package com.shilapi.xcertplay.update

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.shilapi.xcertplay.host.R

/**
 * The update card's behaviour, kept apart from how the card is drawn.
 *
 * One thread of its own runs the work -- a socket for the check, a file for the download -- and every
 * result comes back on the main thread, so the page is only ever touched from where it is drawn. The
 * page draws from [State] and calls [check], [download], [cancel] and [install]; nothing else drives
 * this class, and in particular nothing runs on its own: no check at start-up, no timer, no retry in
 * the background.
 *
 * [currentVersionCode] is passed in rather than read here because the installed version is a fact
 * about the package the page belongs to, and this class is shared by two packages.
 *
 * [needsInstallerConfirmation] is the one difference between the two callers. A page has a driver in
 * front of it and asks before opening the installer; the service that finishes [UpdateHost]'s
 * download has no screen, and its install is the system's own. Both run the same sequence, and the
 * page it draws for is the same [State].
 */
class UpdateCardController(
    private val context: Context,
    private val cleaner: UpdateStorageCleaner,
    private val checker: UpdateChecker = UpdateChecker(),
    private val downloader: UpdateDownloader = UpdateDownloader(),
    private val currentVersionCode: Int,
    private val onStateChanged: (State) -> Unit,
    private val onInstall: (File) -> Unit,
    private val needsInstallerConfirmation: () -> Boolean = { true },
    private val sessionActive: () -> Boolean = { false },
) : AutoCloseable {

    /** What the page shows. [Idle] is everything before the driver has asked for anything. */
    sealed class State {
        abstract val currentVersionCode: Int

        data class Idle(override val currentVersionCode: Int) : State()

        data class Checking(override val currentVersionCode: Int) : State()

        data class UpToDate(override val currentVersionCode: Int, val versionName: String) : State()

        data class UpdateAvailable(
            override val currentVersionCode: Int,
            val info: ReleaseInfo,
            val storedBytes: Long = 0,
        ) : State()

        /**
         * [totalBytes] is -1 when the server did not say how much is coming, and the page then shows
         * the bytes that have arrived rather than a percentage of a number it does not have.
         */
        data class Downloading(
            override val currentVersionCode: Int,
            val info: ReleaseInfo,
            val bytesRead: Long,
            val totalBytes: Long,
        ) : State() {
            val percent: Int
                get() = if (totalBytes <= 0) 0 else ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
        }

        /** [info] is the release the package belongs to: what the page names as the new version. */
        data class ReadyToInstall(
            override val currentVersionCode: Int,
            val info: ReleaseInfo?,
            val file: File,
        ) : State()

        /**
         * [messageKey] is one of the `ERROR_*` constants and is what the page translates; [detail] is
         * the reason the platform gave, shown after it in smaller type and never translated.
         */
        data class Error(override val currentVersionCode: Int, val messageKey: String, val detail: String) : State()
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "update-link").apply { isDaemon = true }
    }

    /** Which change this is, so a result that arrives after a cancel is recognised as stale. */
    private val generation = AtomicInteger(0)
    private val cancelled = AtomicBoolean(false)
    private val downloadCancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /** The state the page was last given. Read on the main thread only. */
    @Volatile
    private var current: State = State.Idle(currentVersionCode)

    /** The release the download button would fetch. */
    @Volatile
    private var offered: ReleaseInfo? = null

    /** The step that last failed, so [retry] repeats that step rather than starting over. */
    private enum class Step { CHECK, DOWNLOAD, INSTALL }

    /** Main thread only: written when a failure is shown and read by [retry]. */
    private var failedStep = Step.CHECK

    /** The release the [State.Error] was about, for the one retry that needs it. Main thread only. */
    private var failedRelease: ReleaseInfo? = null


    val state: State get() = current

    /**
     * The driver pressed "check for updates". The only thing in this class that reaches the network.
     *
     * Allowed while a session is running: it fetches nothing but a small JSON and changes nothing on
     * the car. Everything that does is refused, for the reason the firmware card's own writes are.
     */
    fun check() {
        val state = current
        if (state is State.Checking || state is State.Downloading) return
        publish(State.Checking(currentVersionCode))
        val ticket = generation.incrementAndGet()
        cancelled.set(false)
        worker.execute {
            val result = try {
                checker.checkLatestRelease(currentVersionCode)
            } catch (e: Exception) {
                UpdateChecker.CheckResult.Error(reasonOf(e))
            }
            when (result) {
                is UpdateChecker.CheckResult.Error -> finish(ticket) {
                    fail(Step.CHECK, null, if (result.busy) ERROR_BUSY else ERROR_NETWORK, result.message)
                }
                is UpdateChecker.CheckResult.UpToDate -> finish(ticket) {
                    publish(State.UpToDate(currentVersionCode, result.info.versionName))
                }
                is UpdateChecker.CheckResult.UpdateFound -> offer(result.info, ticket)
            }
        }
    }

    /**
     * Decides, off the main thread, whether the offered release is already here.
     *
     * The folder is cleaned first, so a package for a version that is now installed goes before the
     * question is asked. Then the checksum is fetched -- the sums file is small and the answer decides
     * between an install and a download, so it is worth one request. A release without a sums file
     * cannot be checked; the package is then offered for download and the page says so.
     */
    private fun offer(info: ReleaseInfo, ticket: Int) {
        val versionCode = info.versionCode
        val sums = info.sha256Asset?.let { asset -> runCatching { downloader.fetchChecksum(asset.downloadUrl, info.apkAsset.name) }.getOrNull() }
        val found = cleaner.cleanOldUpdates(currentVersionCode).let { cleaner.findReadyApk(versionCode, sums) }
        // A file whose checksum matches but whose manifest cannot be read is not the package: the
        // name and the digest say what it should be, and the archive says it is not. Offering it
        // would be offering an install button that can only fail, and pressing download would write
        // the release's name over the file it is about to replace -- which is what happens anyway,
        // so the offer is the download.
        val ready = found?.takeIf { cleaner.isInstallable(it) }
        finish(ticket) {
            offered = info
            if (ready != null) {
                publish(State.ReadyToInstall(currentVersionCode, info, ready))
            } else {
                val stored = File(cleaner.updateDir, info.apkAsset.name).length()
                publish(State.UpdateAvailable(currentVersionCode, info, stored))
            }
        }
    }

    /**
     * Fetches the offered release. Refuses before it starts when the car has no room for it, because
     * a download stopped half way is worse than one never begun.
     */
    fun download() {
        // Only the release a check found is ever fetched. After "up to date" there is nothing to
        // fetch, and [offered] still names the release from before -- downloading that one would be
        // putting a package on the car that is older than the one it is running.
        val state = current
        if (state !is State.UpdateAvailable) return
        val info = offered ?: return
        // The install that follows lands while CarPlay is running otherwise, which restarts the app
        // under a live session. The firmware card refuses its writes for the same reason.
        if (sessionActive()) {
            fail(Step.DOWNLOAD, info, ERROR_SESSION, "")
            return
        }
        val ticket = generation.incrementAndGet()
        publish(State.Downloading(currentVersionCode, info, 0, info.apkAsset.sizeBytes))
        // A flag from the previous attempt -- including one set by a cancel that landed before that
        // attempt reached its first buffer -- must not end this one before it starts.
        downloadCancelled.set(false)
        cancelled.set(false)
        worker.execute {
            if (!cleaner.hasAdequateFreeSpace()) {
                finish(ticket) { fail(Step.DOWNLOAD, info, ERROR_STORAGE, "") }
                return@execute
            }
            // One package at a time: what the folder holds for another version goes before this
            // download writes another 6 MB beside it.
            cleaner.prepareForDownload(info.versionCode)
            val apkFile = File(cleaner.updateDir, info.apkAsset.name)
            // The checksum is asked for again here rather than kept from the check: the copy that
            // decides the install is the one fetched beside the download.
            val expected = info.sha256Asset?.let { asset -> downloader.fetchChecksum(asset.downloadUrl, info.apkAsset.name) }
            // Anything the downloader throws, rather than returns, ends this attempt the same way a
            // failure it returns does: the page is told and the fragment is gone. Letting it escape
            // would leave the page on a download that is no longer running.
            val result = try {
                downloader.downloadAndVerify(
                    apkUrl = info.apkAsset.downloadUrl,
                    targetFile = apkFile,
                    expectedSha256 = expected,
                    onProgress = { read, total -> post { showProgress(ticket, info, read, total) } },
                    isCancelled = { downloadCancelled.get() || cancelled.get() || closed.get() },
                )
            } catch (e: Exception) {
                File(cleaner.updateDir, info.apkAsset.name + UpdateStorageCleaner.PARTIAL_SUFFIX).delete()
                UpdateDownloader.DownloadResult.Failure(reasonOf(e))
            }
            finish(ticket) {
                when (result) {
                    is UpdateDownloader.DownloadResult.Success -> publish(State.ReadyToInstall(currentVersionCode, info, result.file))
                    UpdateDownloader.DownloadResult.Cancelled -> publish(State.UpdateAvailable(currentVersionCode, info))
                    is UpdateDownloader.DownloadResult.Failure -> fail(Step.DOWNLOAD, info, errorKeyFor(result), result.error)
                }
            }
        }
    }

    /**
     * Stops the download in flight. The page goes back to the offer, and nothing is left on disk.
     *
     * The worker notices the flag within one buffer and removes its fragment before the page is told
     * anything. The new state is published here rather than only by the worker, so a cancel that
     * lands before the worker has reached its first buffer still ends in the offer instead of
     * leaving the page on a download that is no longer running.
     */
    fun cancel() {
        val state = current as? State.Downloading ?: return
        downloadCancelled.set(true)
        cancelled.set(true)
        generation.incrementAndGet()
        publish(State.UpdateAvailable(currentVersionCode, state.info))
    }

    /**
     * Opens the system installer on the package that was downloaded.
     *
     * The package is checked once more here, because the folder is a cache the system may reclaim:
     * between the download finishing and this press, Android can have taken the file away, and a
     * request for a file that is gone is an install that could only fail. What has gone is a
     * download away rather than an error to show, so the page goes back to the offer.
     */
    fun install() {
        val ready = current as? State.ReadyToInstall ?: return
        if (sessionActive()) {
            fail(Step.INSTALL, ready.info, ERROR_SESSION, "")
            return
        }
        if (!cleaner.isInstallable(ready.file)) {
            fail(Step.DOWNLOAD, ready.info, ERROR_DOWNLOAD, "the package is gone")
            return
        }
        // On the car, an activity calls this and the user is there to answer the installer's own
        // dialog. The host service calls it with no screen to put a dialog on -- and the installer is
        // started without its confirmation then, inside the system's own installation session.
        if (!needsInstallerConfirmation()) {
            startInstaller(ready)
            return
        }
        installerQuestion = AlertDialog.Builder(context)
            .setTitle(R.string.install_now)
            .setMessage(context.getString(R.string.update_install_confirm, ready.info?.versionName.orEmpty()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.install_now) { _, _ -> startInstaller(ready) }
            .show()
    }

    /**
     * The question that is up, while it is up. Null before any is asked and after one is dismissed.
     *
     * Kept so a test can answer it: a dialog is the only thing between a press on "install now" and
     * the installer on the car, and a test that could not press it would be testing everything but
     * that.
     */
    var installerQuestion: AlertDialog? = null
        private set

    private fun startInstaller(ready: State.ReadyToInstall) {
        try {
            onInstall(ready.file)
        } catch (e: Exception) {
            fail(Step.INSTALL, ready.info, ERROR_INSTALL, reasonOf(e))
        }
    }

    /**
     * Repeats the step that failed.
     *
     * "Retry" on a download that broke means fetching it again, not checking again; on an install
     * that could not open, opening it again. The package that was downloaded is still there, so an
     * install retry never goes back to the network.
     */
    fun retry() {
        if (current !is State.Error) return
        when (failedStep) {
            Step.CHECK -> check()
            Step.DOWNLOAD -> {
                // The offer is published before the download starts, so the page says which release
                // is being fetched while it is; [offered] is already the one the failure was about.
                val info = failedRelease ?: return
                offered = info
                publish(State.UpdateAvailable(currentVersionCode, info))
                download()
            }
            Step.INSTALL -> {
                val info = failedRelease
                val file = info?.let { File(cleaner.updateDir, it.apkAsset.name) }
                if (info != null && file != null && cleaner.isInstallable(file)) {
                    publish(State.ReadyToInstall(currentVersionCode, info, file))
                    install()
                } else {
                    val retryInfo = info ?: return
                    offered = retryInfo
                    publish(State.UpdateAvailable(currentVersionCode, retryInfo))
                    download()
                }
            }
        }
    }

    /** The page's sentence for the way a download failed. */
    private fun errorKeyFor(result: UpdateDownloader.DownloadResult.Failure): String = when (result.kind) {
        UpdateDownloader.DownloadResult.Failure.Kind.CHECKSUM -> ERROR_CHECKSUM
        UpdateDownloader.DownloadResult.Failure.Kind.INSECURE -> ERROR_INSECURE
        UpdateDownloader.DownloadResult.Failure.Kind.NO_SPACE -> ERROR_STORAGE
        UpdateDownloader.DownloadResult.Failure.Kind.NETWORK -> ERROR_DOWNLOAD
        UpdateDownloader.DownloadResult.Failure.Kind.OTHER -> ERROR_DOWNLOAD
    }

    /** Records what failed and says so. Main thread only: [retry] reads what this writes. */
    private fun fail(step: Step, info: ReleaseInfo?, messageKey: String, detail: String) {
        failedStep = step
        failedRelease = info
        publish(State.Error(currentVersionCode, messageKey, detail))
    }

    /**
     * The page went away. The worker is stopped and nothing more is published: a handler message that
     * arrives after the page is gone would draw on a view that is no longer there.
     */
    override fun close() {
        closed.set(true)
        cancelled.set(true)
        downloadCancelled.set(true)
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    // ---- thread hand-offs --------------------------------------------------------------------

    private fun post(block: () -> Unit) {
        if (closed.get()) return
        main.post { if (!closed.get()) block() }
    }

    private fun finish(ticket: Int, block: () -> Unit) = post {
        // A result for a check the driver abandoned is dropped rather than shown as news.
        if (ticket == generation.get()) block()
    }

    private fun showProgress(ticket: Int, info: ReleaseInfo, read: Long, total: Long) {
        if (ticket != generation.get()) return
        val state = current
        if (state !is State.Downloading) return
        publish(State.Downloading(currentVersionCode, info, read, total))
    }

    private fun publish(state: State) {
        if (closed.get()) return
        current = state
        onStateChanged(state)
    }

    private fun reasonOf(error: Throwable): String =
        error.javaClass.simpleName + (error.message?.let { ": $it" } ?: "")

    companion object {
        /** Could not reach GitHub, or GitHub did not answer with a release. */
        const val ERROR_NETWORK = "update_error_network"

        /** GitHub refused the request: a rate limit, or a private release. Waiting is the answer. */
        const val ERROR_BUSY = "update_error_busy"

        /** The bytes did not match the sums file, and were not kept. */
        const val ERROR_CHECKSUM = "update_error_checksum"

        /** The release points somewhere this link does not download from. */
        const val ERROR_INSECURE = "update_error_insecure"

        /** CarPlay is running; the update is offered again once it is not. */
        const val ERROR_SESSION = "update_session_active"

        /** The car has no room for the package. */
        const val ERROR_STORAGE = "update_error_storage"

        /** The bytes are not the ones the sums file describes, or the connection broke. */
        const val ERROR_DOWNLOAD = "update_error_download"

        /** The system installer could not be opened. */
        const val ERROR_INSTALL = "update_error_install"
    }
}
