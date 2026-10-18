package com.shilapi.xcertplay

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Where the report ended up, named for what it is rather than for how it was written: a stick's root
 * is the one place a driver finds without instructions, the package directory is the one place the
 * platform lets an app write on a second volume, and the two device columns are what is left when no
 * stick is writable.
 */
internal enum class ExportDestination {
    USB_ROOT,
    USB_PACKAGE_DIR,
    DEVICE_DOWNLOADS,
    DEVICE_PRIVATE,
}

/**
 * @property shareableUri signed by this app's FileProvider for the managed internal copy, or null
 * when even that copy could not be made. A stick's own file is never handed to the provider: no path
 * in its config maps a second volume, so asking would throw exactly where the share sheet should open.
 */
internal data class ExportOutcome(
    val destination: ExportDestination,
    val primaryPath: String,
    val shareableUri: Uri?,
    val probeSummary: String,
    val allSavedPaths: List<String>,
)

/** Saves an app-owned report without depending on an OEM's document-picker activity. */
internal object DiagnosticExportStore {
    data class SavedReport(
        val uri: Uri,
        val savedInApp: Boolean = false,
        val savedPath: String? = null,
    )

    /**
     * One exit for a diagnostic report: the managed copy the app can always share, then the stick's
     * package directory, then the stick's root, then the device's own Downloads.
     *
     * The stick order is the platform's order, not a preference — `<vol>/Android/data/<pkg>/files` is
     * the directory the API guarantees, `<vol>/DiPlay` is the one a driver can find — so b comes before
     * a, and the outcome names whichever actually took the bytes.
     */
    fun export(
        context: Context,
        fileName: String,
        report: String,
        extraMountPaths: Collection<String> = emptySet(),
    ): ExportOutcome {
        val probe = DiagnosticVolumeProbe.probe(context, extraMountPaths)
        val savedPaths = mutableListOf<String>()

        // First and unconditionally: the only file the FileProvider is allowed to sign, so the share
        // sheet has something to hand over whatever the stick turns out to be.
        val backup = runCatching { saveInternalBackup(context, fileName, report) }.getOrNull()
        backup?.let { savedPaths += it.file.absolutePath }

        var usbPackagePath: String? = null
        var usbRootPath: String? = null
        val scanned = mutableListOf<String>()

        // b. The contract-guaranteed directory on the stick.
        for (volume in probe.volumes) {
            if (!volume.packageWritable) continue
            val directory = volume.packageDir?.let { File(it, "DiPlay") } ?: continue
            val file = File(directory, fileName)
            if (!syncWriteText(file, report)) continue
            if (usbPackagePath == null) usbPackagePath = file.absolutePath
            savedPaths += file.absolutePath
            scanned += file.absolutePath
        }

        // a. The stick's root, where a driver finds it without being told the package path.
        for (volume in probe.volumes) {
            if (!volume.rootWritable) continue
            val root = volume.rootDir ?: continue
            val file = File(File(root, "DiPlay"), fileName)
            if (!syncWriteText(file, report)) continue
            if (usbRootPath == null) usbRootPath = file.absolutePath
            savedPaths += file.absolutePath
            scanned += file.absolutePath
        }

        // c. Nothing on the stick took it, so the device's own Downloads is the fallback — not an
        // extra copy. A second copy the dialog does not mention would make "where is my report?" a
        // question with two answers.
        val downloadPath = if (usbPackagePath == null && usbRootPath == null) {
            saveToDeviceDownloads(context, fileName, report)?.also { scanned += it }
        } else null
        downloadPath?.let { savedPaths += it }

        announce(context, scanned)

        val destination: ExportDestination
        val primaryPath: String
        when {
            usbRootPath != null -> { destination = ExportDestination.USB_ROOT; primaryPath = usbRootPath }
            usbPackagePath != null -> { destination = ExportDestination.USB_PACKAGE_DIR; primaryPath = usbPackagePath }
            downloadPath != null -> { destination = ExportDestination.DEVICE_DOWNLOADS; primaryPath = downloadPath }
            backup != null -> { destination = ExportDestination.DEVICE_PRIVATE; primaryPath = backup.file.absolutePath }
            else -> throw IOException("Report storage is unavailable")
        }

        return ExportOutcome(
            destination = destination,
            primaryPath = primaryPath,
            shareableUri = backup?.uri,
            probeSummary = probe.summaryLine,
            allSavedPaths = savedPaths,
        )
    }

