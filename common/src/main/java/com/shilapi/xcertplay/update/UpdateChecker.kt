package com.shilapi.xcertplay.update

import android.annotation.SuppressLint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/** One file on a release, by the name the release gives it and the address it is fetched from. */
data class ReleaseAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)

/**
 * The one release the update link compares against.
 *
 * [versionCode] is what the comparison is made on, because it is what Android installs over and what
 * the package's own file name carries; [versionName] is what the driver is shown. [sha256Asset] is
 * null when the release has no sums file, which makes the download unverifiable -- a state the
 * downloader accepts but the page should not pretend is as good as a verified one.
 */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val apkAsset: ReleaseAsset,
    val sha256Asset: ReleaseAsset?,
)

/**
 * Asks GitHub which release is current, and decides whether it is newer than this app.
 *
 * Nothing here runs on its own: [checkLatestRelease] is called when the driver presses the button and
 * at no other time, which is the whole of the "no checking in the background" rule. The request is a
 * plain `HttpURLConnection` because that is what Android 5.1 has, and the page it draws on has 26 MB
 * of memory to spare -- a client library would be a second copy of the same request.
 *
 * GitHub answers a request with no `User-Agent` with a 403 rather than a page, so one is always sent.
 */
open class UpdateChecker(
    private val repoOwner: String = DEFAULT_REPO_OWNER,
    private val repoName: String = DEFAULT_REPO_NAME,
) {
    /** What a check came to, in the three shapes the page can show. */
    sealed class CheckResult {
        data class UpdateFound(val info: ReleaseInfo) : CheckResult()

        /** The current release is not newer. It carries the release so the page can name the version. */
        data class UpToDate(val info: ReleaseInfo) : CheckResult()

        data class Error(val message: String) : CheckResult()
    }

    /**
     * The release this app makes of one answer from GitHub, or null when it is not one to offer.
     *
     * Null is the answer for every release this app cannot install: no official package among the
     * assets, a tag that does not start with a version, or JSON that is not a release at all. A
     * release without the sums file is still returned -- what it loses is only the ability to check
     * the download, and refusing it outright would leave a car with no way to update at all.
     */
    fun parseReleaseJson(jsonString: String): ReleaseInfo? {
        val root = try {
            JSONObject(jsonString)
        } catch (e: JSONException) {
            return null
        }
        val tagName = root.optString("tag_name")
        val versionName = tagName.removePrefix("v")
        val versionCode = versionCodeOf(versionName) ?: return null

        val assets = root.optJSONArray("assets") ?: return null
        var apk: ReleaseAsset? = null
        var sums: ReleaseAsset? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val parsed = ReleaseAsset(
                name = name,
                downloadUrl = asset.optString("browser_download_url").ifEmpty { asset.optString("url") },
                sizeBytes = asset.optLong("size"),
            )
            if (parsed.downloadUrl.isEmpty()) continue
            if (sums == null && name == SUMS_ASSET_NAME) {
                sums = parsed
            } else if (apk == null && isOfficialPackage(name)) {
                apk = parsed
            }
        }
        val found = apk ?: return null
        return ReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            versionCode = versionCode,
            notes = root.optString("body"),
            apkAsset = found,
            sha256Asset = sums,
        )
    }

    /** Whether [info] is newer than the app asking. */
    fun compare(info: ReleaseInfo, currentVersionCode: Int): CheckResult =
        if (info.versionCode > currentVersionCode) CheckResult.UpdateFound(info) else CheckResult.UpToDate(info)

    /**
     * Asks GitHub for the latest release. Blocks, so it belongs on a worker thread.
     *
     * Every failure is one of the page's three kinds of bad news and none of them is allowed to
     * throw: a check that throws on the drawing thread would take the page down over a lost signal.
     * The message is reason only -- no URL, no stack trace -- because it is shown to a driver.
     */
    open fun checkLatestRelease(currentVersionCode: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): CheckResult {
        val body = try {
            fetch(releasesUrl, timeoutMs)
        } catch (e: IOException) {
            return CheckResult.Error(reasonFor(e))
        } catch (e: Exception) {
            return CheckResult.Error("unexpected")
        } ?: return CheckResult.Error("no answer")
        val info = parseReleaseJson(body) ?: return CheckResult.Error(NO_PACKAGE_FOR_THIS_CAR)
        return compare(info, currentVersionCode)
    }

    /**
     * One GET, the body as text, and null for anything but a 200.
     *
     * Android 5.1's own TLS is enough for GitHub, so the connection is not downgraded or handed to a
     * second library. The socket honours both a connect and a read timeout: a car that has driven out
     * of signal can hold a connection open for as long as the radio is still trying.
     */
    @SuppressLint("TrulyRandom") // Nothing here is encrypted; this is only a fetch of public JSON.
    internal fun fetch(url: String, timeoutMs: Int): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.instanceFollowRedirects = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** The release the update link compares against is always the latest published one. */
    val releasesUrl: String get() = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"

    private fun reasonFor(error: IOException): String = when (error) {
        is SocketTimeoutException -> "timeout"
        is UnknownHostException -> "no network"
        else -> error.javaClass.simpleName
    }

    companion object {
        const val DEFAULT_REPO_OWNER = "MarioZero99"
        const val DEFAULT_REPO_NAME = "DiPlay-Geely-E01"
        const val DEFAULT_TIMEOUT_MS = 10_000

        /** GitHub refuses a request that does not say who is making it. */
        const val USER_AGENT = "DiPlay-Geely-Updater"

        /** The sums file `scripts/publish-apk.sh` uploads beside the package. */
        const val SUMS_ASSET_NAME = "SHA256SUMS.txt"

        /** Why there is nothing to offer even though GitHub answered: the release has no package. */
        const val NO_PACKAGE_FOR_THIS_CAR = "no package for this car"

        /**
         * `0.4.4-geely` -> 44, `0.10.12-geely` -> 1012.
         *
         * The number covers the three release parts with a place each, so that the comparison is
         * between versions rather than between strings: 0.10.0 is newer than 0.9.0, which sorting the
         * text would say the other way round. A missing part counts as 0 (`0.5` is `0.5.0`). This is
         * the same number the release's file name carries, since `publish-apk.sh` writes the digits
         * without their separators.
         */
        fun versionCodeOf(versionName: String): Int? {
            val numeric = versionName.trim().takeWhile { it.isDigit() || it == '.' }.trimEnd('.')
            if (numeric.isEmpty()) return null
            val parts = numeric.split('.')
            if (parts.size > 3) return null
            val numbers = parts.map { part ->
                if (part.isEmpty()) return null
                part.toIntOrNull() ?: return null
            }
            val major = numbers.getOrElse(0) { 0 }
            val minor = numbers.getOrElse(1) { 0 }
            val patch = numbers.getOrElse(2) { 0 }
            if (patch > 99 || minor > 99) return null
            return major * 100 + minor * 10 + patch
        }

        /**
         * `DiPlay-Legacy-Geely-V044.apk`, and not a local one.
         *
         * The local package is the same app with the upload key in it; it is built for a hand to
         * install and is not for a release. `local` is matched anywhere in the name rather than only
         * as a suffix, because the name it was given once was not the only way it could be written.
         */
        fun isOfficialPackage(name: String): Boolean =
            name.startsWith("DiPlay-Legacy-Geely-") &&
                name.endsWith(".apk", ignoreCase = true) &&
                !name.lowercase(Locale.ROOT).contains("local")
    }
}
