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
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.FileSystemCreator
import me.jahnen.libaums.core.fs.FileSystemFactory
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import me.jahnen.libaums.core.partition.Partition
import me.jahnen.libaums.core.partition.PartitionTableEntry
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

        // Unregister any previous receiver to prevent leaks (matching CX File Explorer)
        permissionReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            permissionReceiver = null
        }

        val targetDeviceName = device.usbDevice.deviceName
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_USB_PERMISSION) {
                    val reportedDevice = @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (reportedDevice != null && reportedDevice.deviceName != targetDeviceName) {
                        return
                    }

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    try {
                        context.unregisterReceiver(this)
                        if (permissionReceiver === this) {
                            permissionReceiver = null
                        }
                    } catch (_: Exception) {}
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            onResult(granted)
                        }
                    }
                }
            }
        }
        permissionReceiver = receiver
        try {
            // Android 5.1 (API 22) standard receiver registration (no API 33+ RECEIVER_NOT_EXPORTED)
            context.registerReceiver(receiver, filter)

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
            usbManager.requestPermission(device.usbDevice, permissionIntent)
        } catch (e: Exception) {
            DiagLog.w("UsbMassStorage", "Error requesting USB permission", e)
            permissionReceiver?.let {
                runCatching { context.unregisterReceiver(it) }
                permissionReceiver = null
            }
            onResult(false)
        }
    }

    /**
     * Initializes the device (if not already opened) and returns the primary FileSystem.
     * Matches CX File Explorer ax.b2.l.e():
     * If switching device or re-opening, cleanly closes previous device first.
     */
    fun openFileSystem(device: UsbMassStorageDevice): FileSystem? {
        openFileSystemProvider?.let { return it(device) }
        synchronized(this) {
            if (currentDevice != null) {
                if ((currentDevice === device || currentDevice?.usbDevice?.deviceId == device.usbDevice.deviceId) && currentFs != null) {
                    currentDevice = device
                    return currentFs
                }
                close()
            }
            return try {
                device.init()
                currentDevice = device
                var partitions = runCatching { device.partitions }.getOrElse { emptyList() }
                if (partitions.isEmpty()) {
                    DiagLog.w("UsbMassStorage", "device.partitions is empty, attempting Superfloppy / raw block fallback")
                    val rawDevice = runCatching {
                        val commField = UsbMassStorageDevice::class.java.getDeclaredField("usbCommunication").apply { isAccessible = true }
                        val comm = commField.get(device) as? me.jahnen.libaums.core.usb.UsbCommunication
                        if (comm != null) {
                            me.jahnen.libaums.core.driver.BlockDeviceDriverFactory.createBlockDevice(comm, 0.toByte()).apply { init() }
                        } else null
                    }.getOrNull()
                    if (rawDevice != null) {
                        val entry = PartitionTableEntry(0, 0L, 0L)
                        val fs = RobustFat32FileSystemCreator.read(entry, rawDevice)
                            ?: runCatching { FileSystemFactory.createFileSystem(entry, rawDevice) }.getOrNull()
                        if (fs != null) {
                            val partition = Partition(rawDevice, entry)
                            val fsField = Partition::class.java.getDeclaredField("fileSystem").apply { isAccessible = true }
                            fsField.set(partition, fs)
                            partitions = listOf(partition)
                            device.partitions = partitions
                            DiagLog.i("UsbMassStorage", "Successfully mounted raw Superfloppy FAT32 volume")
                        }
                    }
                }
                val primaryFs = partitions.mapNotNull { part ->
                    runCatching { part.fileSystem }.onFailure { e ->
                        DiagLog.w("UsbMassStorage", "Failed to retrieve filesystem from partition", e)
                    }.getOrNull()
                }.maxByOrNull { it.capacity }
                currentFs = primaryFs
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
                DiagLog.w("UsbMassStorage", "Failed to init USB device: ${device.usbDevice.deviceName}", e)
                close()
                null
            }
        }
    }

    fun getCachedCapacity(): Long = synchronized(this) { currentFs?.capacity ?: cachedCapacity }
    fun getCachedFreeSpace(): Long = synchronized(this) { currentFs?.freeSpace ?: cachedFreeSpace }

    /**
     * Closes the active USB device and releases USB endpoints (matching CX File Explorer ax.b2.l.d()).
     */
    fun close() {
        synchronized(this) {
            permissionReceiver?.let { rx ->
                runCatching { appContext?.unregisterReceiver(rx) }
                permissionReceiver = null
            }
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
}
