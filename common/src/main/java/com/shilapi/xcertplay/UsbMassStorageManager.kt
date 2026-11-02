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
import me.jahnen.libaums.core.fs.FileSystem

/**
 * Manages USB Mass Storage devices in user space via libaums (SCSI/FAT32 over USB Host),
 * exactly matching CX File Explorer's architecture (ax.b2.l) without needing kernel mounts or root.
 * Strictly compatible with Android 5.1 Lollipop (API 22) in-vehicle head units.
 */
internal object UsbMassStorageManager {
    const val ACTION_USB_PERMISSION = "com.shilapi.xcertplay.USB_PERMISSION"

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
                val partitions = device.partitions
                val primaryFs = partitions.mapNotNull { runCatching { it.fileSystem }.getOrNull() }
                    .maxByOrNull { it.capacity }
                currentFs = primaryFs
                if (primaryFs != null) {
                    cachedCapacity = primaryFs.capacity
                    cachedFreeSpace = primaryFs.freeSpace
                } else {
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
