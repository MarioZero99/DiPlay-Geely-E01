package com.shilapi.xcertplay

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.FileSystemCreator
import me.jahnen.libaums.core.fs.FileSystemFactory
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import me.jahnen.libaums.core.partition.Partition
import me.jahnen.libaums.core.partition.PartitionTableEntry
import me.jahnen.libaums.core.usb.UsbCommunication
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Robust FAT32 filesystem creator that adheres to the Microsoft FAT32 specification.
 * Does not strictly enforce ASCII "FAT32   " at offset 82..89, accommodating USB sticks
 * formatted by various third-party tools, macOS, or Linux mkfs.vfat.
 */
internal object RobustFat32FileSystemCreator : FileSystemCreator {
    override fun read(entry: PartitionTableEntry, blockDevice: BlockDeviceDriver): FileSystem? {
        val defaultFs = runCatching { Fat32FileSystem.read(blockDevice) }.getOrNull()
        if (defaultFs != null) return defaultFs

        return runCatching {
            val buffer = ByteBuffer.allocate(512)
            blockDevice.read(0L, buffer)
            buffer.flip()

            val sig0 = buffer.get(510).toInt() and 0xFF
            val sig1 = buffer.get(511).toInt() and 0xFF
            if (sig0 != 0x55 || sig1 != 0xAA) {
                return null
            }

            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val bytesPerSector = buffer.getShort(11).toInt() and 0xFFFF
            val sectorsPerCluster = buffer.get(13).toInt() and 0xFF
            val reservedSectors = buffer.getShort(14).toInt() and 0xFFFF
            val fatCount = buffer.get(16).toInt() and 0xFF
            val totalSectors32 = buffer.getInt(32).toLong() and 0xFFFFFFFFL
            val sectorsPerFat32 = buffer.getInt(36).toLong() and 0xFFFFFFFFL
            val rootDirStartCluster = buffer.getInt(44).toLong() and 0xFFFFFFFFL

            if (bytesPerSector !in listOf(512, 1024, 2048, 4096) ||
                sectorsPerCluster !in listOf(1, 2, 4, 8, 16, 32, 64, 128) ||
                reservedSectors == 0 || fatCount == 0 ||
                totalSectors32 == 0L || sectorsPerFat32 == 0L || rootDirStartCluster == 0L
            ) {
                return null
            }

            DiagLog.i("UsbMassStorage", "RobustFat32: Bypassing strict offset 82 magic check, instantiating Fat32FileSystem via reflection")
            val constructor = Fat32FileSystem::class.java.getDeclaredConstructor(
                BlockDeviceDriver::class.java,
                ByteBuffer::class.java,
            )
            constructor.isAccessible = true
            constructor.newInstance(blockDevice, buffer) as FileSystem
        }.onFailure { e ->
            DiagLog.w("UsbMassStorage", "RobustFat32FileSystemCreator error", e)
        }.getOrNull()
    }
}

/**
 * Manages USB Mass Storage devices in user space via libaums (SCSI/FAT32 over USB Host),
 * exactly matching CX File Explorer's architecture (ax.b2.l) without needing kernel mounts or root.
 * Strictly compatible with Android 5.1 Lollipop (API 22) in-vehicle head units.
 */
internal object UsbMassStorageManager {
    const val ACTION_USB_PERMISSION = "com.shilapi.xcertplay.USB_PERMISSION"

    init {
        runCatching {
            FileSystemFactory.registerFileSystem(RobustFat32FileSystemCreator, 10)
        }.onFailure {
            DiagLog.w("UsbMassStorage", "Failed to register RobustFat32FileSystemCreator", it)
        }
    }

    /** Test injection hooks for unit tests. */
    internal var deviceListProvider: ((Context) -> List<UsbMassStorageDevice>)? = null
    internal var hasPermissionProvider: ((Context, UsbMassStorageDevice) -> Boolean)? = null
    internal var openFileSystemProvider: ((UsbMassStorageDevice) -> FileSystem?)? = null

    internal data class UsbOpenFacts(
        val partitionCount: Int,
        val fat32: Boolean,
        val error: String?,
    )

    internal var lastOpenFacts: UsbOpenFacts = UsbOpenFacts(0, false, "not opened")

    /** How long a missing permission broadcast may leave the save card waiting. */
    internal var permissionWaitMillis = 12_000L
    internal var permissionPollMillis = 250L
    private var permissionGeneration = 0