    /** Android 9 and OEMs without working Downloads storage can still export privately. */
    fun saveWithoutPicker(context: Context, fileName: String, report: String): SavedReport {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return SavedReport(saveToDownloads(context.contentResolver, fileName, report))
            } catch (_: Exception) {
                // Preserve the report even when the OEM's public storage provider is absent.
            }
        }
        val saved = try {
            // Use Android's package-specific directory, including debug application IDs.
            // No storage permission or document-picker activity is needed.
            val externalFiles = context.getExternalFilesDir(null)
            if (externalFiles != null) {
                saveInDirectory(context, File(externalFiles, "diagnostic-reports"), fileName, report)
            } else {
                saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
            }
        } catch (_: Exception) {
            // A missing, read-only or full external volume must not prevent export.
            saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
        }
        exportCopiesToAccessibleStorage(context, fileName, report)
        return saved
    }

    /** Best-effort copies so a driver with no picker can pull the report off a stick or card. */
    private fun exportCopiesToAccessibleStorage(context: Context, fileName: String, report: String) {
        runCatching {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            writeReportTo(File(downloads, "DiPlay"), fileName, report)
        }
        runCatching {
            writeReportTo(File(Environment.getExternalStorageDirectory(), "DiPlay"), fileName, report)
        }
        runCatching {
            val volumes = context.getExternalFilesDirs(null) ?: return@runCatching
            for (index in 1 until volumes.size) {
                val volume = volumes[index] ?: continue
                writeReportTo(File(volume, "diagnostic-reports"), fileName, report)
            }
        }
        runCatching {
            usbVolumeDirectories().forEach { writeReportTo(File(it, "DiPlay"), fileName, report) }
        }
    }

    // A stick is mounted as a child of these roots, not at the root itself: E01 puts it at
    // /storage/usbotg/usbotg-sda1, with the raw twin under /mnt/media_rw.
    private val usbMountRoots = listOf(
        "/storage/usbotg",
        "/storage/udisk",
        "/mnt/usb_storage",
        "/mnt/udisk",
        "/mnt/usb",
        "/mnt/media_rw",
    )

    private fun usbVolumeDirectories(): List<File> = usbMountRoots.flatMap { path ->
        val root = File(path)
        if (!root.isDirectory) emptyList()
        else listOf(root) + root.listFiles().orEmpty().filter { it.isDirectory }
    }

    private fun writeReportTo(directory: File, fileName: String, report: String) {
        syncWriteText(File(directory, fileName), report)
    }

    private data class Backup(val uri: Uri?, val file: File)

    /**
     * The copy the app owns: written even when the stick takes the report, because it is what the
     * share sheet can be handed without the provider ever seeing a second volume. When the external
     * package directory is gone or unwritable this falls back to the app's own files directory.
     */
    private fun saveInternalBackup(context: Context, fileName: String, report: String): Backup {
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        if (external != null) {
            runCatching {
                return saveBackupIn(context, File(external, "diagnostic-reports"), fileName, report)
            }
        }
        return saveBackupIn(context, File(context.filesDir, "diagnostic-reports"), fileName, report)
    }

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

    private fun saveInDirectory(
        context: Context,
        directory: File,
        fileName: String,
        report: String,
        savedInApp: Boolean = false,
    ): SavedReport {
        val backup = saveBackupIn(context, directory, fileName, report)
        return SavedReport(backup.uri!!, savedInApp = savedInApp, savedPath = if (savedInApp) null else backup.file.absolutePath)
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
            FileOutputStream(file).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** The device's own public Downloads, which is only ever the fallback for a stick that took nothing. */
    private fun saveToDeviceDownloads(context: Context, fileName: String, report: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                saveToDownloads(context.contentResolver, fileName, report)
                "Download/DiPlay/$fileName"
            }.getOrNull()
        }
        return runCatching {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(File(downloads, "DiPlay"), fileName)
            if (syncWriteText(file, report)) file.absolutePath else null
        }.getOrNull()
    }

    /**
     * Tells the media database where the new files are. Without this the stick's own file manager
     * shows nothing until it is unplugged and plugged back in.
     */
    private fun announce(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), Array(paths.size) { "text/plain" }, null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(resolver: ContentResolver, fileName: String, report: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DiPlay")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the report")
        try {
            write(resolver, uri, report)
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the report")
            return uri
        } catch (error: Exception) {
            // Only remove the entry created by this call; never leave a partial report behind.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, report: String) {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Report destination is unavailable")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
    }
}
