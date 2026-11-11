package com.shilapi.xcertplay.instrument.fly

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.ui.ThreeFingerSwipeDetector.Decision
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    frameExecutor: ExecutorService? = null,
) {

    private val session = ClusterFlySession(log)

    private val link: ClusterFlyLink? = linkFactory?.invoke()?.apply {
        onPacket = { bytes -> onPacket(bytes) }
    }

    /**
     * The fly thread. CarPlay's media thread hands over a frame and returns; everything that could
     * block — the policy, the encoder boundary, the UDP send — happens here.
     *
     * The queue is bounded and its rejection is a counter, never a wait: a head unit with 26 MB free
     * cannot afford the cluster layer to slow the picture the driver is actually looking at.
     */
    private val frameExecutor: ExecutorService = frameExecutor ?: ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(FRAME_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "cluster-fly-frames").apply { isDaemon = true } },
    )

    /** The one lock. Taken by gestures, safety broadcasts, the reader thread and posted steps. */
    private val lock = Any()

    /** Guards the push policy's own state, which the fly thread and a teardown both touch. */
    private val pushLock = Any()

    /** Before any command has been sent. Not `0`: uptime can legitimately start there. */
    private var lastCommandAt = NO_TIME

    private var radar = false
    private var ecall = false

    /** CarPlay is the navigation picture the cluster's map page is being pointed at. */
    private var carPlayActive = true

    private var subscribed = false
    private var statScheduled = false

    /** The profile warning is said once per layer, not per frame. */
    private var profileWarned = false

    /** A step scheduled before a session ended belongs to the old session and is dropped. */
    private var generation = 0

    /** Set on the fly thread, once. The media thread never pays for the priority change. */
    private var background = false

    private val statPackets = AtomicLong()
    private val statBytes = AtomicLong()
    private val statDrops = AtomicLong()
    private val statFrames = AtomicInteger()
    private val lastKeyframeAt = AtomicLong(NO_TIME)

    init {
        // The push policy logs through this controller, so its lines land in the same session log.
        ClusterFlyPush.reset()
        ClusterFlyPush.log = log
        // Only a controller with a real link builds a codec. Tests leave the factory alone.
        // The decoder inside is the software one, so this does not take the centre screen's decoder.
        if (linkFactory != null) {
            ClusterFlyPush.reencoderFactory = { config ->
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                    null
                } else {
                    val created = ClusterFlyReencoderMediaCodec(config) { unit -> sendEncoded(unit) }
                    if (created.ready) created else {
                        created.close()
                        null
                    }
                }
            }
        }
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
        // The queue may still hold frames; they belong to a dead layer and are not worth sending.
        frameExecutor.shutdownNow()
    }

    /**
     * Hands one unit of the centre screen's video to the fly thread and returns.
     *
     * This is the only thing the CarPlay media thread does for the cluster: one queue offer, no copy,
     * no allocation of its own. The decoder keeps the same array in its own queue (`submit` is an
     * offer too), so the reference stays valid; the fly thread does the rest.
     */
    fun onMainVideo(type: Int, bytes: ByteArray) {
        try {
            frameExecutor.execute { processFrame(type, bytes) }
        } catch (_: RejectedExecutionException) {
            // The queue is full or the layer is closed: one countable drop, never a wait.
            // The GOP is now missing a frame, so the fly thread waits for the next IDR.
            statDrops.incrementAndGet()
            ClusterFlyPush.noteDropped()
        }
    }

    /**
     * The fly thread's work for one unit.
     *
     * The sink's config callback carries an avcC record; it is rewritten to Annex-B here, both so
     * the policy can read its profile and so the cluster is never handed a length-prefixed blob as
     * if it were a NAL. Only the main screen's 110 is ever considered, and only while pushing.
     */
    private fun processFrame(type: Int, bytes: ByteArray) {
        if (!background) {
            background = true
            // Below the UI and the media thread. The software decode uses whatever core is left.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            }
        }
        val now = clock()
        val phase: FlyPhase = synchronized(lock) { session.currentPhase }
        val unit = if (ClusterFlyPush.isConfig(bytes)) {
            ClusterFlyPush.annexB(bytes) ?: return
        } else {
            bytes
        }

        val decision = synchronized(pushLock) { ClusterFlyPush.onFrame(phase, type, unit, now) }
        if (phase != FlyPhase.PUSHING) return

        if (!decision.send) {
            // "pace" is a frame held for the 5 fps cap. "encoder" means the re-encoder never
            // started, which the driver can see. A consumed parameter set is neither.
            if (decision.dropReason == "encoder") warnUnsupportedPicture()
            if (decision.dropReason != null && decision.dropReason != "idle") statDrops.incrementAndGet()
            return
        }
        // In re-encode mode this unit has only been accepted by the encoder; its own output is what
        // goes on the wire, through [sendEncoded]. Sending the original here would hand the cluster
        // a higher-profile slice it cannot decode.
        if (decision.mode != PushMode.FORWARD) return

        if (ClusterFlyPush.isKeyframe(unit)) lastKeyframeAt.set(now)
        if (link?.send(unit) == true) {
            statPackets.incrementAndGet()
            statBytes.addAndGet(unit.size.toLong())
            statFrames.incrementAndGet()
        } else {
            statDrops.incrementAndGet()
        }
    }

    /** Said once per layer, on the main thread: the phone's picture is not the profile the cluster takes. */
    private fun warnUnsupportedPicture() {
        if (profileWarned) return
        profileWarned = true
        postDelayed(0L) {
            synchronized(lock) { showWarning(ProfileWarning) }
        }
    }

    private fun sendEncoded(unit: ByteArray) {
        if (link?.send(unit) == true) {
            statPackets.incrementAndGet()
            statBytes.addAndGet(unit.size.toLong())
            statFrames.incrementAndGet()
        } else {
            statDrops.incrementAndGet()
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
                synchronized(pushLock) { ClusterFlyPush.release() }
            }
        }

        if (effect.phase == FlyPhase.ARMING || effect.phase == FlyPhase.WAITING_FOR_VIDEO) {
            // Listening starts with the opening pair, not with the wait: the cluster may ask inside
            // those 400 ms and the ask is remembered rather than refused.
            link?.start()
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
        commands.send(step.command)
        lastCommandAt = clock()
        if (step.command == FlyCommand.FLY_LEFT) {
            // The left step has gone out; from here the cluster may ask for the picture. An ask that
            // arrived while arming is answered now, before the subscription makes frames flow.
            val effect = session.onWaitTimeout(clock())
            if (effect.push && session.takePendingOpen()) {
                val replies = listOf(
                    reply(FlyReply.READY, ClusterFlyProtocol.videoReady()),
                    reply(FlyReply.START, ClusterFlyProtocol.videoStart()),
                )
                link?.reply(replies)
            }
            handle(effect)
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

                // The cluster's own heartbeat: the factory reads it and writes nothing back. Ours is
                // sent on the link's three-second timer, not as an answer.
                VideoAsk.HEARTBEAT -> emptyList()

                VideoAsk.PARTIAL_OPEN, VideoAsk.PARTIAL_CLOSE ->
                    listOf(reply(FlyReply.REFUSED, ClusterFlyProtocol.partialRefused()))

                VideoAsk.OPEN -> {
                    val effect = session.onVideoAsk(VideoAsk.OPEN, now, conditions)
                    val replies = when {
                        effect.push -> listOf(
                            reply(FlyReply.READY, ClusterFlyProtocol.videoReady()),
                            reply(FlyReply.START, ClusterFlyProtocol.videoStart()),
                        )
                        // Asked during the 400 ms before the left step. A refusal here is the
                        // answer the cluster keeps; the ready and start go out with that step.
                        effect.holdOpen -> emptyList()
                        else -> listOf(reply(FlyReply.REFUSED, ClusterFlyProtocol.videoRefused()))
                    }
                    // The held ask is answered from the left step. Handling this effect later
                    // would run after the picture had started and turn the subscription back off.
                    if (!effect.holdOpen) {
                        postDelayed(0L) {
                            synchronized(lock) { handle(effect) }
                        }
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
                        synchronized(pushLock) { ClusterFlyPush.release() }
                    }
                    postDelayed(0L) {
                        synchronized(lock) { handle(effect) }
                    }
                    // The factory acknowledges the close whether or not it was streaming, then
                    // sends the stream stop.
                    listOf(
                        reply(FlyReply.READY, ClusterFlyProtocol.videoReady()),
                        reply(FlyReply.STOP, ClusterFlyProtocol.videoStop()),
                    )
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
        profileWarned = false
        if (subscribed) {
            subscribed = false
            setMainVideoSubscribed(false)
        }
        synchronized(pushLock) { ClusterFlyPush.release() }
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
        val frames = statFrames.getAndSet(0)
        val fps = frames / (STAT_INTERVAL_MILLIS / 1_000L).toInt()
        val keyframeAt = lastKeyframeAt.get()
        val keyframeAge = if (keyframeAt == NO_TIME) -1L else clock() - keyframeAt
        log(
            ClusterFlyLog.stat(
                packets = statPackets.getAndSet(0),
                bytes = statBytes.getAndSet(0),
                drops = statDrops.getAndSet(0),
                fps = fps,
                keyframeAgeMs = keyframeAge,
            ),
        )
        scheduleStat()
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
        const val STAT_INTERVAL_MILLIS = 5_000L

        /**
         * One frame waiting, one frame being decoded. A third is dropped: a backlog here is a
         * software decode running ahead of the picture the driver is looking at.
         */
        const val FRAME_QUEUE_CAPACITY = 1

        const val ProfileWarning = "手机画面格式暂不支持飞屏"
    }
}