    internal fun describeOpen(vendorId: Int, productId: Int, permission: Boolean, facts: UsbOpenFacts): String {
        val error = facts.error ?: "none"
        return "usb open vid=%04x pid=%04x perm=%s partitions=%d fat32=%s error=%s".format(
            vendorId,
            productId,
            permission,
            facts.partitionCount,
            facts.fat32,
            error,
        )
    }

    // Currently opened device and filesystem (matching CX File Explorer ax.b2.l singleton)
    private var currentDevice: UsbMassStorageDevice? = null
    private var currentFs: FileSystem? = null
    private var cachedCapacity: Long = 0L
    private var cachedFreeSpace: Long = 0L

    private var appContext: Context? = null
    private var permissionReceiver: BroadcastReceiver? = null

    fun getDevices(context: Context): List<UsbMassStorageDevice> {
        deviceListProvider?.let { return it(context) }
        return runCatching {
            UsbMassStorageDevice.getMassStorageDevices(context).toList()
        }.getOrElse { error ->
            DiagLog.w("UsbMassStorage", "Failed to enumerate USB mass storage devices", error)
            emptyList()
        }
    }

    fun hasPermission(context: Context, device: UsbMassStorageDevice): Boolean {
        hasPermissionProvider?.let { return it(context, device) }
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usbManager.hasPermission(device.usbDevice)
    }

    fun requestPermission(
        activity: Activity,
        device: UsbMassStorageDevice,
        onResult: (Boolean) -> Unit,
    ) {
        val context = activity.applicationContext
        appContext = context
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            onResult(false)
            return
        }
        if (hasPermission(context, device)) {
            onResult(true)
            return
        }
        val usb = runCatching { device.usbDevice }.getOrNull()
        if (usb == null) {
            DiagLog.w("UsbMassStorage", "USB permission skipped: device has no UsbDevice")
            onResult(false)
            return
        }

