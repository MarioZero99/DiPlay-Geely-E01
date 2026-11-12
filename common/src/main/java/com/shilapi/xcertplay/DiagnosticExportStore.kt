package com.shilapi.xcertplay

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.host.R
import me.jahnen.libaums.core.UsbMassStorageDevice
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
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val mounted = DiagnosticVolumeProbe.candidates(context)
            .filter { it.absolutePath != downloads.absolutePath }
            .filter { isRealMountedStick(it) }
            .map { dir ->
                ExportFolder(
                    label = formatVolumeLabel(context, dir, usb = true),
                    directory = dir,
                    usb = true,
                    detail = formatVolumeDetail(context, dir),
                )
            }
        result.addAll(mounted)
        if (mounted.isEmpty()) {
            for (device in UsbMassStorageManager.getDevices(context)) {
                result.add(
                    ExportFolder(
                        label = formatUsbDeviceLabel(device),
                        usb = true,
                        detail = context.getString(R.string.export_usb_direct_detail),
                        usbDevice = device,
                    ),
                )
            }
        }
        result.add(
            ExportFolder(
                context.getString(R.string.export_internal_storage),
                directory = downloads,
                usb = false,
                detail = formatVolumeDetail(context, downloads),
            ),
        )
        DiagLog.i("DiagnosticExport", result.joinToString(" | ") { folder ->
            "${folder.label} [${folder.displayPath}] usb=${folder.usb}"
        }.ifEmpty { "places empty" })
        return result
    }

    internal fun isRealMountedStick(dir: File): Boolean {
        val leaf = dir.name.lowercase()
        return leaf != "sdcard1" && leaf != "sdcard2" && leaf != "usbotg" && leaf != "udisk"
    }

    internal fun formatUsbDeviceLabel(device: UsbMassStorageDevice): String {
        val productName = runCatching { device.usbDevice.productName }.getOrNull()?.takeIf { it.isNotBlank() }
        return if (productName != null) "U 盘 ($productName)" else "U 盘 (USB 存储)"
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
        val expected = report.toByteArray(Charsets.UTF_8).size.toLong()
        val actual = if (file.isFile) file.length() else -1L
        DiagLog.i(
            "DiagnosticExport",
            "save done direct=$direct bytes=$expected fileBytes=$actual backup=${backup != null} ${file.absolutePath}",
        )
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
    ): ExportOutcome = synchronized(UsbMassStorageManager) {
        // Open, the sector write, and close share the manager lock. A retry started while init()
        // is still inside the first save waits here, instead of closing the stick mid-write.
        val deviceName = runCatching { device.usbDevice.deviceName }.getOrDefault("unknown")
        DiagLog.i("DiagnosticExport", "saveToUsb device=$deviceName fileName=$fileName chars=${report.length}")
        val primaryPath = "USB: /DiPlay/$fileName"
        // 日志行的 path 不带分隔空格，与 Global Constraints 里固定的 grep 格式一致。
        val logPath = "USB:/DiPlay/$fileName"
        val bytes = report.toByteArray(Charsets.UTF_8)
        var written = false
        try {
            val fs = UsbMassStorageManager.openFileSystem(device)
            val vendorId = runCatching { device.usbDevice.vendorId }.getOrDefault(0)
            val productId = runCatching { device.usbDevice.productId }.getOrDefault(0)
            val permission = UsbMassStorageManager.hasPermission(context, device)
            DiagLog.i(
                "DiagnosticExport",
                UsbMassStorageManager.describeOpen(vendorId, productId, permission, UsbMassStorageManager.lastOpenFacts),
            )
            if (fs == null) {
                DiagLog.w("DiagnosticExport", "saveToUsb: libaums returned no filesystem")
            } else {
                written = writeReport(fs, fileName, bytes)
                if (written) {
                    DiagLog.i("DiagnosticExport", "saveToUsb success via libaums: $primaryPath bytes=${bytes.size}")
                } else {
                    DiagLog.w("DiagnosticExport", "saveToUsb length mismatch path=$primaryPath expected=${bytes.size}")
                }
            }
            DiagLog.i("DiagnosticExport", describeSave(written, bytes.size, logPath))
        } catch (e: Exception) {
            DiagLog.w("DiagnosticExport", "saveToUsb via libaums failed", e)
            written = false
            DiagLog.i("DiagnosticExport", describeSave(written, bytes.size, logPath))
        } finally {
            UsbMassStorageManager.close()
        }

        val backup = runCatching { saveInternalBackup(context, fileName, report) }.getOrElse { error ->
            DiagLog.w("DiagnosticExport", "private copy failed", error)
            null
        }
        DiagLog.i("DiagnosticExport", "saveToUsb complete: written=$written primaryPath=$primaryPath backup=${backup != null}")
        if (!written && backup == null) throw IOException("Report storage is unavailable")
        ExportOutcome(primaryPath, backup?.uri, written = written)
    }

    internal fun describeSave(written: Boolean, bytes: Int, path: String): String =
        "usb save written=$written bytes=$bytes path=$path"

    /**
     * Writes `<fs>/DiPlay/<fileName>` and reports whether the file took every byte.
     * An existing file of the same name is replaced so a retry cannot leave the old report behind.
     */
    internal fun writeReport(fs: me.jahnen.libaums.core.fs.FileSystem, fileName: String, bytes: ByteArray): Boolean {
        val root = fs.rootDirectory
        val folder = root.search("DiPlay") ?: root.createDirectory("DiPlay")
        if (!folder.isDirectory) return false
        val existing = folder.search(fileName)
        existing?.delete()
        val file = folder.createFile(fileName)
        UsbFileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
        }
        return file.length == bytes.size.toLong()
    }

    private data class Backup(val uri: Uri?, val file: File)

    private fun saveInternalBackup(context: Context, fileName: String, report: String): Backup =
        PerfTrace.span("export/backup") {
            saveBackupIn(context, File(context.filesDir, "diagnostic-reports"), fileName, report)
        }

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
            val bytes = content.toByteArray(Charsets.UTF_8)
            FileOutputStream(file).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            val actual = if (file.isFile) file.length() else -1L
            if (actual != bytes.size.toLong()) {
                DiagLog.w(
                    "DiagnosticExport",
                    "direct write ${file.absolutePath} length mismatch expected=${bytes.size} actual=$actual",
                )
                return false
            }
            DiagLog.i("DiagnosticExport", "direct write ${file.absolutePath} bytes=$actual")
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
            val expected = report.toByteArray(Charsets.UTF_8).size.toLong()
            if (targetFile.isFile && targetFile.length() == expected) {
                DiagLog.i("DiagnosticExport", "USB root copy $targetPath success bytes=$expected")
                return true
            }
            val listed = com.shilapi.xcertplay.system.RootShellBridge.executeWithOutput("ls -l \"$targetPath\"")
            val size = lengthFromLs(listed.second)
            if (listed.first == 0 && size == expected) {
                DiagLog.i("DiagnosticExport", "USB root copy $targetPath success via ls bytes=$expected")
                return true
            }
            DiagLog.w(
                "DiagnosticExport",
                "USB root copy $targetPath exit 0 but file missing listed=$size expected=$expected ls=${listed.second.take(180)}",
            )
            false
        } catch (error: Throwable) {
            DiagLog.w("DiagnosticExport", "USB root copy $targetPath failed: ${error.message}")
            false
        } finally {
            tempFile.delete()
        }
    }

    /** `ls -l` 里日期前面的最后一个纯数字是文件大小。链接数 1 在日期之前，不能拿第一个数字。 */
    internal fun lengthFromLs(output: String): Long? {
        val tokens = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("-") }
            ?.split(Regex("\\s+"))
            ?: return null
        val dateAt = tokens.indexOfFirst {
            it.contains(':') || it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
        }
        val before = if (dateAt > 0) tokens.subList(0, dateAt) else tokens
        return before.lastOrNull { it.all(Char::isDigit) }?.toLongOrNull()
    }

    private fun announce(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), Array(paths.size) { "text/plain" }, null)
        }
    }
}
