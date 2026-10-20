package com.shilapi.xcertplay.update

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowStatFs

/**
 * The update card as a state machine, and the rule that only the driver moves it.
 *
 * The card offers one button at a time. Nothing is fetched when the page opens: the card reads the
 * folder it already has, and the network is asked nothing until "check for updates" is pressed --
 * which is the entirety of the "no checking in the background" rule, and the reason this class is
 * testable at all. After that, the order of states is fixed, and each of them is reachable only from
 * the one before.
 *
 * Work runs on a worker thread and comes back through a main-thread handler, because that is what
 * the controller does on the car. The tests wait for a state rather than for a fixed time, and the
 * checker and the downloader are stand-ins that answer with what the test wrote -- the real ones are
 * covered by their own tests over loopback sockets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UpdateCardControllerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var cleaner: UpdateStorageCleaner
    private lateinit var checker: FakeChecker
    private lateinit var downloader: FakeDownloader
    private lateinit var controller: UpdateCardController

    /** Every state the page was shown, in order. */
    private val states: MutableList<UpdateCardController.State> = Collections.synchronizedList(mutableListOf())

    @Before fun setUp() {
        cleaner = UpdateStorageCleaner(context)
        cleaner.updateDir.deleteRecursively()
        cleaner.updateDir.mkdirs()
        // A car with room to spare. The platform's own answer here is "0 bytes free", which is
        // true of no head unit and would make every download test fail the space check first.
        ShadowStatFs.registerStats(cleaner.updateDir, 100_000, 0, 100_000)
        checker = FakeChecker()
        downloader = FakeDownloader()
        states.clear()
    }

    @After fun tearDown() {
        controller.close()
        cleaner.updateDir.deleteRecursively()
        ShadowStatFs.unregisterStats(cleaner.updateDir)
    }

    private fun controller(currentVersionCode: Int = 44, onInstall: (File) -> Unit = {}): UpdateCardController =
        UpdateCardController(
            context = context,
            cleaner = cleaner,
            checker = checker,
            downloader = downloader,
            currentVersionCode = currentVersionCode,
            onStateChanged = { states += it },
            onInstall = onInstall,
        ).also { controller = it }

    /** Lets the main thread run and waits for a state of [type], failing with what it saw instead. */
    private inline fun <reified T : UpdateCardController.State> await(timeoutMs: Long = 5_000, predicate: (T) -> Boolean = { true }): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            states.toList().filterIsInstance<T>().lastOrNull()?.takeIf(predicate)?.let { return it }
            states.toList().lastOrNull()?.let { last ->
                if (last is UpdateCardController.State.Error && T::class != UpdateCardController.State.Error::class) {
                    throw AssertionError("the card reported ${last.messageKey}: ${last.detail}")
                }
            }
            Thread.sleep(5)
        }
        throw AssertionError("the card never reached ${T::class.simpleName}; it went through ${states.map { it::class.simpleName }}")
    }

    private fun settle(millis: Long = 250) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    // ---- what the page starts as --------------------------------------------------------------

    @Test fun initialStateIsIdle() {
        val card = controller()

        val initial = card.state

        assertTrue(initial is UpdateCardController.State.Idle)
        assertEquals(44, initial.currentVersionCode)
        assertTrue("nothing is fetched before the driver asks", checker.requests.isEmpty())
    }

    // ---- checking ----------------------------------------------------------------------------

    @Test fun checkTransitionsToCheckingThenUpToDate() {
        checker.latest = release(44)
        val card = controller()

        card.check()
        await<UpdateCardController.State.UpToDate>()

        assertTrue(states.any { it is UpdateCardController.State.Checking })
    }

    @Test fun checkTransitionsToReadyToInstallIfLocalApkMatches() {
        // The package is already here and is the one the release offers: the download is skipped
        // outright rather than fetched a second time.
        checker.latest = release(45)
        val ready = downloadable(45)
        downloader.sums[ready.name] = sha256Of(ready)
        val card = controller()

        card.check()

        assertEquals(ready, await<UpdateCardController.State.ReadyToInstall>().file)
        assertEquals("nothing is downloaded again", 0, downloader.calls)
    }

    @Test fun aLocalPackageThatIsNotTheOneOfferedIsReplaced() {
        checker.latest = release(45)
        val stale = downloadable(45)
        downloader.sums[stale.name] = "b".repeat(64)
        val card = controller()

        card.check()

        await<UpdateCardController.State.UpdateAvailable>()
        assertEquals(0, downloader.calls)
        assertTrue("the file is left for the download to replace", stale.exists())
    }

    @Test fun checkOffersTheDownloadWhenThePackageIsNotHereYet() {
        checker.latest = release(45)
        val card = controller()

        card.check()

        val offered = await<UpdateCardController.State.UpdateAvailable>()
        assertEquals(45, offered.info.versionCode)
        assertEquals("the page can say how big it is before fetching it", 6_311_524L, offered.info.apkAsset.sizeBytes)
    }

    @Test fun aFailedCheckSaysWhyAndCanBeTriedAgain() {
        checker.failure = IOException("timeout")
        val card = controller()

        card.check()
        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_NETWORK }

        checker.failure = null
        checker.latest = release(44)
        card.check()
        await<UpdateCardController.State.UpToDate>()
    }

    // ---- downloading -------------------------------------------------------------------------

    @Test fun downloadUpdatesProgressAndReachesReadyToInstall() {
        checker.latest = release(45)
        val card = controller()
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()

        card.download()

        val ready = await<UpdateCardController.State.ReadyToInstall>()
        assertEquals(cleaner.apkFileName(45), ready.file.name)
        assertTrue(ready.file.isFile)
        assertTrue("the page is shown the bytes as they come", states.any { it is UpdateCardController.State.Downloading && it.bytesRead > 0 })
        assertTrue(states.any { it is UpdateCardController.State.Downloading && it.percent == 100 })
    }

    @Test fun aDownloadRefusedForSpaceIsSaidSoAndNeverStarts() {
        checker.latest = release(45)
        val card = controller()
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        ShadowStatFs.registerStats(cleaner.updateDir, 0, 0, 0)

        card.download()

        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_STORAGE }
        assertEquals("nothing is fetched onto a car with no room", 0, downloader.calls)
    }

    @Test fun cancelStopsTheDownloadAndReturnsToTheOffer() {
        checker.latest = release(45)
        downloader.blockUntilCancelled = true
        val card = controller()
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()

        card.download()
        await<UpdateCardController.State.Downloading>()
        assertTrue("the stand-in started", downloader.running.await(5, TimeUnit.SECONDS))
        card.cancel()

        // The page is on the offer as soon as the press lands; what the test still waits for is the
        // worker noticing the flag, which is when its fragment is gone.
        await<UpdateCardController.State.UpdateAvailable> { true }
        // The worker has to reach the flag before it can have removed its fragment; the page is
        // already on the offer by then, so the wait is for the worker and not for the page.
        downloader.finished.await(5, TimeUnit.SECONDS)
        assertTrue(
            "the download is told to stop; calls=${downloader.calls} states=${states.map { it::class.simpleName }}",
            downloader.cancelRequested,
        )
    }

    // ---- installing --------------------------------------------------------------------------

    @Test fun installHandsThePackageToTheInstaller() {
        checker.latest = release(45)
        val installed = Collections.synchronizedList(mutableListOf<File>())
        val card = controller(onInstall = { installed += it })
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        card.download()
        await<UpdateCardController.State.ReadyToInstall>()

        card.install()
        settle()

        assertEquals(cleaner.apkFileName(45), installed.single().name)
    }

    @Test fun aFailedInstallIsSaidSoAndKeepsThePackage() {
        checker.latest = release(45)
        val card = controller(onInstall = { throw IllegalStateException("no installer") })
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        card.download()
        val ready = await<UpdateCardController.State.ReadyToInstall>()

        card.install()

        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_INSTALL }
        assertTrue("a package that could not be opened is still worth pressing again", ready.file.exists())
    }

    // ---- retrying ----------------------------------------------------------------------------

    @Test fun retryAfterAFailedCheckChecksAgain() {
        checker.failure = IOException("timeout")
        val card = controller()
        card.check()
        await<UpdateCardController.State.Error>()

        checker.failure = null
        checker.latest = release(44)
        card.retry()

        await<UpdateCardController.State.UpToDate>()
        assertEquals(2, checker.requests.size)
        assertEquals("a check is not a download", 0, downloader.calls)
    }

    @Test fun retryAfterAFailedDownloadDownloadsAgain() {
        checker.latest = release(45)
        val card = controller()
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        downloader.failure = IOException("connection reset")
        card.download()
        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_DOWNLOAD }

        downloader.failure = null
        card.retry()

        await<UpdateCardController.State.ReadyToInstall>()
        assertEquals("the download is repeated, the check is not", 2, downloader.calls)
        assertEquals("and the release is not looked up again", 1, checker.requests.size)
    }

    @Test fun retryAfterAFailedInstallOpensTheInstallerAgain() {
        checker.latest = release(45)
        var failInstalls = true
        val installed = Collections.synchronizedList(mutableListOf<File>())
        val card = controller(
            onInstall = { file ->
                if (failInstalls) throw IllegalStateException("no installer")
                installed += file
            },
        )
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        card.download()
        await<UpdateCardController.State.ReadyToInstall>()
        card.install()
        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_INSTALL }

        failInstalls = false
        card.retry()
        settle()

        assertEquals(cleaner.apkFileName(45), installed.single().name)
        assertEquals("an install retry fetches nothing", 1, downloader.calls)
    }

    // ---- the rule ----------------------------------------------------------------------------

    @Test fun nothingIsCheckedUntilCheckIsCalled() {
        controller()

        settle()

        assertTrue("opening the card asks the network nothing", checker.requests.isEmpty())
        assertEquals(0, downloader.calls)
        assertTrue(states.all { it is UpdateCardController.State.Idle })
    }

    @Test fun aSecondCheckWhileOneIsRunningIsIgnored() {
        val gate = CountDownLatch(1)
        checker.gate = gate
        checker.latest = release(44)
        val card = controller()

        card.check()
        card.check()
        card.check()
        gate.countDown()
        await<UpdateCardController.State.UpToDate>()

        assertEquals("one press, one request", 1, checker.requests.size)
    }


    @Test fun downloadDoesNothingBeforeThereIsAnOffer() {
        val card = controller()

        card.download()
        settle()

        assertEquals(0, downloader.calls)
        assertTrue(card.state is UpdateCardController.State.Idle)
    }

    // ---- stand-ins ---------------------------------------------------------------------------

    private fun sha256Of(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /**
     * A package-shaped file the stand-in package manager can read as version [versionCode].
     *
     * The cleaner keeps a package only when it can read the version inside it, so a test that wants
     * a package to survive must give it one. The bytes are not an installable APK -- nothing here
     * installs anything -- but they carry the manifest the package manager is asked for.
     */
    private fun downloadable(versionCode: Int): File {
        val file = File(cleaner.updateDir, cleaner.apkFileName(versionCode))
        file.writeBytes(ByteArray(256) { it.toByte() })
        shadowOf(context.packageManager).setPackageArchiveInfo(
            file.absolutePath,
            android.content.pm.PackageInfo().apply { this.versionCode = versionCode },
        )
        return file
    }

    private fun release(versionCode: Int): ReleaseInfo {
        val versionName = "0.${versionCode / 10}.${versionCode % 10}-geely"
        return ReleaseInfo(
            tagName = "v$versionName",
            versionName = versionName,
            versionCode = versionCode,
            notes = "notes for $versionCode",
            apkAsset = ReleaseAsset(
                name = "DiPlay-Legacy-Geely-V$versionCode.apk",
                downloadUrl = "https://example.invalid/DiPlay-Legacy-Geely-V$versionCode.apk",
                sizeBytes = 6_311_524L,
            ),
            sha256Asset = ReleaseAsset(
                name = UpdateChecker.SUMS_ASSET_NAME,
                downloadUrl = "https://example.invalid/SHA256SUMS.txt",
                sizeBytes = 100,
            ),
        )
    }

    /** Answers with [latest] -- or fails with [failure] -- and remembers every request. */
    private class FakeChecker : UpdateChecker() {
        val requests: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        var latest: ReleaseInfo? = null
        var failure: Exception? = null
        var gate: CountDownLatch? = null

        override fun checkLatestRelease(currentVersionCode: Int, timeoutMs: Int): CheckResult {
            requests += currentVersionCode
            gate?.await(5, TimeUnit.SECONDS)
            failure?.let { throw it }
            val info = latest ?: throw IOException("no release")
            return compare(info, currentVersionCode)
        }
    }

    /** Writes [payload] to the target, or -- when [blockUntilCancelled] -- waits to be cancelled. */
    private class FakeDownloader : UpdateDownloader() {
        @Volatile var calls = 0
        @Volatile var blockUntilCancelled = false
        @Volatile var cancelRequested = false
        @Volatile var failure: Exception? = null
        /** Open once the stand-in is inside the download, so a test can cancel a running one. */
        val running = CountDownLatch(1)
        /** Open when the stand-in has returned, whichever way it ended. */
        val finished = CountDownLatch(1)
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** What the release's sums file says, by package name. */
        val sums: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

        override fun downloadAndVerify(
            apkUrl: String,
            targetFile: File,
            expectedSha256: String?,
            onProgress: (Long, Long) -> Unit,
            isCancelled: () -> Boolean,
        ): DownloadResult = try {
            download(apkUrl, targetFile, expectedSha256, onProgress, isCancelled)
        } finally {
            finished.countDown()
        }

        private fun download(
            apkUrl: String,
            targetFile: File,
            expectedSha256: String?,
            onProgress: (Long, Long) -> Unit,
            isCancelled: () -> Boolean,
        ): DownloadResult {
            calls++
            writes += targetFile.absolutePath
            failure?.let { throw it }
            val payload = ByteArray(1024)
            if (blockUntilCancelled) {
                running.countDown()
                val deadline = System.currentTimeMillis() + 5_000
                while (!isCancelled() && System.currentTimeMillis() < deadline) Thread.sleep(5)
                if (isCancelled()) {
                    cancelRequested = true
                    return DownloadResult.Cancelled
                }
            }
            onProgress(payload.size / 2L, payload.size.toLong())
            targetFile.writeBytes(payload)
            onProgress(payload.size.toLong(), payload.size.toLong())
            return DownloadResult.Success(targetFile, verified = expectedSha256 != null)
        }

        override fun fetchChecksum(sha256Url: String, apkFileName: String): String? = sums[apkFileName]
    }
}
