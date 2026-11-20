package com.shilapi.xcertplay.instrument.fly

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.ui.ThreeFingerSwipeDetector.Decision
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Where the factory commands really go. The Activity sends them; tests record them. */
fun interface FlyCommandSink {
    /** False means the command did not get out. Only a failed [FlyCommand.FLY_LEFT] aborts. */
    fun send(command: FlyCommand): Boolean
}

/**
 * Joins the gesture, the state machine, the cluster's display and the centre screen's picture.
 *
 * The session and everything that steers it sit behind one lock: the fly thread starts the decoder
 * on one side, the main thread takes gestures and safety broadcasts on the other, and both move the
 * same state machine. The picture itself is subscribed only while the session says it is pushing,
 * so a session with no cluster watching costs the decoder nothing.
 */
class ClusterFlyController(
    private val commands: FlyCommandSink,
    private val showWarning: (String) -> Unit,
    private val setMainVideoSubscribed: (Boolean) -> Unit,
    /** Whether the head unit has finished the settings that make the layer usable. */
    private val provisioned: () -> Boolean = { true },
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val postDelayed: (Long, () -> Unit) -> Unit = { delay, action ->
        Handler(Looper.getMainLooper()).postDelayed(action, delay)
    },
    /** Runs once when the left step has gone out, so the Activity can put its picture on the cluster. */
    private val onLayerOpened: () -> Unit = {},
    /** The centre screen's last avcC record, for the decoder's first surface. */
    private val mainVideoConfig: () -> ByteArray? = { null },
    /** The centre screen's codec. The cluster decoder only decodes H.264; an HEVC session has none. */
    private val mainVideoCodec: () -> VideoCodec? = { null },
    /** Posts to the main thread. The decoder runs on the fly thread, the presentation does not. */
    private val onMain: (() -> Unit) -> Unit = { action -> action() },
    /** The Activity takes its cluster window down. Called once per failed picture. */
    private val onGiveUp: () -> Unit = {},
    /** Asks the phone for one IDR. A still picture offers none, and the decoder starts on one. */
    private val requestKeyFrame: () -> Unit = {},
    frameExecutor: ExecutorService? = null,
) {

    private val session = ClusterFlySession(log)

    /** The session's phase, for tests and for anything that only reads. */
    val phase: FlyPhase
        get() = session.currentPhase

    /**
     * The fly thread. CarPlay's media thread hands over a frame and returns; everything that could
     * block — the decode, the surface, a slow unit — happens here.
     *
     * The work queue is unbounded on purpose: it carries control as well as frames, and losing a
     * "the surface is gone" task would leak the decoder. Frames are admitted by [framesInFlight]
     * instead, so the picture is still dropped, never buffered, and never waits.
     */
    private val frameExecutor: ExecutorService = frameExecutor ?: ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { runnable -> Thread(runnable, "cluster-fly-frames").apply { isDaemon = true } },
    )

    /** Frames waiting for the fly thread. The one decoding is not counted; control never is. */
    private val framesWaiting = AtomicInteger(0)

    /** The one lock. Taken by gestures, safety broadcasts, the fly thread and posted steps. */
    private val lock = Any()

    /** Before any command has been sent. Not `0`: uptime can legitimately start there. */
    private var lastCommandAt = NO_TIME

    private var radar = false
    private var ecall = false

    /** CarPlay is the navigation picture the cluster's map page is being pointed at. */
    private var carPlayActive = true

    private var subscribed = false

    /** A step scheduled before a session ended belongs to the old session and is dropped. */
    private var generation = 0

    /** The three-second wait for the cluster's display; a newer wait or a started decoder cancels it. */
    private var videoTimeoutGeneration = 0

    /** Set on the fly thread, once. The media thread never pays for the priority change. */
    private var background = false

    /** The presentation's surface and the decoder drawing on it; both exist only while it is up. */
    private var flyDecoder: FlyClusterDecoder? = null
    private var waitingSurface: Surface? = null
    private var pendingConfig: ByteArray? = null
    private var configWarned = false

    /** A dropped frame leaves the GOP torn: the decoder must restart on the next IDR. */
    private val dropUntilIdr = AtomicBoolean(false)

    fun onGesture(decision: Decision) {
        synchronized(lock) {
            val now = clock()
            when (decision) {
                Decision.FLY_LEFT -> {
                    log(ClusterFlyLog.gesture(FlyDirection.LEFT))
                    handle(session.onLeft(now, conditions(now)))
                }

                Decision.FLY_RIGHT -> {
                    log(ClusterFlyLog.gesture(FlyDirection.RIGHT))
                    handle(session.onRight(now, conditions(now)))
                }

                Decision.TRIGGER -> log(ClusterFlyLog.gestureDown())
                else -> Unit
            }
        }
    }

    /**
     * The cluster's surface is up and the decoder has started: the picture is live.
     *
     * Idempotent: once the picture is pushing, a second call does nothing. The first caller turns
     * the subscription on, cancels the three-second abort of this round, and asks the phone for one
     * IDR — a still CarPlay picture offers none, and the software decoder starts only on one.
     * Runs on the fly thread, from the decoder path; never from [sendFlyCommand] — when the left
     * step returns the session is still arming.
     */
    fun onClusterDisplayReady() {
        synchronized(lock) {
            if (session.currentPhase == FlyPhase.PUSHING) return
            val effect = session.onDisplayReady(clock())
            if (effect.phase != FlyPhase.PUSHING) return
            videoTimeoutGeneration++
            if (!subscribed) {
                subscribed = true
                setMainVideoSubscribed(true)
            }
            handle(effect)
            requestKeyFrame()
        }
    }

    fun onRadar(active: Boolean) {
        synchronized(lock) {
            if (radar == active) return
            radar = active
            safetyChanged(if (active) FlyBlock.RADAR else null)
        }
    }

    fun onEcall(active: Boolean) {
        synchronized(lock) {
            if (ecall == active) return
            ecall = active
            safetyChanged(if (active) FlyBlock.ECALL else null)
        }
    }

    /** A screen stream is active again: the picture the cluster flies to is back. */
    fun onCarPlayStarted() {
        synchronized(lock) {
            carPlayActive = true
        }
    }

    /**
     * CarPlay ended and the layer has to come down.
     *
     * The close is judged on the session that was live — conditions are taken before the navigation
     * flag falls, or the gate would answer `NOT_NAVIGATING` and no close command would ever go out.
     */
    fun onCarPlayEnded() {
        synchronized(lock) {
            generation++
            val now = clock()
            val effect = session.onCarPlayEnded(now, conditions(now))
            carPlayActive = false
            handle(effect)
            stopEverything()
        }
        // The window is still open on the cluster. Take it down; a new session at the same display
        // must be able to attach again.
        onGiveUp()
    }

    /** The Activity is going away: no warning, no commands, no sockets. */
    fun close() {
        synchronized(lock) {
            generation++
            // The three-second abort belongs to this Activity; if it is gone, so is the timer.
            videoTimeoutGeneration++
            stopEverything()
        }
        // Release the decoder on its own thread, then let the queue drain. A shutdown that discards
        // the task leaks a MediaCodec, and this head unit has no spare memory for that.
        try {
            frameExecutor.execute {
                flyDecoder?.release()
                flyDecoder = null
                waitingSurface = null
                pendingConfig = null
                configWarned = false
            }
        } catch (_: RejectedExecutionException) {
        }
        frameExecutor.shutdown()
    }

    /**
     * Hands one unit of the centre screen's video to the fly thread and returns.
     *
     * This is the only thing the CarPlay media thread does for the cluster: one queue offer, no copy,
     * no allocation of its own. The decoder keeps the same array in its own queue (`submit` is an
     * offer too), so the reference stays valid; the fly thread does the rest.
     */
    fun onMainVideo(type: Int, bytes: ByteArray) {
        if (framesWaiting.incrementAndGet() > FRAME_QUEUE_CAPACITY) {
            // One frame waiting, one decoding. A third is dropped: a backlog here is a software
            // decode running ahead of the picture the driver is looking at.
            framesWaiting.decrementAndGet()
            dropUntilIdr.set(true)
            return
        }
        try {
            frameExecutor.execute {
                framesWaiting.decrementAndGet()
                processFrame(type, bytes)
            }
        } catch (_: RejectedExecutionException) {
            // The layer is closed: one countable drop, never a wait. The GOP is now missing a
            // frame, so the fly thread waits for the next IDR.
            framesWaiting.decrementAndGet()
            dropUntilIdr.set(true)
        }
    }

    /**
     * The presentation's surface arrived (or went away). Runs on the fly thread.
     *
     * The decoder is created here and nowhere else: it lives exactly as long as there is a surface
     * to draw on. A surface that arrives before the configuration leaves the picture waiting; the
     * next configuration unit starts it.
     */
    fun onFlySurface(surface: Surface?) {
        try {
            frameExecutor.execute {
                if (surface == null) {
                    flyDecoder?.release()
                    flyDecoder = null
                    waitingSurface = null
                    // The next session may have a different resolution or profile; never start it
                    // on the previous one's record.
                    pendingConfig = null
                    configWarned = false
                    return@execute
                }
                waitingSurface = surface
                tryStartDecoder()
            }
        } catch (_: RejectedExecutionException) {
            // The controller is closed; the decoder went with the fly thread.
        }
    }

    /** The cluster window is up but the picture cannot start. Tear the window down again. */
    fun onPictureFailed() {
        endPicture("decoder failed")
    }

    /**
     * The system took the presentation's display away. The session must not stay in PUSHING: the
     * next left swipe would be swallowed as if the layer were still up.
     */
    fun onClusterDisplayLost() {
        endPicture("display gone")
    }

    private fun endPicture(reason: String) {
        onMain {
            synchronized(lock) {
                if (session.currentPhase == FlyPhase.PUSHING) {
                    // 1885 already lifted the layer, so it has to be covered again.
                    val effect = session.onRight(clock(), conditions(clock()))
                    subscribed = false
                    setMainVideoSubscribed(false)
                    handle(effect)
                    onGiveUp()
                } else {
                    abortSession(reason)
                }
            }
        }
    }

    private fun tryStartDecoder() {
        if (flyDecoder != null || waitingSurface == null) return
        val codec = mainVideoCodec()
        if (codec != null && codec != VideoCodec.H264) {
            log("fly.display codec unsupported ${codec.name}")
            onPictureFailed()
            return
        }
        val config = pendingConfig ?: mainVideoConfig() ?: run {
            if (!configWarned) {
                configWarned = true
                log("fly.display no config")
            }
            return
        }
        val sets = FlyAnnex.parameterSets(config) ?: run {
            log("fly.display decoder failed")
            onPictureFailed()
            return
        }
        val created = FlyClusterDecoder(waitingSurface!!, sets.sps, sets.pps, log)
        if (!created.ready) {
            created.release()
            onPictureFailed()
            return
        }
        flyDecoder = created
        onClusterDisplayReady()
    }

    /**
     * The fly thread's work for one unit.
     *
     * The configuration record (avcC) is kept for the decoder's first surface and never handed to
     * the decoder as a picture. Every other unit goes straight to the software decoder while the
     * session is pushing; a unit that arrives while it is not is dropped, not buffered.
     */
    private fun processFrame(type: Int, bytes: ByteArray) {
        if (!background) {
            background = true
            // Below the UI and the media thread. The software decode uses whatever core is left.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            }
        }
        if (FlyAnnex.isConfig(bytes)) pendingConfig = bytes.copyOf()
        tryStartDecoder()
        val decoder = flyDecoder ?: return
        val phase = synchronized(lock) { session.currentPhase }
        if (phase != FlyPhase.PUSHING) return
        if (FlyAnnex.isConfig(bytes)) return
        if (dropUntilIdr.compareAndSet(true, false)) decoder.dropUntilIdr()
        try {
            decoder.submit(bytes)
        } catch (error: RuntimeException) {
            log("fly.display decoder failed ${error.javaClass.simpleName}")
            decoder.release()
            flyDecoder = null
            onPictureFailed()
        }
    }

    private fun conditions(now: Long): FlyConditions = FlyConditions(
        navigationArmed = carPlayActive,
        deviceProvisioned = provisioned(),
        reversing = false,
        radar = radar,
        ecall = ecall,
        millisSinceLastClusterCommand = when (lastCommandAt) {
            NO_TIME -> Long.MAX_VALUE
            else -> now - lastCommandAt
        },
    )

    private fun handle(effect: FlyEffect) {
        effect.warning?.let { block -> ClusterFlyWarning.text(block)?.let(showWarning) }

        if (effect.push != subscribed) {
            subscribed = effect.push
            setMainVideoSubscribed(effect.push)
        }

        if (effect.steps.isNotEmpty()) {
            // A new command pair supersedes anything still queued from the old one — a right swipe
            // during the left pair must not be followed by a late FLY_LEFT.
            generation++
        }
        val expected = generation
        for (step in effect.steps) {
            val delay = (step.sendAtMillis - clock()).coerceAtLeast(0L)
            if (delay == 0L) {
                sendStep(step)
            } else {
                postDelayed(delay) {
                    synchronized(lock) {
                        if (generation == expected) sendStep(step)
                    }
                }
            }
        }
    }

    private fun sendStep(step: FlyStep) {
        log(ClusterFlyLog.command(step.command))
        val ok = commands.send(step.command)
        // A withdrawal does not restart the interval: the driver waits one second from the failure,
        // not one second from the message that takes the failure back.
        if (!step.retraction) lastCommandAt = clock()
        if (step.command != FlyCommand.FLY_LEFT) return
        if (!ok) {
            // The write never reached RpcService. Waiting three seconds for a page change nobody
            // asked the cluster to make would just keep the next swipe locked out.
            abortSession("1885 left false")
            return
        }
        // The left step has gone out; from here the cluster's display may come up and start the
        // picture. The three-second clock is armed after the window has been put up.
        val effect = session.onWaitTimeout(clock())
        handle(effect)
        // The cluster window goes up before the three-second clock is armed: the surface arrives
        // later, and the clock is what takes the layer down if it never does.
        if (ok && session.currentPhase == FlyPhase.WAITING_FOR_VIDEO) onLayerOpened()
        if (session.currentPhase == FlyPhase.WAITING_FOR_VIDEO) scheduleVideoTimeout()
    }

    /** Starts the three-second clock. Only a real ask from the cluster cancels it. */
    private fun scheduleVideoTimeout() {
        val expected = ++videoTimeoutGeneration
        postDelayed(VIDEO_ASK_TIMEOUT_MILLIS) {
            synchronized(lock) {
                if (videoTimeoutGeneration != expected) return@synchronized
                if (session.currentPhase != FlyPhase.WAITING_FOR_VIDEO) return@synchronized
                abortSession("cluster silent")
            }
        }
    }

    /**
     * Takes back what the opening sent and returns to idle.
     *
     * No `FLY_RIGHT`: the page change never took effect, so there is no layer to cover. The
     * navigation flag and the factory's front message are withdrawn; `handle` puts the owed
     * `NAV_BACK` on the queue under a new generation, so a second swipe cancels it.
     */
    private fun abortSession(reason: String) {
        onGiveUp()
        videoTimeoutGeneration++
        val effect = session.onAbort(clock())
        // The session refuses to abort a picture that is already flowing (it has no steps). Tearing
        // the link down anyway would leave the state machine pushing with nothing behind it, and
        // the next swipe would be swallowed in silence — the exact symptom this abort exists to end.
        if (effect.steps.isEmpty()) return
        log(ClusterFlyLog.abort(reason))
        showWarning(ClusterFlyWarning.pageUnchanged())
        handle(effect)
        stopEverything()
    }

    private fun safetyChanged(block: FlyBlock?) {
        if (block != null) log(ClusterFlyLog.safetyOn(block)) else log(ClusterFlyLog.safetyOff())
        val now = clock()
        handle(session.onSafety(conditions(now), now))
    }

    private fun stopEverything() {
        if (subscribed) {
            subscribed = false
            setMainVideoSubscribed(false)
        }
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE

        /** How long the cluster has to bring its display up after a successful 1885. */
        const val VIDEO_ASK_TIMEOUT_MILLIS = 3_000L

        /**
         * One frame waiting, one frame being decoded. A third is dropped: a backlog here is a
         * software decode running ahead of the picture the driver is looking at.
         */
        const val FRAME_QUEUE_CAPACITY = 1
    }
}
