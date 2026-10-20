package com.shilapi.xcertplay.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * The one folder an update is ever downloaded into, and the rules for what may stay in it.
 *
 * There is one package at a time: the newest one a release offers. Anything in here that is not that
 * package is storage the car has already given up -- a `.download` fragment left by an ignition cut
 * mid-download, or the package for a version that has since been installed. [cleanOldUpdates] takes
 * both on the first run, so a new download never lands on top of what the last one left.
 *
 * The folder is the app's external cache when there is one and the internal cache when there is not.
 * "Cache" is deliberate: the system may reclaim it under storage pressure, and the update link can
 * rebuild it by downloading again, so nothing here is worth asking the driver to manage.
 */
class UpdateStorageCleaner(private val context: Context) {

    /**
     * Where downloads go. Both the downloader and the install step resolve it through here.
     *
     * Canonical, because the root the app is given and the root the platform later reports for the
     * same folder are not always spelled the same way -- `/var/...` and `/private/var/...` on a
     * device whose data lives behind a link. A path compared by its text would call those two
     * folders different, and the download would land where the cleaner does not look.
     */
    val updateDir: File
        get() {
            val base = context.externalCacheDir ?: context.cacheDir
            return File(base, UPDATE_DIR_NAME).canonicalFile
        }

    /**
     * This app's own version code.
     *
     * Read from the package manager rather than from `BuildConfig`: the code lives in a library that
     * two applications share, and the version is a fact about the installed package, not about the
     * library it was built from.
     */
    fun getInstalledVersionCode(): Int {
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return code.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Whether there is room for another download on top of whatever survives a clean.
     *
     * The threshold is the update's own size with room to spare, because the downloader writes the
     * package and then compares it, and the cache the system keeps for itself lives in the same
     * partition.
     */
    fun hasAdequateFreeSpace(requiredBytes: Long = DEFAULT_REQUIRED_BYTES): Boolean {
        // On the first launch the folder is not there yet and StatFs answers 0 for a path that does
        // not exist, so it is made before it is asked about -- and a folder that cannot be made is
        // itself the answer.
        val dir = updateDir
        if (!dir.isDirectory && !dir.mkdirs()) return false
        return try {
            StatFs(dir.path).availableBytes >= requiredBytes
        } catch (e: IllegalArgumentException) {
            // The platform refuses a path it cannot stat. Treating that as "no room" is the safe
            // answer: a download that cannot be stored is not one to start.
            false
        }
    }

    /**
     * Removes the fragments and the packages nothing will install, and says how many files went.
     *
     * An `.apk` stays only when the package manager can read it and its version is newer than
     * [currentVersionCode]. [PackageManager.getPackageArchiveInfo] is the only answer that is about
     * the file itself rather than about its name.
     */
    fun cleanOldUpdates(currentVersionCode: Int = getInstalledVersionCode()): Int {
        val dir = updateDir
        if (!dir.isDirectory) return 0
        var removed = 0
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val keep = file.name.endsWith(APK_SUFFIX) && versionCodeOf(file)?.let { it > currentVersionCode } == true
            if (!keep && file.delete()) removed++
        }
        return removed
    }

    /**
     * The already-downloaded package for [targetVersionCode], when it is whole.
     *
     * This is what makes a second launch onto the same release cost nothing: the package is already
     * on disk, and the only question left is whether it is the one the release is offering. Without a
     * checksum the file has to answer by its name and its size alone, so a caller that has a checksum
     * should always pass it.
     */
    fun findReadyApk(targetVersionCode: Int, expectedSha256: String?): File? {
        val file = File(updateDir, apkFileName(targetVersionCode))
        if (!file.isFile || file.length() == 0L) return null
        val expected = expectedSha256?.trim()?.lowercase()
        if (expected != null && sha256Of(file) != expected) return null
        return file
    }

    /** The name every download of [versionCode] is written under. */
    fun apkFileName(versionCode: Int): String = "$APK_PREFIX$versionCode$APK_SUFFIX"

    /** What the package manager says the file is, or null when it cannot read it. */
    private fun versionCodeOf(file: File): Int? {
        val info = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        } catch (e: RuntimeException) {
            // A file that only looks like an archive can fail here rather than answer null.
            null
        } ?: return null
        @Suppress("DEPRECATION")
        val code: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return code.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun sha256Of(file: File): String? = try {
        MessageDigest.getInstance("SHA-256").let { digest ->
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        /** The cache sub-folder. Named once, here, because three other classes look it up. */
        const val UPDATE_DIR_NAME = "updates"

        /** Releases are named `DiPlay-Legacy-Geely-V<versionCode>.apk`; see `scripts/publish-apk.sh`. */
        const val APK_PREFIX = "DiPlay-Legacy-Geely-V"
        const val APK_SUFFIX = ".apk"

        /** The suffix a download is written under until it has been checked. */
        const val PARTIAL_SUFFIX = ".download"

        /** Fits an update with room to spare; the car has about 2.8 GB, so this never blocks it. */
        const val DEFAULT_REQUIRED_BYTES = 25L * 1024 * 1024

        /** The 8 KB the rest of the app reads files in; a download is never held in memory. */
        const val BUFFER_BYTES = 8 * 1024
    }
}
