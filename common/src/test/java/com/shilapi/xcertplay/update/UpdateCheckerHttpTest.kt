package com.shilapi.xcertplay.update

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The request itself: what [UpdateChecker.checkLatestRelease] and [UpdateDownloader.fetchChecksum]
 * make of an answer that is not a release.
 *
 * These were the half of the link no test touched, because the parser was testable on its own and the
 * request was assumed. What the request has to get right is the way it fails: a car that has driven
 * out of signal, a GitHub that answers 403 to a client with no name, and a page that answered 200
 * with something that is not a release at all. Every one of them is a sentence on the page, none of
 * them is an exception, and none of them is a URL shown to a driver.
 *
 * The server is a socket on loopback that answers once and closes, so a failure is decided by the
 * test rather than by a network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UpdateCheckerHttpTest {
    private val checker = UpdateChecker()

    @Test fun aReleaseIsReadThroughTheRequest() {
        val body = """{"tag_name":"v0.4.5-geely","body":"notes","assets":[
            {"name":"DiPlay-Legacy-Geely-V45.apk","browser_download_url":"https://example.invalid/V45.apk","size":6311524},
            {"name":"SHA256SUMS.txt","browser_download_url":"https://example.invalid/SHA256SUMS.txt","size":100}]}"""

        WithServer(body) { url ->
            val result = checker.fetch(url, timeoutMs = 5_000)?.let { checker.parseReleaseJson(it) }?.let { checker.compare(it, 44) }

            assertTrue("the request found a newer release", result is UpdateChecker.CheckResult.UpdateFound)
        }
    }

    @Test fun theRequestNamesItselfBecauseGitHubRefusesThoseThatDoNot() {
        val seen = AtomicReference<String>()
        WithServer("{}", onRequest = { request -> seen.set(request) }) { url ->
            checker.fetch(url, timeoutMs = 5_000)
        }

        assertTrue("a User-Agent is sent", seen.get().contains("User-Agent: ${UpdateChecker.USER_AGENT}"))
    }

    @Test fun forbiddenIsNoAnswer() {
        // What GitHub answers a client with no User-Agent, and a rate limit, and a private release.
        WithServer("{" + "\"message\":\"API rate limit exceeded\"}", status = "403 Forbidden") { url ->
            assertEquals("a status that is not 200 is null", null, checker.fetch(url, timeoutMs = 5_000))
        }
    }

    @Test fun a200ThatIsNotAReleaseIsSaidSo() {
        // A captive portal, a proxy's own page, or a GitHub that changed its answer: all arrive as
        // 200 with a body that is not a release. The page is told there is no package, not that it
        // has no network -- they lead to different things for the driver to do.
        WithServer("<html>not a release</html>") { url ->
            assertEquals("the body is read whatever it is", "<html>not a release</html>", checker.fetch(url, timeoutMs = 5_000))
            assertEquals(null, checker.parseReleaseJson("<html>not a release</html>"))
        }
    }

    @Test fun aConnectionThatCannotBeMadeIsAnErrorAndNotAnException() {
        // Nothing is listening on the port: the check answers with an error the page can show.
        val port = ServerSocket(0).use { it.localPort }
        // The same request the check makes, against an address nothing answers on.
        val result = runCatching { checker.fetch("http://127.0.0.1:$port/releases/latest", timeoutMs = 1_000) }
        assertTrue("a refused connection surfaces as a thrown IOException from fetch", result.exceptionOrNull() is IOException)
    }

    @Test fun aForbiddenAnswerIsGitHubRefusingRatherThanTheCarHavingNoNetwork() {
        // Rate limits and a request with no name both arrive as 403, and the next move for the driver
        // is to wait rather than to look at the car's connection.
        assertTrue(UpdateChecker.isRefusal(403))
        assertTrue(UpdateChecker.isRefusal(404))
        assertTrue(!UpdateChecker.isRefusal(200))
        assertTrue(!UpdateChecker.isRefusal(500))
        assertTrue("a connection that was never made is not a refusal", !UpdateChecker.isRefusal(-1))
    }

    @Test fun aDownloadFromAPlainHttpAddressIsRefusedBeforeAnythingIsWritten() {
        // The environment allows cleartext. Over HTTP the package and the sums file that describes
        // it can both be replaced by whoever is on the network, and the comparison would pass.
        val target = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "updates/insecure.apk")
        val result = UpdateDownloader().downloadAndVerify(
            apkUrl = "http://mirror.example.invalid/DiPlay-Legacy-Geely-V45.apk",
            targetFile = target,
            expectedSha256 = null,
            onProgress = { _, _ -> },
            isCancelled = { false },
        )

        assertTrue(result is UpdateDownloader.DownloadResult.Failure)
        assertEquals(
            UpdateDownloader.DownloadResult.Failure.Kind.INSECURE,
            (result as UpdateDownloader.DownloadResult.Failure).kind,
        )
        assertTrue("nothing is written for an address it will not use", !target.exists())
        target.delete()
    }

    @Test fun whichAddressesAPackageIsFetchedFrom() {
        assertTrue(UpdateDownloader.isFetchableApkUrl("https://github.com/o/r/releases/download/v1/x.apk"))
        assertTrue("the tests' own server", UpdateDownloader.isFetchableApkUrl("http://127.0.0.1:8080/x.apk"))
        assertTrue(UpdateDownloader.isFetchableApkUrl("http://localhost:8080/x.apk"))
        assertTrue(!UpdateDownloader.isFetchableApkUrl("http://github.com/o/r/x.apk"))
        assertTrue(!UpdateDownloader.isFetchableApkUrl("file:///sdcard/x.apk"))
        assertTrue(!UpdateDownloader.isFetchableApkUrl("not a url"))
    }

    @Test fun aServerThatRefusesThePackageIsANetworkFailure() {
        WithServer("", status = "403 Forbidden") { url ->
            val result = UpdateDownloader().downloadAndVerify(
                apkUrl = url,
                targetFile = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "updates/refused.apk"),
                expectedSha256 = null,
                onProgress = { _, _ -> },
                isCancelled = { false },
            )
            assertTrue(result is UpdateDownloader.DownloadResult.Failure)
            assertEquals(
                UpdateDownloader.DownloadResult.Failure.Kind.NETWORK,
                (result as UpdateDownloader.DownloadResult.Failure).kind,
            )
        }
    }

    @Test fun aChecksumFileThatAnswersAnErrorIsNoChecksum() {
        WithServer("nope", status = "500 Internal Server Error") { url ->
            assertEquals(null, UpdateDownloader().fetchChecksum(url, "DiPlay-Legacy-Geely-V45.apk"))
        }
    }

    @Test fun aChecksumFileIsReadThroughTheRequest() {
        val line = "${"a".repeat(64)}  DiPlay-Legacy-Geely-V45.apk\n"
        WithServer(line) { url ->
            assertEquals("a".repeat(64), UpdateDownloader().fetchChecksum(url, "DiPlay-Legacy-Geely-V45.apk"))
        }
    }

    private fun WithServer(
        body: String,
        status: String = "200 OK",
        onRequest: (String) -> Unit = {},
        block: (String) -> Unit,
    ) {
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket -> answer(socket, body.toByteArray(), status, onRequest) }
            } catch (e: IOException) {
                // The client hanging up is not this server's problem.
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}/latest")
        } finally {
            server.close()
            worker.join(5_000)
        }
    }

    private fun answer(socket: Socket, body: ByteArray, status: String, onRequest: (String) -> Unit) {
        val input = socket.getInputStream().bufferedReader()
        val request = StringBuilder()
        while (true) {
            val line = input.readLine() ?: break
            request.append(line).append("\r\n")
            if (line.isEmpty()) break
        }
        onRequest(request.toString())
        val out = socket.getOutputStream()
        out.write(("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }
}
