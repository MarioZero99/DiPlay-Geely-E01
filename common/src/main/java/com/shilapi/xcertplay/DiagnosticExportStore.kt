package com.shilapi.xcertplay

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** One folder the export card can open. [usb] is false only for the head unit's own Download directory. */
internal data class ExportFolder(
    val label: String,
    val directory: File,
    val usb: Boolean,
    val detail: String? = null,
)

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
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val usb = DiagnosticVolumeProbe.candidates(context)
            .filter { it.absolutePath != downloads.absolutePath }
            .map { dir ->
                val label = formatVolumeLabel(context, dir, usb = true)
                val detail = formatVolumeDetail(context, dir)
                ExportFolder(label, dir, usb = true, detail = detail)
            }
        val internalLabel = context.getString(R.string.export_internal_storage)
        val internalDetail = formatVolumeDetail(context, downloads)
        val places = usb + ExportFolder(internalLabel, downloads, usb = false, detail = internalDetail)
        DiagLog.i("DiagnosticExport", places.joinToString(" | ") { folder ->
            val dir = folder.directory
            val listed = runCatching { dir.list() }.getOrNull()
            "${dir.absolutePath} usb=${folder.usb} dir=${dir.isDirectory} list=${listed?.size ?: "null"} write=${dir.canWrite()}"
        }.ifEmpty { "places empty" })
        return places
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
                val effective = DiagnosticVolumeProbe.resolveVolumePath(File(path))
                if (path == dir.absolutePath || effective.absolutePath == dir.absolutePath) {
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

    /** Directories first, then files, by lower-case name. An unreadable directory returns empty list. */
    fun children(directory: File): List<File> {
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
        val direct = syncWriteText(file, report)
        val written = direct || exportViaRoot(file, report, context.cacheDir)
        val backup = runCatching { saveInternalBackup(context, fileName, report) }.getOrElse { error ->
            DiagLog.w("DiagnosticExport", "private copy failed", error)
            null
        }
        DiagLog.i("DiagnosticExport", "save done direct=$direct written=$written backup=${backup != null} ${file.absolutePath}")
        if (!written && backup == null) throw IOException("Report storage is unavailable")
        if (written) announce(context, listOf(file.absolutePath))
        return ExportOutcome(file.absolutePath, backup?.uri, written)
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
