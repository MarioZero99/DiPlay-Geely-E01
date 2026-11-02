package com.shilapi.xcertplay

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.host.R
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * One folder or USB storage target the export card can open.
 * Supports both local filesystems (internal storage/SD card) and direct USB Host Mass Storage devices.
 */
internal data class ExportFolder(
    val label: String,
    val directory: File? = null,
    val usb: Boolean = false,
    val detail: String? = null,
    val usbDevice: UsbMassStorageDevice? = null,
) {
    val displayPath: String
        get() = when {
            usbDevice != null -> "USB: /"
            directory != null -> directory.absolutePath
            else -> ""
        }

    val saveDestinationHint: String
        get() = when {
            usbDevice != null -> "USB: /DiPlay"
            directory != null -> File(directory, "DiPlay").absolutePath
            else -> ""
        }
}

/**
 * Where the report went. [primaryPath] is the file under the folder the user confirmed.
 * [shareableUri] is the private copy the FileProvider may sign, or null when that copy failed.
 */
internal data class ExportOutcome(
    val primaryPath: String,
    val shareableUri: Uri?,
    /** False when the chosen folder did not take the file. The card must not call that a save. */
    val written: Boolean,
)

/** Saves one diagnostic report into the folder the user confirmed, plus a private copy for sharing. */
internal object DiagnosticExportStore {
    fun folders(context: Context): List<ExportFolder> {
        val result = mutableListOf<ExportFolder>()

        // 1. Physical USB devices via USB Host (libaums SCSI/FAT32, exact match with CX File Explorer)
        val usbDevices = UsbMassStorageManager.getDevices(context)
        for (device in usbDevices) {
            val detail = formatUsbDeviceDetail(context, device)
            val label = formatUsbDeviceLabel(device)
            result.add(ExportFolder(label, usb = true, detail = detail, usbDevice = device))
        }

        // 2. Genuine system-mounted removable volumes (e.g. system SD cards)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val sysVolumes = DiagnosticVolumeProbe.candidates(context)
            .filter { it.absolutePath != downloads.absolutePath }
            .filter { candidate ->
                // If a physical USB Host device is already listed, don't duplicate usbotg
                if (usbDevices.isNotEmpty()) {
                    val leaf = candidate.name.lowercase()
                    !leaf.contains("usbotg") && !leaf.contains("usb")
                } else true
            }
            .map { dir ->
                val label = formatVolumeLabel(context, dir, usb = true)
                val detail = formatVolumeDetail(context, dir)
                ExportFolder(label, directory = dir, usb = true, detail = detail)
            }
        result.addAll(sysVolumes)

        // 3. Internal storage (Downloads)
        val internalLabel = context.getString(R.string.export_internal_storage)
        val internalDetail = formatVolumeDetail(context, downloads)
        result.add(ExportFolder(internalLabel, directory = downloads, usb = false, detail = internalDetail))

        DiagLog.i("DiagnosticExport", result.joinToString(" | ") { folder ->
            "${folder.label} [${folder.displayPath}] usb=${folder.usb}"
        }.ifEmpty { "places empty" })
        return result
    }

