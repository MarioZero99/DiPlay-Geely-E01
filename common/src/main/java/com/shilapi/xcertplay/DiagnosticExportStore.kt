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
)

/**
 * Where the report went. [primaryPath] is the file under the folder the user confirmed.
 * [shareableUri] is the private copy the FileProvider may sign, or null when that copy failed.
 */
internal data class ExportOutcome(
    val primaryPath: String,
    val shareableUri: Uri?,
)

/** Saves one diagnostic report into the folder the user confirmed, plus a private copy for sharing. */
internal object DiagnosticExportStore {
    fun folders(
        context: Context,
        extraMountPaths: Collection<String> = emptySet(),
        procMountsFile: File? = DiagnosticVolumeProbe.defaultProcMounts(),
    ): List<ExportFolder> {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val usb = preferListedCopy(
            DiagnosticVolumeProbe.candidates(context, extraMountPaths, procMountsFile)
                .filter { canList(it) && it.absolutePath != downloads.absolutePath },
        ).map { ExportFolder(it.name, it, usb = true) }
        return usb + ExportFolder(context.getString(R.string.export_internal_storage), downloads, usb = false)
    }

    /** Directories first, then files, by lower-case name. An unreadable directory is an empty list. */
    fun children(directory: File): List<File> {
        val listed = runCatching { directory.listFiles() }.getOrNull() ?: return emptyList()
        return listed.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /**
     * Two paths with the same leaf are one stick. Prefer the copy that is not under `/mnt/media_rw/`,
     * which is the path a file manager shows. A raw path that is the only one still listable stays.
     */
    internal fun preferListedCopy(dirs: List<File>): List<File> {
        val chosen = linkedMapOf<String, File>()
        for (dir in dirs) {
            val have = chosen[dir.name]
            val raw = dir.path.contains("/mnt/media_rw/")
            if (have == null || (have.path.contains("/mnt/media_rw/") && !raw)) {
                chosen[dir.name] = dir
            }
        }
        return chosen.values.toList()
    }

    /**
     * Writes `<directory>/DiPlay/<fileName>` and nothing else. The private backup is only for the
     * share sheet. A failure here throws: the card must not claim the report was saved on the device.
     */
    fun save(context: Context, directory: File, fileName: String, report: String): ExportOutcome {
        val file = File(directory, "DiPlay/$fileName")
        val written = syncWriteText(file, report) || exportViaRoot(file, report, context.cacheDir)
        if (!written) throw IOException("Report storage is unavailable")
        announce(context, listOf(file.absolutePath))
        val backup = runCatching { saveInternalBackup(context, fileName, report) }.getOrNull()
        return ExportOutcome(file.absolutePath, backup?.uri)
    }

    private fun canList(dir: File): Boolean = dir.isDirectory && runCatching { dir.list() }.getOrNull() != null

    private data class Backup(val uri: Uri?, val file: File)

    /**
     * The copy the app owns. Private storage, not the external package directory: this file exists
     * only so the share sheet has something the FileProvider may sign, and private storage is the
     * one place that survives a stick being pulled out or an SD card being removed.
     */
    private fun saveInternalBackup(context: Context, fileName: String, report: String): Backup =
        saveBackupIn(context, File(context.filesDir, "diagnostic-reports"), fileName, report)

    private fun saveBackupIn(context: Context, directory: File, fileName: String, report: String): Backup {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Report storage is unavailable")
        // Each export has a new URI: an earlier share grant cannot read a later report.
        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        try {
            if (!syncWriteText(file, report)) throw IOException("Report storage is unavailable")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            // Retain only the newest eight reports; never prune the export being returned.
            directory.listFiles()?.filter { it != file && it.isFile }
                ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            return Backup(uri, file)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    /**
     * Writes the bytes and then asks the kernel to push them onto the stick's own hardware.
     *
     * A driver pulls the stick out the moment the dialog says saved. A buffered write lives in this
     * device's page cache and is lost with the removal, so the sync has to happen inside the stream's
     * lifetime — after the stream is closed there is no descriptor left to sync.
     */
    private fun syncWriteText(file: File, content: String): Boolean {
        return try {
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false
            if (parent != null && !parent.canWrite()) return false
            FileOutputStream(file).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Copies one file into place with root when the app itself cannot write the mount. */
    internal fun exportViaRoot(targetFile: File, report: String, cacheDir: File): Boolean {
        if (!com.shilapi.xcertplay.system.RootShellBridge.isAvailable()) return false
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

    /** Tells the media database about the new file, or a file manager shows nothing until the stick is replugged. */
    private fun announce(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), Array(paths.size) { "text/plain" }, null)
        }
    }
}
