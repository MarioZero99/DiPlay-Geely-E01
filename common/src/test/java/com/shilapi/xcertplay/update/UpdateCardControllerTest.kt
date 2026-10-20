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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /**
     * The card with a page behind it. [confirmInstall] stands for the driver at the installer's own
     * question -- false answers it straight away, which is what the car's service does.
     */
    private fun controller(
        currentVersionCode: Int = 44,
        confirmInstall: Boolean = false,
        sessionActive: () -> Boolean = { false },
        onInstall: (File) -> Unit = {},
    ): UpdateCardController =
        UpdateCardController(
            context = context,
            cleaner = cleaner,
            checker = checker,
            downloader = downloader,
            currentVersionCode = currentVersionCode,
            onStateChanged = { states += it },
            onInstall = onInstall,
            needsInstallerConfirmation = { confirmInstall },
            sessionActive = sessionActive,
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

    @Test fun installAsksBeforeItOpensTheInstaller() {
        // On the car, pressing "install now" opens a question first; nothing reaches the installer
        // until it is answered. The answer itself is a dialog, which a unit test cannot press, so
        // what is pinned here is that the question is asked and that no installer ran meanwhile.
        checker.latest = release(45)
        val installed = Collections.synchronizedList(mutableListOf<File>())
        val card = controller(confirmInstall = true, onInstall = { installed += it })
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        card.download()
        await<UpdateCardController.State.ReadyToInstall>()

        card.install()
        settle()

        assertEquals("nothing installs before the question is answered", 0, installed.size)
        assertTrue("and the package is still there to answer for", card.state is UpdateCardController.State.ReadyToInstall)

        // Answering it is answering the driver's own press: the installer starts after.
        val question = card.installerQuestion
        assertNotNull("the question is asked", question)
        question!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        settle()

        assertEquals(cleaner.apkFileName(45), installed.single().name)
    }

    @Test fun dismissingTheInstallQuestionInstallsNothing() {
        checker.latest = release(45)
        val installed = Collections.synchronizedList(mutableListOf<File>())
        val card = controller(confirmInstall = true, onInstall = { installed += it })
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        card.download()
        await<UpdateCardController.State.ReadyToInstall>()

        card.install()
        settle()
        card.installerQuestion!!.dismiss()
        settle()

        assertEquals("a dismissed question is a no", 0, installed.size)
    }

    @Test fun aPackageThatTheSystemCacheTookAwayIsADownloadAgainNotAnInstallError() {
        // The update folder is a cache: between the download finishing and the press, Android may
        // reclaim it. The page says what happened and the next press fetches it again.
        checker.latest = release(45)
        val installed = Collections.synchronizedList(mutableListOf<File>())
        val card = controller(onInstall = { installed += it })
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        card.download()
        val ready = await<UpdateCardController.State.ReadyToInstall>()
        ready.file.delete()

        card.install()

        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_DOWNLOAD }
        assertEquals("no installer was opened for a file that is not there", 0, installed.size)

        card.retry()
        val again = await<UpdateCardController.State.ReadyToInstall> { it.file.isFile }
        assertEquals("retry fetches it again", 2, downloader.calls)
        assertTrue(again.file.isFile)
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

    // ---- packages that are on disk but not installable -----------------------------------------

    @Test fun aDamagedDownloadIsReplacedRatherThanOfferedAgain() {
        // A package from an interrupted transfer wearing the release's name and digest is what the
        // folder can hold: the downloader writes beside it and renames only a verified file, but the
        // release's name is fixed, so a file already there is the file the next download writes to.
        // A file whose manifest the package manager cannot read cannot be installed -- and the page
        // used to take it straight to "install", which is a button that could only fail.
        checker.latest = release(45)
        val damaged = File(cleaner.updateDir, cleaner.apkFileName(45)).apply { writeBytes(ByteArray(256)) }
        downloader.sums[damaged.name] = sha256Of(damaged)
        val card = controller()

        card.check()

        await<UpdateCardController.State.UpdateAvailable>()
        assertEquals("it is not taken for an installable package", 0, downloader.calls)
    }

    @Test fun aPackageManagerThatCannotReadArchivesStillDownloads() {
        // `getPackageArchiveInfo` answers null in this stand-in -- which is every Robolectric test --
        // while the checksum matches and the release does not care. The archive's manifest is a
        // reason to distrust a package, not a reason to refuse one the checksum vouches for.
        checker.latest = release(45)
        val ready = downloadable(45)
        downloader.sums[ready.name] = sha256Of(ready)
        val card = controller()

        card.check()

        assertEquals(ready, await<UpdateCardController.State.ReadyToInstall>().file)
        // The cleaner has to have kept it for that to happen at all.
        assertFalse("the cleaner must not have removed a package whose checksum matches", ready.exists().not())
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

    @Test fun aDownloadPressedWithoutACheckDoesNothing() {
        // "Download" means the release this page found. A page that never found one -- or one that
        // found the app was current -- has nothing to fetch, and must not go looking for something.
        checker.latest = release(44)
        val card = controller()
        card.check()
        await<UpdateCardController.State.UpToDate>()

        card.download()
        settle()

        assertEquals("nothing is fetched without an offer", 0, downloader.calls)
        assertTrue(card.state is UpdateCardController.State.UpToDate)
    }

    @Test fun theStateAfterAFinishedDownloadSaysWhichPackageIsReady() {
        checker.latest = release(45)
        val card = controller()
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        card.download()
        val ready = await<UpdateCardController.State.ReadyToInstall>()

        // The page says the package's own name. The version it belongs to is the one the offer
        // named, so the two have to agree even after a rename.
        assertEquals(downloader.lastApkFileName, ready.file.name)
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

    // ---- a session that is running -------------------------------------------------------------

    @Test fun aDownloadIsRefusedWhileCarPlayIsRunning() {
        // The install that follows restarts the app under a live session. Nothing lands on the car
        // while one is running, which is the same rule the firmware card's writes follow.
        checker.latest = release(45)
        val card = controller(sessionActive = { true })
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()

        card.download()

        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_SESSION }
        assertEquals("nothing is fetched", 0, downloader.calls)
    }

    @Test fun anInstallIsRefusedWhileCarPlayIsRunning() {
        checker.latest = release(45)
        var live = false
        val installed = Collections.synchronizedList(mutableListOf<File>())
        val card = controller(sessionActive = { live }, onInstall = { installed += it })
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        card.download()
        await<UpdateCardController.State.ReadyToInstall>()
        live = true

        card.install()

        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_SESSION }
        assertEquals("the installer is not opened", 0, installed.size)
    }

    @Test fun checkingIsStillAllowedWhileCarPlayIsRunning() {
        // A check fetches a small JSON and touches nothing on the car: the one thing a driver may do
        // mid-session is ask whether there is something to install once it ends.
        checker.latest = release(44)
        val card = controller(sessionActive = { true })

        card.check()

        await<UpdateCardController.State.UpToDate>()
    }

    // ---- the folder holds one package -----------------------------------------------------------

    @Test fun aDownloadLeavesOnlyThePackageItFetched() {
        // Two packages would be two of everything the folder is budgeted for. What is there for a
        // version other than the one being fetched is not what anything is about to install.
        checker.latest = release(45)
        val other = readablePackage(context, File(cleaner.updateDir, cleaner.apkFileName(46)), 46)
        val fragment = File(cleaner.updateDir, cleaner.apkFileName(45) + UpdateStorageCleaner.PARTIAL_SUFFIX).apply { writeBytes(ByteArray(4)) }
        val card = controller()
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()

        card.download()
        val ready = await<UpdateCardController.State.ReadyToInstall>()

        assertEquals("one package", listOf(cleaner.apkFileName(45)), cleaner.updateDir.list()!!.sorted())
        assertEquals(cleaner.apkFileName(45), ready.file.name)
        assertTrue("the one for another version is gone", !other.exists())
        assertTrue("and so is the fragment", !fragment.exists())
    }

    @Test fun aSumsFileThatCannotBeFetchedDoesNotOfferAnInstall() {
        checker.latest = release(45)
        downloader.checksumMissing = true
        val card = controller()

        card.check()

        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_CHECKSUM }
        assertEquals(0, downloader.calls)
    }

    @Test fun aDownloadWhoseChecksumDisappearsIsNotFetched() {
        checker.latest = release(45)
        val card = controller()
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        downloader.checksumMissing = true

        card.download()

        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_CHECKSUM }
        assertEquals(0, downloader.calls)
    }

    @Test fun aFailureIsSaidInTheWordsItsKindDeserves() {
        checker.latest = release(45)
        val card = controller()
        card.check()
        await<UpdateCardController.State.UpdateAvailable>()
        downloader.failureResult = UpdateDownloader.DownloadResult.Failure(
            "checksum mismatch",
            UpdateDownloader.DownloadResult.Failure.Kind.CHECKSUM,
        )

        card.download()

        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_CHECKSUM }
    }

    @Test fun aGitHubThatRefusesTheCheckSaysToWait() {
        checker.refusal = UpdateChecker.CheckResult.Error("http 403", busy = true)
        val card = controller()

        card.check()

        await<UpdateCardController.State.Error> { it.messageKey == UpdateCardController.ERROR_BUSY }
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
    private fun downloadable(versionCode: Int): File =
        readablePackage(context, File(cleaner.updateDir, cleaner.apkFileName(versionCode)), versionCode)

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
        @Volatile var refusal: UpdateChecker.CheckResult.Error? = null

        val requests: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        var latest: ReleaseInfo? = null
        var failure: Exception? = null
        var gate: CountDownLatch? = null

        override fun checkLatestRelease(currentVersionCode: Int, timeoutMs: Int): CheckResult {
            requests += currentVersionCode
            gate?.await(5, TimeUnit.SECONDS)
            failure?.let { throw it }
            refusal?.let { return it }
            val info = latest ?: throw IOException("no release")
            return compare(info, currentVersionCode)
        }
    }

    /** Writes a readable package to the target, or -- when [blockUntilCancelled] -- waits to be cancelled. */
    private inner class FakeDownloader : UpdateDownloader() {
        @Volatile var calls = 0
        @Volatile var blockUntilCancelled = false
        @Volatile var cancelRequested = false
        @Volatile var failure: Exception? = null
        @Volatile var failureResult: UpdateDownloader.DownloadResult.Failure? = null
        /** The name the offer asked to be written under. */
        @Volatile var lastApkFileName: String? = null
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
            lastApkFileName = targetFile.name
            failure?.let { throw it }
            failureResult?.let { return it }
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
            readablePackage(context, targetFile, 45)
            onProgress(payload.size.toLong(), payload.size.toLong())
            return DownloadResult.Success(targetFile, verified = expectedSha256 != null)
        }

        /** A sums file that was published but could not be fetched. The download must not proceed. */
        @Volatile var checksumMissing = false

        override fun fetchChecksum(sha256Url: String, apkFileName: String): String? {
            if (checksumMissing) return null
            return sums[apkFileName] ?: "ab".repeat(32)
        }
    }
}