    internal fun formatUsbDeviceLabel(device: UsbMassStorageDevice): String {
        val labelFromFs = runCatching {
            val partitions = runCatching { device.partitions }.getOrNull() ?: emptyList()
            partitions.mapNotNull { runCatching { it.fileSystem }.getOrNull() }
                .firstOrNull { it.volumeLabel.isNotBlank() }?.volumeLabel
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (labelFromFs != null) return "U 盘 ($labelFromFs)"

        val productName = runCatching { device.usbDevice.productName }.getOrNull()?.takeIf { it.isNotBlank() }
        return if (productName != null) "U 盘 ($productName)" else "U 盘 (USB 存储)"
    }

    internal fun formatUsbDeviceDetail(context: Context, device: UsbMassStorageDevice): String {
        if (UsbMassStorageManager.hasPermission(context, device)) {
            val fs = UsbMassStorageManager.openFileSystem(device)
            val cap = fs?.capacity ?: UsbMassStorageManager.getCachedCapacity()
            val free = fs?.freeSpace ?: UsbMassStorageManager.getCachedFreeSpace()
            if (cap > 0L) {
                val freeStr = android.text.format.Formatter.formatFileSize(context, free)
                val capStr = android.text.format.Formatter.formatFileSize(context, cap)
                return context.getString(R.string.export_storage_space, freeStr, capStr) + " · USB"
            }
            return "USB 存储设备"
        }
        return "USB 存储设备 (点击授权)"
    }

    internal fun formatVolumeLabel(context: Context, dir: File, usb: Boolean): String {
        if (!usb) return context.getString(R.string.export_internal_storage)
        val sysDesc = runCatching {
            val volumes = DiagnosticVolumeProbe.volumeListProvider?.invoke(context) ?: runCatching {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) ?: return@runCatching null
                sm.javaClass.getMethod("getVolumeList").invoke(sm) as? Array<*>
            }.getOrNull() ?: return@runCatching null
            for (vol in volumes) {
                if (vol == null) continue
                val path = vol.javaClass.getMethod("getPath").invoke(vol) as? String ?: continue
                if (path == dir.absolutePath) {
                    val desc = runCatching {
                        vol.javaClass.getMethod("getDescription", Context::class.java).invoke(vol, context) as? String
                    }.getOrNull()
                    if (!desc.isNullOrBlank()) return@runCatching desc
                }
            }
            null
        }.getOrNull()
        if (!sysDesc.isNullOrBlank()) return sysDesc

        val nameLower = dir.name.lowercase()
        return when {
            nameLower.contains("usb") || nameLower.contains("udisk") || nameLower.contains("otg") || nameLower.contains("sda") || nameLower.contains("sdb") ->
                context.getString(R.string.export_usb_storage, dir.name)
            nameLower.contains("sdcard") ->
                context.getString(R.string.export_sdcard_storage, dir.name)
            else -> dir.name
        }
    }

    internal fun formatVolumeDetail(context: Context, dir: File): String {
        val spaceInfo = runCatching {
            val stat = android.os.StatFs(dir.absolutePath)
            val blockCount = stat.blockCountLong
            if (blockCount > 0L) {
                val available = stat.availableBlocksLong * stat.blockSizeLong
                val total = blockCount * stat.blockSizeLong
                val freeStr = android.text.format.Formatter.formatFileSize(context, available)
                val totalStr = android.text.format.Formatter.formatFileSize(context, total)
                context.getString(R.string.export_storage_space, freeStr, totalStr)
            } else null
        }.getOrNull()
        return if (spaceInfo != null) "$spaceInfo · ${dir.absolutePath}" else dir.absolutePath
    }

    /**
     * In-memory representation of a file or directory discovered via RootShellBridge.
     */
    open class VirtualFile(
        parent: File,
        name: String,
        private val isDir: Boolean,
        private val fileLength: Long = 0L,
    ) : File(parent, name) {
        override fun isDirectory(): Boolean = isDir
        override fun isFile(): Boolean = !isDir
        override fun length(): Long = fileLength
    }

    fun isReadable(directory: File): Boolean {
        if (runCatching { directory.listFiles() }.getOrNull() != null) return true
        if (com.shilapi.xcertplay.system.RootShellBridge.isAvailable()) {
            val (rc, _) = com.shilapi.xcertplay.system.RootShellBridge.executeWithOutput("ls -d \"${directory.absolutePath}\"")
            return rc == 0
        }
        return false
    }

