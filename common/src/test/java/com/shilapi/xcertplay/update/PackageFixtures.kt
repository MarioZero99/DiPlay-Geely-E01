package com.shilapi.xcertplay.update

import android.content.Context
import android.content.pm.PackageInfo
import java.io.File
import org.robolectric.Shadows.shadowOf

/**
 * A package-shaped file the stand-in package manager can read as version [versionCode].
 *
 * The bytes are not an installable APK and nothing here installs anything. What they are is a file
 * whose manifest the package manager is willing to answer for -- which is what [UpdateStorageCleaner]
 * and the controller ask of every package on disk, and what a test with a real release package would
 * get back without needing a signed APK in the tree.
 */
internal fun readablePackage(context: Context, file: File, versionCode: Int): File {
    file.parentFile?.mkdirs()
    file.writeBytes(ByteArray(256) { it.toByte() })
    @Suppress("DEPRECATION")
    shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, PackageInfo().apply { this.versionCode = versionCode })
    return file
}
