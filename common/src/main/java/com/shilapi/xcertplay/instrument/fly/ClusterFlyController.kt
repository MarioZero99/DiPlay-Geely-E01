package com.shilapi.xcertplay.instrument.fly

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.ui.ThreeFingerSwipeDetector.Decision

/** Where the factory commands really go. The Activity sends them; tests record them. */
fun interface FlyCommandSink {
    /** False means the command did not get out. Only a failed [FlyCommand.FLY_LEFT] aborts. */
    fun send(command: FlyCommand): Boolean
}

/**
 * Joins the gesture, the state machine, the cluster's display and the centre screen's picture.
 *
 * The session and everything that steers it sit behind one lock: the Activity moves the centre
 * decoder onto the mirror and reports the first cluster frame on one side, gestures and safety
 * broadcasts arrive on the other. The same decoder feeds both screens, so there is nothing to
 * subscribe to and no second picture to start.
 */
class ClusterFlyController(
    private val commands: FlyCommandSink,
    private val showWarning: (String) -> Unit,
    /** Whether the head unit has finished the settings that make the layer usable. */
    private val provisioned: () -> Boolean = { true },
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val postDelayed: (Long, () -> Unit) -> Unit = { delay, action ->
        Handler(Looper.getMainLooper()).postDelayed(action, delay)
    },
    /** Runs once when the left step has gone out, so the Activity can put its picture on the cluster. */
    private val onLayerOpened: () -> Unit = {},
    /** Posts to the main thread. The decoder runs on the fly thread, the presentation does not. */
    private val onMain: (() -> Unit) -> Unit = { action -> action() },
    /** The Activity takes its cluster window down. Called once per failed picture. */
    private val onGiveUp: () -> Unit = {},
) {

    private val session = ClusterFlySession(log)

    /** The session's phase, for tests and for anything that only reads. */
    val phase: FlyPhase
        get() = session.currentPhase

    /** The one lock. Taken by gestures, safety broadcasts and the mirror's callbacks. */
    private val lock = Any()

    /** Before any command has been sent. Not `0`: uptime can legitimately start there. */
    private var lastCommandAt = NO_TIME

    /** When the last MCU write actually went out. The gap line measures these, not broadcasts. */
    private var lastMcuAt = NO_TIME

    /** The open's three MCU results, `skip` until that hop is reached. Printed once, at 1885. */
    private var chain1900 = FlyChainLog.SKIP
    private var chain1887 = FlyChainLog.SKIP
    private var chain1885 = FlyChainLog.SKIP

    private var radar = false
    private var ecall = false

    /** CarPlay is the navigation picture the cluster's map page is being pointed at. */
    private var carPlayActive = true

    /** A step scheduled before a session ended belongs to the old session and is dropped. */
    private var generation = 0

    /**
     * The wait for the cluster's picture. The three-second one runs from FLY_LEFT; the five-second
     * one replaces it when the cluster's surface arrives, because the window change costs a keyframe.
     */
    private var videoTimeoutGeneration = 0

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
     * The cluster's surface arrived and the window change is under way.
     *
     * Replaces the three-second wait with five: the decoder must be detached, the texture attached,
     * and a keyframe requested, which the phone delivers about two seconds later. Nothing has been
     * drawn at this point, so it is not the moment to say the picture is live.
     */
    fun onMirrorStarted() {
        synchronized(lock) {
            val expected = ++videoTimeoutGeneration
            postDelayed(MIRROR_WAIT_TIMEOUT_MILLIS) {
                synchronized(lock) {
                    if (videoTimeoutGeneration != expected) return@synchronized
                    if (session.currentPhase != FlyPhase.WAITING_FOR_VIDEO) return@synchronized
                    onMirrorFailed()
                }
            }
        }
    }

    /**
     * The first frame really reached the cluster: the picture is live.
     *
     * Idempotent. The five-second wait is cancelled here and nowhere earlier — a successful
     * `configure` only means the decoder found its new window.
     */
    fun onMirrorReady() {
        synchronized(lock) {
            videoTimeoutGeneration++
            if (session.currentPhase == FlyPhase.PUSHING) return
            val effect = session.onDisplayReady(clock())
            if (effect.phase != FlyPhase.PUSHING) return
            handle(effect)
        }
    }

    /**
     * The mirror could not come up, or no cluster frame arrived in time.
     *
     * The layer is up (1885 already went out), so it has to be covered again. The close obeys the
     * factory's one-second interval like any other; [ClusterFlySession.onRight] decides that.
     */
    fun onMirrorFailed() {
        synchronized(lock) {
            videoTimeoutGeneration++
            val phase = session.currentPhase
            if (phase != FlyPhase.WAITING_FOR_VIDEO && phase != FlyPhase.PUSHING) return
            val effect = session.onRight(clock(), conditions(clock()))
            handle(effect)
            onGiveUp()
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
        }
        // The window is still open on the cluster. Take it down; a new session at the same display
        // must be able to attach again.
        onGiveUp()
    }

    /** The Activity is going away: no warning, no commands, no sockets. */
    fun close() {
        synchronized(lock) {
            generation++
            // The waits belong to this Activity; if it is gone, so are the timers.
            videoTimeoutGeneration++
        }
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
                    handle(effect)
                    onGiveUp()
                } else {
                    abortSession(reason)
                }
            }
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
        val now = clock()
        // The gap line is written before the send so it reflects the interval the factory sees. A
        // broadcast is not an MCU write and shares an instant with the 1900 next to it.
        if (isMcu(step.command)) {
            log(ClusterFlyLog.mcuGap(if (lastMcuAt == NO_TIME) null else now - lastMcuAt))
        }
        val ok = commands.send(step.command)
        // A withdrawal does not restart the interval: the driver waits one second from the failure,
        // not one second from the message that takes the failure back.
        if (isMcu(step.command) && !step.retraction) lastMcuAt = now
        if (!step.retraction) lastCommandAt = now
        // The three MCU results, printed once as one line when the last one is known.
        if (!step.retraction) recordChain(step.command, ok)
        // The mode write never reached RpcService. The front and the page have no meaning without
        // it, so they are withdrawn instead of being sent to a factory that ignores them.
        if (step.command == FlyCommand.NAV_MODE_ON && !ok) {
            abortSession("1900 on false")
            return
        }
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

    /**
     * One MCU result of the opening.
     *
     * The summary can only be printed once the 1885 result is known: until then the later hops are
     * still `skip`. A failed 1900 ends the chain on the spot — the front and page are never sent.
     */
    private fun recordChain(command: FlyCommand, ok: Boolean) {
        val value = if (ok) FlyChainLog.TRUE else FlyChainLog.FALSE
        when (command) {
            FlyCommand.NAV_MODE_ON -> {
                chain1900 = value
                chain1887 = FlyChainLog.SKIP
                chain1885 = FlyChainLog.SKIP
                if (!ok) emitChain()
            }
            FlyCommand.NAV_FRONT -> chain1887 = value
            FlyCommand.FLY_LEFT -> {
                chain1885 = value
                emitChain()
            }
            else -> Unit
        }
    }

    private fun emitChain() {
        val complete = chain1900 == FlyChainLog.TRUE &&
            chain1887 == FlyChainLog.TRUE &&
            chain1885 == FlyChainLog.TRUE
        val at = when {
            chain1900 != FlyChainLog.TRUE -> "1900"
            chain1885 != FlyChainLog.TRUE -> "1885"
            chain1887 != FlyChainLog.TRUE -> "1887"
            else -> null
        }
        log(
            FlyChainLog.commands(
                if (complete) FlyChainLog.OK else FlyChainLog.FAIL,
                at,
                chain1900,
                chain1887,
                chain1885,
            ),
        )
        chain1900 = FlyChainLog.SKIP
        chain1887 = FlyChainLog.SKIP
        chain1885 = FlyChainLog.SKIP
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
    }

    /** The three MCU opcodes plus the close side; only the two broadcasts are not. */
    private fun isMcu(command: FlyCommand): Boolean = when (command) {
        FlyCommand.NAV_ON, FlyCommand.NAV_OFF -> false
        else -> true
    }

    private fun safetyChanged(block: FlyBlock?) {
        if (block != null) log(ClusterFlyLog.safetyOn(block)) else log(ClusterFlyLog.safetyOff())
        val now = clock()
        handle(session.onSafety(conditions(now), now))
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE

        /** How long the cluster has to bring its display up after a successful 1885. */
        const val VIDEO_ASK_TIMEOUT_MILLIS = 3_000L

        /** How long the mirror has to put one frame on the cluster after the window changed. */
        const val MIRROR_WAIT_TIMEOUT_MILLIS = 5_000L
    }
}