        // Unregister any previous receiver to prevent leaks (matching CX File Explorer)
        permissionReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            permissionReceiver = null
        }

        val generation = ++permissionGeneration
        val targetDeviceName = usb.deviceName
        val wait = UsbPermissionWait(
            hasPermission = { hasPermission(context, device) },
            onResult = { granted ->
                if (generation != permissionGeneration) return@UsbPermissionWait
                if (granted) unregisterPermissionReceiver(context)
                DiagLog.i("DiagnosticExport", "usb permission device=$targetDeviceName granted=$granted")
                activity.runOnUiThread {
                    if (generation != permissionGeneration) return@runOnUiThread
                    if (!activity.isFinishing && !activity.isDestroyed) onResult(granted)
                }
            },
            waitMillis = permissionWaitMillis,
            stepMillis = permissionPollMillis,
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                val reportedDevice = @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                wait.onBroadcast(granted, reportedDevice?.deviceName, targetDeviceName)
            }
        }
        permissionReceiver = receiver
        try {
            // Android 5.1 (API 22) standard receiver registration (no API 33+ RECEIVER_NOT_EXPORTED)
            context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))

            // Flags: 0 on Android 5.1; 0x02000000 (FLAG_MUTABLE) on API 31+ if ever run there
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or 0x02000000
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(usb, permissionIntent)
            // This head unit often grants the device and then drops the broadcast. Polling
            // hasPermission is what lets the save continue. The wait still ends, so the card
            // cannot sit on "正在读取…" until the process is killed.
            val main = Handler(Looper.getMainLooper())
            wait.start { delay, block ->
                main.postDelayed({
                    if (generation != permissionGeneration) return@postDelayed
                    block()
                }, delay)
            }
        } catch (e: Exception) {
            DiagLog.w("UsbMassStorage", "Error requesting USB permission", e)
            permissionGeneration++
            unregisterPermissionReceiver(context)
            onResult(false)
        }
    }

    private fun unregisterPermissionReceiver(context: Context) {
        permissionReceiver?.let { rx ->
            runCatching { context.unregisterReceiver(rx) }
            if (permissionReceiver === rx) permissionReceiver = null
        }
    }

    /**
     * Initializes the device (if not already opened) and returns the primary FileSystem.
     * Matches CX File Explorer ax.b2.l.e():
     * If switching device or re-opening, cleanly closes previous device first.
     */
    fun openFileSystem(device: UsbMassStorageDevice): FileSystem? {
        lastOpenFacts = UsbOpenFacts(0, false, "not opened")
        openFileSystemProvider?.let { provider ->
            val provided = provider(device)
            lastOpenFacts = UsbOpenFacts(
                partitionCount = 0,
                fat32 = provided != null,
                error = if (provided == null) "no filesystem" else null,
            )
            return provided
        }
        synchronized(this) {
            if (currentDevice != null) {
                if ((currentDevice === device || currentDevice?.usbDevice?.deviceId == device.usbDevice.deviceId) && currentFs != null) {
                    currentDevice = device
                    return currentFs
                }
                close()
            }
            return try {
                DiagLog.i(
                    "UsbMassStorage",
                    "init begin name=${runCatching { device.usbDevice.deviceName }.getOrDefault("unknown")}",
                )
                device.init()
                currentDevice = device
                var partitions = runCatching { device.partitions }.getOrElse { emptyList() }
                var openError: String? = null
                if (partitions.isEmpty()) {
                    DiagLog.w("UsbMassStorage", "device.partitions is empty, reading LUN 0 sector 0")
                    try {
                        val commField = UsbMassStorageDevice::class.java.getDeclaredField("usbCommunication")
                        commField.isAccessible = true
                        val comm = commField.get(device) as? UsbCommunication
                        if (comm == null) {
                            openError = "no filesystem"
                        } else {
                            val recovery = recoverLunZero(comm)
                            val fs = recovery.fileSystem
                            val decision = recovery.decision
                            if (fs != null) {
                                val lba = decision.mountLba ?: 0L
                                val entry = PartitionTableEntry(
                                    decision.partitionType, lba, decision.sectorCount,
                                )
                                val partition = Partition(recovery.blockDevice, entry)
                                val fsField = Partition::class.java.getDeclaredField("fileSystem")
                                fsField.isAccessible = true
                                fsField.set(partition, fs)
                                partitions = listOf(partition)
                                device.partitions = partitions
                                DiagLog.i("UsbMassStorage", "usb partition mounted lba=$lba")
                            } else if (decision.mountLba != null) {
                                DiagLog.w("UsbMassStorage", "usb partition lba=${decision.mountLba} fat32=false")
                                openError = "no filesystem"
                            } else {
                                openError = decision.error
                            }
                        }
                    } catch (e: Exception) {
                        DiagLog.w("UsbMassStorage", "LUN 0 read failed", e)
                        openError = "${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                val primaryFs = partitions.mapNotNull { part ->
                    runCatching { part.fileSystem }.onFailure { e ->
                        DiagLog.w("UsbMassStorage", "Failed to retrieve filesystem from partition", e)
                    }.getOrNull()
                }.maxByOrNull { it.capacity }
                currentFs = primaryFs
                lastOpenFacts = UsbOpenFacts(
                    partitionCount = partitions.size,
                    fat32 = primaryFs != null,
                    error = if (primaryFs == null) openError ?: "no filesystem" else null,
                )
                if (primaryFs != null) {
                    cachedCapacity = primaryFs.capacity
                    cachedFreeSpace = primaryFs.freeSpace
                    DiagLog.i("UsbMassStorage", "USB mounted: label='${primaryFs.volumeLabel}', capacity=${primaryFs.capacity}, free=${primaryFs.freeSpace}")
                } else {
                    DiagLog.w("UsbMassStorage", "USB device initialized but no valid filesystem found")
                    close()
                }
                primaryFs
            } catch (e: Exception) {
                lastOpenFacts = UsbOpenFacts(0, false, "${e.javaClass.simpleName}: ${e.message}")
                DiagLog.w("UsbMassStorage", "Failed to init USB device: ${device.usbDevice.deviceName}", e)
                close()
                null
            }
        }
    }

    fun getCachedCapacity(): Long = synchronized(this) { currentFs?.capacity ?: cachedCapacity }
    fun getCachedFreeSpace(): Long = synchronized(this) { currentFs?.freeSpace ?: cachedFreeSpace }

    /**
     * Mounts the FAT32 volume [decision] points at. A partition table entry puts the volume at its
     * start sector, so the block device is offset there; a superfloppy is mounted on the device as is.
     */
    internal fun mountAtDecision(device: BlockDeviceDriver, decision: Fat32VolumeDecision): FileSystem? {
        val lba = decision.mountLba ?: return null
        val entry = PartitionTableEntry(decision.partitionType, lba, decision.sectorCount)
        val target = if (lba == 0L) device else Partition(device, entry)
        return RobustFat32FileSystemCreator.read(entry, target)
    }

    /** The LUN 0 block device and what [Fat32VolumeLayout] made of its first sector. */
    internal class LunZeroRecovery(
        val decision: Fat32VolumeDecision,
        val blockDevice: BlockDeviceDriver,
        val fileSystem: FileSystem?,
    )

    /**
     * Recovers a stick whose partition table libaums could not read. Get Max LUN returns `0xFF` on
     * the target stick, so libaums never initializes a LUN, and a stalled control transfer can leave
     * the bulk endpoints halted. Reset the device, clear both halts, then read LUN 0's first sector
     * and mount what [Fat32VolumeLayout] decides. [createBlockDevice] is a seam for the unit test.
     */
    internal fun recoverLunZero(
        comm: UsbCommunication,
        createBlockDevice: (UsbCommunication, Byte) -> BlockDeviceDriver = { communication, lun ->
            BlockDeviceDriverFactory.createBlockDevice(communication, lun)
        },
    ): LunZeroRecovery {
        runCatching {
            comm.controlTransfer(0x21, 0xFF, 0, comm.usbInterface.id, ByteArray(0), 0)
        }
        runCatching { comm.clearFeatureHalt(comm.outEndpoint) }
        runCatching { comm.clearFeatureHalt(comm.inEndpoint) }
        DiagLog.i("UsbMassStorage", "usb recover bulk-reset")
        val raw = createBlockDevice(comm, 0.toByte())
        raw.init()
        val sector = ByteArray(512)
        raw.read(0L, ByteBuffer.wrap(sector))
        val decision = Fat32VolumeLayout.decide(sector)
        DiagLog.i("UsbMassStorage", decision.logLine)
        return LunZeroRecovery(decision, raw, mountAtDecision(raw, decision))
    }

    /**
     * Closes the active USB device and releases USB endpoints (matching CX File Explorer ax.b2.l.d()).
     */
    fun close() {
        cancelPermissionWait()
        synchronized(this) { closeDeviceLocked() }
    }

    /**
     * Cancels a permission wait on the caller thread, then releases the device on a background
     * thread. init() holds the manager lock for the whole SCSI exchange, and waiting for that
     * lock on the UI thread freezes the page.
     */
    fun closeInBackground() {
        cancelPermissionWait()
        Thread({
            synchronized(this) { closeDeviceLocked() }
        }, "diplay-usb-close").start()
    }

    private fun cancelPermissionWait() {
        // Bump before taking the lock. A permission callback must not start a save after the card is gone.
        permissionGeneration++
        val context = appContext
        if (context != null) {
            unregisterPermissionReceiver(context)
        } else {
            permissionReceiver = null
        }
    }

    private fun closeDeviceLocked() {
        val dev = currentDevice
        if (dev != null) {
            currentFs?.let {
                cachedCapacity = it.capacity
                cachedFreeSpace = it.freeSpace
            }
            runCatching { dev.close() }
            currentDevice = null
            currentFs = null
        }
    }
}

