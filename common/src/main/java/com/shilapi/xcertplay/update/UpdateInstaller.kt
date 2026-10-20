package com.shilapi.xcertplay.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded package to the system's own installer.
 *
 * The app does not install anything by itself. It asks the package installer to open the file, the
 * installer draws its confirmation, and the driver presses it. On a head unit that is the only safe
 * arrangement -- an install that goes wrong there leaves a car with an app that cannot start and no
 * way to remove it from the screen it is stuck on.
 *
 * The URI is a `content://` one from the FileProvider the app already carries. A `file://` URI in an
 * intent is refused from API 24 up, and this app targets 37 on a 22 head unit, so the refusal would
 * come from the platform's `targetSdk` check rather than from the car's age. The read grant is a
 * flag on the intent, so it lasts as long as the intent does.
 *
 * `ACTION_INSTALL_PACKAGE` rather than a plain view of the archive. A file manager on this very car
 * that does open the installer (`com.cxinventor.file.explorer`, found by its dex strings) sends
 * exactly this action with this MIME type, and this is the action the installer's own activity
 * declares an intent filter for. A `view` of a package-archive URI reaches it only through the
 * platform's fallback for unhandled types, which is a path the car's ROM did not have to keep --
 * `ACTION_INSTALL_PACKAGE` is what the head unit's own file browsers have been shown to get through.
 * It is deprecated from API 29 and this app targets 37, so the only place the deprecation can bite
 * is a device running Android 10 or later -- and never this one.
 */
object UpdateInstaller {

    const val PACKAGE_MIME_TYPE = "application/vnd.android.package-archive"

    /** The authority the app's report provider is declared under, which also carries the updates. */
    fun authority(context: Context): String = "${context.packageName}.diagnostic-reports"

    /**
     * The intent that opens [apkFile] in the system installer.
     *
     * The three extras are the ones the platform's own documentation attaches to this action: the
     * package is not from an unknown source (`EXTRA_NOT_UNKNOWN_SOURCE`, API 17+), it replaces the
     * copy already installed (`EXTRA_ALLOW_REPLACE`, API 19+), and the installer may name this app as
     * the one that started it. None is required for the installer to open, and all of them are what
     * tells it the request came from an app that said it could install rather than from a stray
     * intent.
     */
    @Suppress("DEPRECATION") // API 29: the action stays for every head unit this app is built for.
    fun createInstallIntent(context: Context, apkFile: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, authority(context), apkFile)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, PACKAGE_MIME_TYPE)
            // Not promised the caller is an activity: the controller runs this from a worker.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_ALLOW_REPLACE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
        }
    }

    /** Opens the system installer on [apkFile]. A car with nothing to open it says so in the Result. */
    fun install(context: Context, apkFile: File): Result<Unit> = runCatching {
        require(apkFile.isFile) { "the package is not on disk" }
        context.startActivity(createInstallIntent(context, apkFile))
    }

    /**
     * The package that installed [packageName], or null when nothing recorded doing so.
     *
     * Null is the answer for a package pushed by hand -- over ADB, or copied in by a file manager on
     * the car -- and it is also the answer when the package manager cannot say. Both mean the same
     * thing to [shouldStandAside]: nothing else owns this app's updates.
     */
    fun installingPackageOnBehalfOf(context: Context, packageName: String): String? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(packageName)
    } catch (e: RuntimeException) {
        null
    }

    /**
     * Whether updates for this app belong to another installer and this link must not offer them.
     *
     * A store-owned app is updated by the store. A GitHub package put over it is not the store's
     * copy, and the store's next update would put its own build back -- possibly an older one than
     * the driver just installed. Installed by hand, there is nothing to leave it to, and the link
     * offers.
     */
    fun shouldStandAside(context: Context, packageName: String): Boolean =
        installingPackageOnBehalfOf(context, packageName) != null

    /**
     * The installers that do not own an app's updates.
     *
     * Kept for the page's wording: "hand-installed" is an installer of null -- ADB or a copy on the
     * car leaves no record -- while these are the system's own UI for the same act, and a ROM that
     * preinstalls a package records `com.android.settings`.
     */
    internal fun isKnownInstallPath(installer: String?): Boolean =
        installer == null ||
            installer == "com.android.packageinstaller" ||
            installer == "com.google.android.packageinstaller" ||
            installer == "com.android.shell"
}
