package com.shilapi.xcertplay.instrument.fly

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.ui.ThreeFingerSwipeDetector.Decision

/** Where the two factory broadcasts really go. The Activity posts them; tests record them. */
fun interface FlyCommandSink {
    fun send(command: FlyCommand)
}

/**
 * Joins the gesture, the state machine, the cluster's socket and the centre screen's picture.
 *
 * The session and everything that steers it sit behind one lock: the link's reader thread answers
 * the cluster on one side, the main thread takes gestures and safety broadcasts on the other, and
 * both move the same state machine. The picture itself is subscribed only while the session says it
 * is pushing, so a session with no cluster watching costs the decoder nothing.
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
    linkFactory: (() -> ClusterFlyLink)? = null,
) {

    private val session = ClusterFlySession(log)

    private val link: ClusterFlyLink? = linkFactory?.invoke()?.apply {
        onPacket = { bytes -> onPacket(bytes) }
    }

    /** The one lock. Taken by gestures, safety broadcasts, the reader thread and posted steps. */
    private val lock = Any()

    /** Before any command has been sent. Not `0`: uptime can legitimately start there. */
    private var lastCommandAt = NO_TIME

    private var radar = false
    private var ecall = false

    /** CarPlay is the navigation picture the cluster's map page is being pointed at. */
    private var carPlayActive = true

    private var subscribed = false
    private var statScheduled = false

    /** A step scheduled before a session ended belongs to the old session and is dropped. */
    private var generation = 0

    private var statPackets = 0L
    private var statBytes = 0L
    private var statDrops = 0L
    private var statFrames = 0
    private var lastKeyframeAt = NO_TIME

    init {
        // The push policy logs through this controller, so its lines land in the same session log.
        ClusterFlyPush.log = log
    }

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
    }

    /** The Activity is going away: no warning, no commands, no sockets. */
    fun close() {
        synchronized(lock) {
            generation++
            stopEverything()
        }
    }

    /**
     * One unit of the centre screen's video, already off the media thread.
     *
     * The sink's config callback carries an avcC record; it is rewritten to Annex-B here, both so
     * the policy can read its profile and so the cluster is never handed a length-prefixed blob as
     * if it were a NAL. Only the main screen's 110 is ever considered, and only while pushing.
     */
    fun onMainVideo(type: Int, bytes: ByteArray) {
        synchronized(lock) {
            val now = clock()
            val phase = session.currentPhase
            val unit = if (ClusterFlyPush.isConfig(bytes)) {
                ClusterFlyPush.annexB(bytes) ?: return
            } else {
                bytes
            }

            val decision = ClusterFlyPush.onFrame(phase, type, unit, now)
            if (phase != FlyPhase.PUSHING) return

            if (!decision.send) {
                if (decision.dropReason != "idle") statDrops++
                return
            }
            if (ClusterFlyPush.isKeyframe(unit)) lastKeyframeAt = now
            if (link?.send(unit) == true) {
                statPackets++
                statBytes += unit.size
                statFrames++
            } else {
                statDrops++
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

        if (effect.push != subscribed) {
            subscribed = effect.push
            setMainVideoSubscribed(effect.push)
            if (effect.push) {
                if (!statScheduled) {
                    statScheduled = true
                    scheduleStat()
                }
            } else {
                statScheduled = false
                ClusterFlyPush.release()
            }
        }

        if (effect.phase == FlyPhase.ARMING || effect.phase == FlyPhase.WAITING_FOR_VIDEO) {
            link?.start()
        }

        for (step in effect.steps) {
            val delay = (step.sendAtMillis - clock()).coerceAtLeast(0L)
            if (delay == 0L) {
                sendStep(step)
            } else {
                val expected = generation
                postDelayed(delay) {
                    synchronized(lock) {
                        if (generation == expected) sendStep(step)
                    }
                }
            }
        }
    }

    private fun sendStep(step: FlyStep) {
        commands.send(step.command)
        lastCommandAt = clock()
        if (step.command == FlyCommand.FLY_LEFT) {
            // The left step has gone out; from here the cluster may ask for the picture.
            handle(session.onWaitTimeout(clock()))
        }
    }

    /**
     * Answers one 24-byte ask. Runs on the link's reader thread.
     *
     * For an open, the replies are returned to the link first and the subscription is posted: the
     * cluster must know a picture is coming before the first packet does. For a close it is the
     * other way round — the picture is stopped here, and only then is STOP written back.
     */
    fun onPacket(bytes: ByteArray): List<ByteArray> {
        val ask = ClusterFlyProtocol.parse(bytes)
        log(ClusterFlyLog.ask(ask))
        if (ask == VideoAsk.IGNORE) return emptyList()

        synchronized(lock) {
            val now = clock()
            val conditions = conditions(now)
            return when (ask) {
                VideoAsk.IGNORE -> emptyList()

                VideoAsk.HEARTBEAT -> listOf(reply(FlyReply.HEARTBEAT, ClusterFlyProtocol.heartbeat()))

                VideoAsk.OPEN -> {
                    val effect = session.onVideoAsk(VideoAsk.OPEN, now, conditions)
                    val replies = if (effect.push) {
                        listOf(
                            reply(FlyReply.READY, ClusterFlyProtocol.videoReady()),
                            reply(FlyReply.START, ClusterFlyProtocol.videoStart()),
                        )
                    } else {
                        listOf(reply(FlyReply.REFUSED, ClusterFlyProtocol.videoRefused()))
                    }
                    postDelayed(0L) {
                        synchronized(lock) { handle(effect) }
                    }
                    replies
                }

                VideoAsk.CLOSE -> {
                    val wasPushing = session.currentPhase == FlyPhase.PUSHING
                    val effect = session.onVideoAsk(VideoAsk.CLOSE, now, conditions)
                    if (wasPushing) {
                        // The driver sees the picture stop before the cluster is told it stopped.
                        if (subscribed) {
                            subscribed = false
                            setMainVideoSubscribed(false)
                        }
                        ClusterFlyPush.release()
                    }
                    postDelayed(0L) {
                        synchronized(lock) { handle(effect) }
                    }
                    if (wasPushing) {
                        listOf(reply(FlyReply.STOP, ClusterFlyProtocol.videoStop()))
                    } else {
                        listOf(reply(FlyReply.REFUSED, ClusterFlyProtocol.videoRefused()))
                    }
                }
            }
        }
    }

    private fun reply(kind: FlyReply, bytes: ByteArray): ByteArray {
        log(ClusterFlyLog.reply(kind))
        return bytes
    }

    private fun safetyChanged(block: FlyBlock?) {
        if (block != null) log(ClusterFlyLog.safetyOn(block)) else log(ClusterFlyLog.safetyOff())
        val now = clock()
        handle(session.onSafety(conditions(now), now))
    }

    private fun stopEverything() {
        statScheduled = false
        if (subscribed) {
            subscribed = false
            setMainVideoSubscribed(false)
        }
        ClusterFlyPush.release()
        link?.close()
    }

    private fun scheduleStat() {
        postDelayed(STAT_INTERVAL_MILLIS) {
            synchronized(lock) { emitStat() }
        }
    }

    private fun emitStat() {
        if (!statScheduled || !subscribed) {
            statScheduled = false
            return
        }
        val fps = statFrames / (STAT_INTERVAL_MILLIS / 1_000L).toInt()
        val keyframeAge = if (lastKeyframeAt == NO_TIME) -1L else clock() - lastKeyframeAt
        log(
            ClusterFlyLog.stat(
                packets = statPackets,
                bytes = statBytes,
                drops = statDrops,
                fps = fps,
                keyframeAgeMs = keyframeAge,
            ),
        )
        statPackets = 0
        statBytes = 0
        statDrops = 0
        statFrames = 0
        scheduleStat()
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
        const val STAT_INTERVAL_MILLIS = 5_000L
    }
}