    /** Directories first, then files, by lower-case name. Falls back to RootShellBridge if normal File API is blocked. */
    fun children(directory: File): List<File> {
        val listed = runCatching { directory.listFiles() }.getOrNull()
        if (listed != null) {
            return listed.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
        if (com.shilapi.xcertplay.system.RootShellBridge.isAvailable()) {
            val (rc, output) = com.shilapi.xcertplay.system.RootShellBridge.executeWithOutput("ls -1p \"${directory.absolutePath}\"")
            if (rc == 0 && output.isNotBlank()) {
                return output.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { name ->
                        val isDir = name.endsWith("/")
                        val cleanName = name.removeSuffix("/")
                        VirtualFile(directory, cleanName, isDir)
                    }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    .toList()
            }
        }
        return emptyList()
    }

    /** Directories first, then files, for USB Mass Storage. */
    fun usbChildren(directory: UsbFile): List<UsbFile> {
        val listed = runCatching { directory.listFiles() }.getOrNull()
        return listed?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    /**
     * Writes `<directory>/DiPlay/<fileName>`. The private backup is what the share sheet opens,
     * and it is still written when the chosen place does not take the file. [ExportOutcome.written]
     * is false in that case. Both writes failing throws, so the card does not claim a save.
     */
    fun save(context: Context, directory: File, fileName: String, report: String): ExportOutcome {
        val file = File(directory, "DiPlay/$fileName")
        DiagLog.i("DiagnosticExport", "save ${file.absolutePath} chars=${report.length}")
        var direct = syncWriteText(file, report)
        if (!direct) {
            direct = exportViaRoot(file, report, context.cacheDir)
        }
        val backup = runCatching { saveInternalBackup(context, fileName, report) }.getOrElse { error ->
            DiagLog.w("DiagnosticExport", "private copy failed", error)
            null
        }
        DiagLog.i("DiagnosticExport", "save done direct=$direct backup=${backup != null} ${file.absolutePath}")
        if (!direct && backup == null) throw IOException("Report storage is unavailable")
        if (direct) announce(context, listOf(file.absolutePath))
        return ExportOutcome(file.absolutePath, backup?.uri, direct)
    }

    /**
     * Saves the report onto a USB mass-storage device through libaums (FAT32 sectors).
     * There is no root or mount-path fallback: MTK shell directories such as
     * /mnt/media_rw/usbotg are not the stick. [ExportOutcome.written] is false when the
     * stick did not take the file. The private copy is still made for sharing.
     */
    fun saveToUsb(
        context: Context,
        device: UsbMassStorageDevice,
        fileName: String,
        report: String,
    ): ExportOutcome {
        val deviceName = runCatching { device.usbDevice.deviceName }.getOrDefault("unknown")
        DiagLog.i("DiagnosticExport", "saveToUsb device=$deviceName fileName=$fileName chars=${report.length}")
        val primaryPath = "USB: /DiPlay/$fileName"
        var written = false
        try {
            val fs = UsbMassStorageManager.openFileSystem(device)
            if (fs == null) {
                DiagLog.w("DiagnosticExport", "saveToUsb: libaums returned no filesystem")
            } else {
                val root = fs.rootDirectory
                val diplayDir = root.search("DiPlay") ?: root.createDirectory("DiPlay")
                if (!diplayDir.isDirectory) {
                    throw IOException("Target DiPlay path exists but is not a directory")
                }
                val targetFile = diplayDir.search(fileName) ?: diplayDir.createFile(fileName)
                val bytes = report.toByteArray(Charsets.UTF_8)
                UsbFileOutputStream(targetFile).use { out ->
                    out.write(bytes)
                    out.flush()
                }
                written = targetFile.length == bytes.size.toLong()
                if (written) {
                    DiagLog.i("DiagnosticExport", "saveToUsb success via libaums: $primaryPath bytes=${bytes.size}")
                } else {
                    DiagLog.w("DiagnosticExport", "saveToUsb length mismatch path=$primaryPath expected=${bytes.size} actual=${targetFile.length}")
                }
            }
        } catch (e: Exception) {
            DiagLog.w("DiagnosticExport", "saveToUsb via libaums failed", e)
            written = false
        }

        val backup = runCatching { saveInternalBackup(context, fileName, report) }.getOrElse { error ->
            DiagLog.w("DiagnosticExport", "private copy failed", error)
            null
        }
        DiagLog.i("DiagnosticExport", "saveToUsb complete: written=$written primaryPath=$primaryPath backup=${backup != null}")
        if (!written && backup == null) throw IOException("Report storage is unavailable")
        return ExportOutcome(primaryPath, backup?.uri, written = written)
    }

    private data class Backup(val uri: Uri?, val file: File)

    private fun saveInternalBackup(context: Context, fileName: String, report: String): Backup =
        saveBackupIn(context, File(context.filesDir, "diagnostic-reports"), fileName, report)

    private fun saveBackupIn(context: Context, directory: File, fileName: String, report: String): Backup {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Report storage is unavailable")
        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        try {
            if (!syncWriteText(file, report)) throw IOException("Report storage is unavailable")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            directory.listFiles()?.filter { it != file && it.isFile }
                ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            return Backup(uri, file)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun syncWriteText(file: File, content: String): Boolean {
        return try {
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                DiagLog.w("DiagnosticExport", "direct write ${file.absolutePath} mkdirs failed")
                return false
            }
            if (parent != null && !parent.canWrite()) {
                DiagLog.w("DiagnosticExport", "direct write ${file.absolutePath} parent not writable")
                return false
            }
            FileOutputStream(file).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            true
        } catch (error: Exception) {
            DiagLog.w("DiagnosticExport", "direct write ${file.absolutePath} failed", error)
            false
        }
    }

    /** Copies one file into place with root when the app itself cannot write the mount. */
    internal fun exportViaRoot(targetFile: File, report: String, cacheDir: File): Boolean {
        if (!com.shilapi.xcertplay.system.RootShellBridge.isAvailable()) {
            DiagLog.w("DiagnosticExport", "root copy skipped, ExtraUtilsService missing: ${targetFile.absolutePath}")
            return false
        }
        val parentDir = targetFile.parentFile ?: return false
        val tempFile = File(cacheDir, "diag_export_${System.currentTimeMillis()}.tmp")
        val targetPath = targetFile.absolutePath
        return try {
            FileOutputStream(tempFile).use { stream ->
                stream.write(report.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            val parentPath = parentDir.absolutePath
            val tempPath = tempFile.absolutePath

            val mkdirOk = com.shilapi.xcertplay.system.RootShellBridge.execute("mkdir -p \"$parentPath\"")
            if (!mkdirOk) {
                DiagLog.w("DiagnosticExport", "USB root copy $targetPath failed: mkdir -p rejected")
                return false
            }
            val cpOk = com.shilapi.xcertplay.system.RootShellBridge.execute("cp -f \"$tempPath\" \"$targetPath\"")
            if (!cpOk) {
                DiagLog.w("DiagnosticExport", "USB root copy $targetPath failed: cp rejected")
                return false
            }
            val chmodOk = com.shilapi.xcertplay.system.RootShellBridge.execute("chmod 644 \"$targetPath\"")
            if (!chmodOk) {
                DiagLog.w("DiagnosticExport", "USB root copy $targetPath: chmod 644 rejected, proceeding")
            }
            val syncOk = com.shilapi.xcertplay.system.RootShellBridge.execute("sync", timeoutMillis = 5_000L)
            if (!syncOk) {
                DiagLog.w("DiagnosticExport", "USB root copy $targetPath failed: sync rejected or timed out")
                return false
            }
            DiagLog.i("DiagnosticExport", "USB root copy $targetPath success")
            true
        } catch (error: Throwable) {
            DiagLog.w("DiagnosticExport", "USB root copy $targetPath failed: ${error.message}")
            false
        } finally {
            tempFile.delete()
        }
    }

    private fun announce(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), Array(paths.size) { "text/plain" }, null)
        }
    }
}
