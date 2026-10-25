package com.shilapi.xcertplay.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale

/**
 * Fetches the package and proves it is the package before letting it take its name.
 *
 * The bytes land in `<package>.download` and are hashed as they are written -- one 8 KB buffer, never
 * the whole file, because the head unit has 26 MB of memory to spare and the package is 6 MB. Only
 * when the digest matches the sums file published beside it does the fragment become
 * `DiPlay-Legacy-Geely-V<n>.apk`, in one rename. Anything else -- a mismatch, a lost connection, a
 * driver pressing cancel -- leaves no package at all, and the fragment is deleted rather than kept:
 * a download that stopped is not one to resume, it is one to make again.
 *
 * A release with no sums file has nothing to check against. [downloadAndVerify] takes it anyway and
 * says so by [DownloadResult.Success.verified], rather than refusing what would then be the car's
 * only route to an update.
 */
open class UpdateDownloader(private val timeoutMs: Int = DEFAULT_TIMEOUT_MS) {

    sealed class DownloadResult {
        /** [verified] is false only for a release that published no sums file. */
        data class Success(val file: File, val verified: Boolean) : DownloadResult()

        object Cancelled : DownloadResult()

        /**
         * [error] is the reason, never translated: a page that wants a sentence maps it to one, and
         * a page that does not shows it as it is. [kind] is the one thing a page has to act on.
         */
        data class Failure(val error: String, val kind: Kind = Kind.OTHER) : DownloadResult() {
            enum class Kind {
                /** The bytes were not the ones the sums file describes. */
                CHECKSUM,

                /** The connection failed or the server refused. */
                NETWORK,

                /** The address is not one this link fetches packages from. */
                INSECURE,

                /** The system needed the space back mid-download. */
                NO_SPACE,

                OTHER,
            }
        }
    }

    /**
     * Writes [apkUrl] to [targetFile], checking it against [expectedSha256] on the way.
     *
     * Blocks until the file is on disk or the attempt has ended, so it belongs on a worker thread.
     * [onProgress] is called from that thread as bytes arrive; [isCancelled] is asked between
     * buffer loads, so a cancel lands within one buffer of the press.
     */
    open fun downloadAndVerify(
        apkUrl: String,
        targetFile: File,
        expectedSha256: String?,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): DownloadResult {
        if (!isFetchableApkUrl(apkUrl)) return DownloadResult.Failure("not an https address", DownloadResult.Failure.Kind.INSECURE)
        targetFile.parentFile?.mkdirs()
        val partial = File(targetFile.parentFile, targetFile.name + UpdateStorageCleaner.PARTIAL_SUFFIX)
        val expected = expectedSha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        var connection: HttpURLConnection? = null
        try {
            connection = URL(apkUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", UpdateChecker.USER_AGENT)
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                return DownloadResult.Failure("http $status", DownloadResult.Failure.Kind.NETWORK)
            }
            val total = connection.getHeaderField("content-length")?.toLongOrNull()?.takeIf { it > 0 }
                ?: connection.contentLength.toLong().takeIf { it > 0 } ?: -1L
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            val buffer = ByteArray(UpdateStorageCleaner.BUFFER_BYTES)
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    while (true) {
                        if (isCancelled()) {
                            // The file is closed by this block's end and removed below; nothing
                            // half-written is ever named as a package.
                            partial.delete()
                            return DownloadResult.Cancelled
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                    output.fd.sync()
                }
            }
            if (isCancelled()) {
                partial.delete()
                return DownloadResult.Cancelled
            }
            if (expected != null && digest.digest().joinToString("") { "%02x".format(it) } != expected) {
                partial.delete()
                return DownloadResult.Failure(CHECKSUM_MISMATCH, DownloadResult.Failure.Kind.CHECKSUM)
            }
            if (!partial.renameTo(targetFile)) {
                partial.delete()
                return DownloadResult.Failure("could not store the package", DownloadResult.Failure.Kind.NO_SPACE)
            }
            return DownloadResult.Success(targetFile, verified = expected != null)
        } catch (e: IOException) {
            // A write that runs out of space arrives as an IOException too; ENOSPC is the one case
            // where what the driver has to do is different from trying the connection again.
            return DownloadResult.Failure(reasonFor(e), kindFor(e))
        } catch (e: SecurityException) {
            return DownloadResult.Failure("not allowed to store the package")
        } finally {
            connection?.disconnect()
            if (partial.exists()) partial.delete()
        }
    }

    /**
     * The digest the release's sums file gives for [apkFileName], or null when it gives none.
     *
     * The same request settings as the download, so a car that can fetch the sums file can fetch the
     * package beside it. A failure to fetch is null: the caller treats a release without a checksum
     * as unverified rather than uninstallable.
     */
    open fun fetchChecksum(sha256Url: String, apkFileName: String): String? {
        val text = try {
            val connection = URL(sha256Url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("User-Agent", UpdateChecker.USER_AGENT)
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            return null
        }
        return parseChecksumFor(text, apkFileName)
    }

    private fun reasonFor(error: IOException): String = when (error) {
        is SocketTimeoutException -> "timeout"
        is UnknownHostException -> "no network"
        else -> error.javaClass.simpleName
    }

    private fun kindFor(error: IOException): DownloadResult.Failure.Kind =
        if (error.message?.contains("ENOSPC", ignoreCase = true) == true) {
            DownloadResult.Failure.Kind.NO_SPACE
        } else {
            DownloadResult.Failure.Kind.NETWORK
        }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000
        const val CHECKSUM_MISMATCH = "checksum mismatch"

        /**
         * Whether [url] is one this link downloads a package from.
         *
         * HTTPS, and only HTTPS. The package's integrity is decided by the sums file, and the sums
         * file is fetched over the same kind of connection: over plain HTTP both can be replaced by
         * whoever is on the network, and the comparison would pass against bytes they wrote. This
         * app's manifest allows cleartext for its own local traffic, so the rule has to be here.
         *
         * Loopback is the one plain address allowed, and only for tests: a package served by the
         * test that is checking the download is the test's own bytes.
         */
        fun isFetchableApkUrl(url: String): Boolean {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            if (scheme == "https") return true
            if (scheme != "http") return false
            val host = uri.host ?: return false
            return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
        }

        /**
         * The digest a `shasum -a 256` / `sha256sum` listing gives for [apkFileName].
         *
         * A listing is `<digest><space><name>`, with one space from `sha256sum` and two from
         * `shasum`, and an asterisk before the name for binary mode. Every one of those is read, and
         * anything that is not 64 hex digits followed by the name asked for is skipped.
         */
        fun parseChecksumFor(sumsText: String, apkFileName: String): String? {
            for (line in sumsText.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.length < 65) continue
                val digest = trimmed.substring(0, 64)
                if (!digest.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) continue
                var rest = trimmed.substring(64).trimStart()
                if (rest.startsWith("*")) rest = rest.substring(1)
                if (rest == apkFileName) return digest.lowercase(Locale.ROOT)
            }
            return null
        }
    }
}
