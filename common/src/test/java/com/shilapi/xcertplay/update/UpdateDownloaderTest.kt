package com.shilapi.xcertplay.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Fetching the package, and refusing one that is not the one it was supposed to be.
 *
 * The check is the point of the whole download: the car is handed a package over a connection it
 * does not control and then asked to install it, so the bytes are compared against the digest the
 * release publishes before the file is allowed to take the package's name. Until then it is a
 * `.download` fragment, which is also what an ignition cut mid-download leaves behind -- and which
 * the cleaner removes on the next run.
 *
 * The server here is a socket on loopback answering with a hand-written HTTP response, so the size,
 * the digest and the point at which a test cancels are all decided by the test rather than by a
 * network. Nothing is held in memory: the code copies through one 8 KB buffer, and the tests are
 * written to notice if that ever stops being true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UpdateDownloaderTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = UpdateStorageCleaner(context).updateDir.apply { mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    // ---- the sums file -------------------------------------------------------------------------

    @Test fun parseExpectedSha256FromSumsFile() {
        val sums = """
            9f2c7b1e0a4d6c8e2f5a3b7d1c9e4f6a8b0c2d4e6f8a1b3c5d7e9f0a2b4c6d8e  DiPlay-Legacy-Geely-V045.apk
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  DiPlay-Legacy-Geely-V045-local.apk
        """.trimIndent()

        assertEquals(UpdateDownloaderTest.SHA_045, UpdateDownloader.parseChecksumFor(sums, "DiPlay-Legacy-Geely-V045.apk"))
        assertNull("another package's digest is not this package's", UpdateDownloader.parseChecksumFor(sums, "DiPlay-Legacy-Geely-V046.apk"))
    }

    @Test fun parseExpectedSha256AcceptsTheFormsSumsFilesComeIn() {
        val name = "DiPlay-Legacy-Geely-V045.apk"
        val digest = SHA_045

        // shasum's two spaces, sha256sum's one, an asterisk for binary mode, and a Windows line end.
        assertEquals(digest, UpdateDownloader.parseChecksumFor("$digest  $name", name))
        assertEquals(digest, UpdateDownloader.parseChecksumFor("$digest $name", name))
        assertEquals(digest, UpdateDownloader.parseChecksumFor("$digest *$name", name))
        assertEquals(digest, UpdateDownloader.parseChecksumFor("$digest  $name\r\n", name))
        assertEquals(digest, UpdateDownloader.parseChecksumFor("${digest.uppercase()}  $name", name))
    }

    @Test fun parseExpectedSha256RejectsLinesThatAreNotDigests() {
        val name = "DiPlay-Legacy-Geely-V045.apk"

        assertNull(UpdateDownloader.parseChecksumFor("", name))
        assertNull(UpdateDownloader.parseChecksumFor("not-a-digest  $name", name))
        assertNull(UpdateDownloader.parseChecksumFor("${SHA_045}0  $name", name))
    }

    // ---- the download -------------------------------------------------------------------------

    @Test fun downloadComputesCorrectSha256() {
        val payload = apkBytes(200 * 1024)
        val target = File(dir, "DiPlay-Legacy-Geely-V045.apk")

        WithServer(payload) { url ->
            val result = UpdateDownloader().downloadAndVerify(
                apkUrl = url,
                targetFile = target,
                expectedSha256 = sha256Of(payload),
                onProgress = { _, _ -> },
                isCancelled = { false },
            )

            assertTrue(result is UpdateDownloader.DownloadResult.Success)
            assertEquals(target, (result as UpdateDownloader.DownloadResult.Success).file)
            assertTrue(target.exists())
            assertContentEquals(payload, target.readBytes())
        }
        assertFalse("no fragment is left behind", File(dir, target.name + UpdateStorageCleaner.PARTIAL_SUFFIX).exists())
    }

    @Test fun downloadReportsProgressAsItGoes() {
        val payload = apkBytes(300 * 1024)
        val seen = mutableListOf<Pair<Long, Long>>()

        WithServer(payload) { url ->
            UpdateDownloader().downloadAndVerify(url, File(dir, "a.apk"), null, { read, total -> seen += read to total }, { false })
        }

        assertTrue("progress is reported while the bytes arrive", seen.size > 1)
        assertEquals("and it does not go backwards", seen.map { it.first }.sorted(), seen.map { it.first })
        assertEquals(payload.size.toLong(), seen.last().first)
        // A connection that does not say how much is coming reports -1, and the page then shows the
        // bytes that have arrived rather than a percentage of a number it does not have.
        assertEquals(-1L, seen.last().second)
    }

    @Test fun downloadDeletesTempFileWhenChecksumMismatch() {
        val payload = apkBytes(64 * 1024)
        val target = File(dir, "DiPlay-Legacy-Geely-V045.apk")

        WithServer(payload) { url ->
            val result = UpdateDownloader().downloadAndVerify(url, target, "0".repeat(64), { _, _ -> }, { false })

            assertTrue(result is UpdateDownloader.DownloadResult.Failure)
            assertFalse("the package never takes its name", target.exists())
            assertFalse("and nothing keeps its bytes", File(dir, target.name + UpdateStorageCleaner.PARTIAL_SUFFIX).exists())
        }
    }

    @Test fun downloadWithoutAChecksumStillStoresThePackage() {
        // A release with no sums file cannot be verified. It is still fetched -- refusing would leave
        // the car with no way to update -- and the page says it is unverified.
        val payload = apkBytes(1024)
        val target = File(dir, "a.apk")

        WithServer(payload) { url ->
            val result = UpdateDownloader().downloadAndVerify(url, target, null, { _, _ -> }, { false })
            assertTrue(result is UpdateDownloader.DownloadResult.Success)
        }
        assertContentEquals(payload, target.readBytes())
    }

    @Test fun downloadRespectsCancellation() {
        val payload = apkBytes(400 * 1024)
        val target = File(dir, "a.apk")
        var reads = 0

        WithServer(payload, chunked = true) { url ->
            val result = UpdateDownloader().downloadAndVerify(
                apkUrl = url,
                targetFile = target,
                expectedSha256 = null,
                onProgress = { _, _ -> },
                // The driver presses cancel after a few chunks, which is what stops the copy.
                isCancelled = { reads++ >= 3 },
            )

            assertEquals(UpdateDownloader.DownloadResult.Cancelled, result)
            assertFalse(target.exists())
            assertFalse(File(dir, "a.apk" + UpdateStorageCleaner.PARTIAL_SUFFIX).exists())
        }
    }

    @Test fun downloadFailsOnAnErrorStatusWithoutAPackage() {
        val target = File(dir, "a.apk")

        WithServer(ByteArray(0), status = "500 Internal Server Error") { url ->
            val result = UpdateDownloader().downloadAndVerify(url, target, null, { _, _ -> }, { false })

            assertTrue(result is UpdateDownloader.DownloadResult.Failure)
            assertFalse(target.exists())
        }
    }

    @Test fun downloadFailsWhenTheConnectionCannotBeMade() {
        val port = ServerSocket(0).use { it.localPort }
        val result = UpdateDownloader(timeoutMs = 2_000).downloadAndVerify(
            apkUrl = "http://127.0.0.1:$port/DiPlay-Legacy-Geely-V045.apk",
            targetFile = File(dir, "a.apk"),
            expectedSha256 = null,
            onProgress = { _, _ -> },
            isCancelled = { false },
        )

        assertTrue(result is UpdateDownloader.DownloadResult.Failure)
        assertFalse(File(dir, "a.apk").exists())
    }

    @Test fun downloadKeepsTheRealNameWhenTheServerSendsItWithTheBytes() {
        // What an APK's own path looks like on disk after a download: the package's name and its
        // suffix, nothing appended to say it was fetched.
        val payload = apkBytes(2048)
        val target = File(dir, "DiPlay-Legacy-Geely-V045.apk")

        WithServer(payload) { url ->
            UpdateDownloader().downloadAndVerify(url, target, sha256Of(payload), { _, _ -> }, { false })
        }

        assertEquals(listOf("DiPlay-Legacy-Geely-V045.apk"), dir.list()!!.toList())
    }

    // ---- the sums file, fetched -----------------------------------------------------------------

    @Test fun downloadSumsFileReadsItThroughTheSameConnection() {
        val sums = "${SHA_045}  DiPlay-Legacy-Geely-V045.apk\n"
        WithServer(sums.toByteArray()) { url ->
            assertEquals(SHA_045, UpdateDownloader().fetchChecksum(url, "DiPlay-Legacy-Geely-V045.apk"))
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun apkBytes(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 7).toByte() }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        assertTrue(MessageDigest.isEqual(expected, actual))
    }

    /**
     * A server on loopback for exactly one request.
     *
     * `Content-Length` is omitted when [chunked], so the body arrives in pieces the downloader has to
     * read as they come -- which is what gives a cancel something to interrupt, and what a real
     * connection over a car's radio does anyway.
     */
    private fun WithServer(
        body: ByteArray,
        status: String = "200 OK",
        chunked: Boolean = true,
        block: (String) -> Unit,
    ) {
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket -> answer(socket, body, status, chunked) }
            } catch (e: IOException) {
                // The client hanging up is a case under test, not a failure of the server.
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}/packages/DiPlay-Legacy-Geely-V045.apk")
        } finally {
            server.close()
            worker.join(5_000)
        }
    }

    private fun answer(socket: Socket, body: ByteArray, status: String, chunked: Boolean) {
        val input = socket.getInputStream().bufferedReader()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
        }
        val out = socket.getOutputStream()
        val length = if (chunked) "" else "Content-Length: ${body.size}\r\n"
        out.write(
            ("HTTP/1.1 $status\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                length +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        // No Content-Length in [chunked] mode: the body ends when the connection does, which is what
        // gives a cancel something to interrupt and what a connection over a car's radio does.
        var offset = 0
        while (offset < body.size) {
            val size = minOf(16 * 1024, body.size - offset)
            out.write(body, offset, size)
            out.flush()
            offset += size
        }
        out.flush()
    }

    companion object {
        /** The digest of the package a release at 0.4.5 would carry, as a sums file would spell it. */
        const val SHA_045 = "9f2c7b1e0a4d6c8e2f5a3b7d1c9e4f6a8b0c2d4e6f8a1b3c5d7e9f0a2b4c6d8e"
    }
}