/**
 * Waits out a permission broadcast this head unit often drops.
 * A null device extra still counts: the ROM omitted it. A different device name is ignored,
 * and [hasPermission] is polled until [waitMillis]. Timeout reports false once; a later grant
 * still reports true, because the receiver stays until the grant, a new request, or [UsbMassStorageManager.close].
 */
internal class UsbPermissionWait(
    private val hasPermission: () -> Boolean,
    private val onResult: (Boolean) -> Unit,
    private val waitMillis: Long = 12_000L,
    private val stepMillis: Long = 250L,
) {
    private var outcome: Boolean? = null
    private var elapsed = 0L

    fun start(postDelayed: (Long, () -> Unit) -> Unit) {
        postDelayed(stepMillis) { tick(postDelayed) }
    }

    fun onBroadcast(granted: Boolean, reportedDeviceName: String?, targetDeviceName: String) {
        if (!acceptsPermissionBroadcast(targetDeviceName, reportedDeviceName)) return
        finish(granted)
    }

    private fun tick(postDelayed: (Long, () -> Unit) -> Unit) {
        if (outcome == true) return
        if (hasPermission()) {
            finish(true)
            return
        }
        elapsed += stepMillis
        if (outcome == null && elapsed >= waitMillis) finish(false)
        if (outcome != true && elapsed < waitMillis) postDelayed(stepMillis) { tick(postDelayed) }
    }

    private fun finish(granted: Boolean) {
        if (granted) {
            if (outcome == true) return
            outcome = true
            onResult(true)
            return
        }
        if (outcome != null) return
        outcome = false
        onResult(false)
    }
}

/** A missing extra is a grant for this request. A different device name belongs to another request. */
internal fun acceptsPermissionBroadcast(targetDeviceName: String, reportedDeviceName: String?): Boolean =
    reportedDeviceName == null || reportedDeviceName == targetDeviceName
