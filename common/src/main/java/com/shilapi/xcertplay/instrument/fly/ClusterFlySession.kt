package com.shilapi.xcertplay.instrument.fly

/**
 * What the cluster asked for on its 1028 connection.
 *
 * `OPEN`/`CLOSE` are function 301, the background map the CarPlay picture goes into. The partial
 * pair is function 302, the junction map, which this app can only decline.
 */
enum class VideoAsk { OPEN, CLOSE, PARTIAL_OPEN, PARTIAL_CLOSE, HEARTBEAT, IGNORE }

/** The five states of the layer. Only [PUSHING] has a picture going to the cluster. */
enum class FlyPhase { IDLE, ARMING, WAITING_FOR_VIDEO, PUSHING, CLOSING }

/** Everything one event produced: the new phase, whether a picture should be flowing, what to say. */
data class FlyEffect(
    val phase: FlyPhase,
    val push: Boolean,
    val warning: FlyBlock?,
    val steps: List<FlyStep>,
)

/**
 * The layer's state machine. It sends nothing, opens nothing and starts no thread; it only decides.
 *
 * The rule the factory forces: after the left swipe the cluster is asked for a picture, but the
 * picture is not pushed until the cluster itself opens 1028 and says so. A cluster that never asks —
 * it kept the map page, or it has no video support at all — gets nothing, and nothing is faked for it
 * out of the centre screen.
 */
class ClusterFlySession(
    /** Where the lines go. The controller passes `DiagLog`; tests pass a list. */
    private val log: (String) -> Unit = {},
) {

    private var phase = FlyPhase.IDLE
    private var loggedPhase = FlyPhase.IDLE

    /** When the `FLY_LEFT` / `FLY_RIGHT` step is due; the wait for a video only starts after it. */
    private var flyStepAt = NO_TIME

    /** When the last close command is due, after which the layer is back to idle. */
    private var closeNavOffAt = NO_TIME

    /** The most recent complete picture of the car, for events that have no conditions of their own. */
    private var lastConditions: FlyConditions? = null

    /** What the layer is doing right now. Read-only: only the session's own events move it. */
    val currentPhase: FlyPhase
        get() = phase

    fun onLeft(nowMillis: Long, conditions: FlyConditions): FlyEffect {
        advance(nowMillis)
        lastConditions = conditions
        if (phase != FlyPhase.IDLE) return effect()

        val block = ClusterFlyGate.decide(conditions)
        if (block != FlyBlock.NONE) return effect(warning = block)

        val steps = ClusterFlySequencer.open(nowMillis, conditions)
        phase = FlyPhase.ARMING
        flyStepAt = nowMillis + ClusterFlySequencer.STEP_DELAY_MILLIS
        return effect(steps = steps)
    }

    fun onRight(nowMillis: Long, conditions: FlyConditions): FlyEffect {
        advance(nowMillis)
        lastConditions = conditions
        if (phase == FlyPhase.IDLE) return effect()

        val block = ClusterFlyGate.decide(conditions)
        if (block != FlyBlock.NONE) {
            // The factory drops the close commands in these states too. The only thing left to do is
            // to stop our own picture; the cluster's layer is the factory's to take down.
            phase = FlyPhase.IDLE
            return effect(warning = block)
        }
        return close(nowMillis, conditions)
    }

    /**
     * The cluster's ask. [conditions] is the car as it is *now*: a picture must not start under a
     * radar warning, and a close is a command like any other, so the one-second interval applies to
     * it too. Passing null falls back to the picture last stored by a gesture.
     */
    fun onVideoAsk(ask: VideoAsk, nowMillis: Long, conditions: FlyConditions? = null): FlyEffect {
        advance(nowMillis)
        when (ask) {
            VideoAsk.OPEN -> if (phase == FlyPhase.WAITING_FOR_VIDEO) {
                val current = conditions ?: lastConditions
                val safety = current?.let { safetyBlock(it) }
                if (safety != null) return effect(warning = safety)
                phase = FlyPhase.PUSHING
            }

            VideoAsk.CLOSE -> if (phase == FlyPhase.PUSHING) {
                val current = conditions ?: lastConditions
                val block = current?.let { ClusterFlyGate.decide(it) } ?: FlyBlock.NONE
                if (current != null && block == FlyBlock.NONE) return close(nowMillis, current)
                // The factory would drop the close commands in this state; all we can do is stop.
                phase = FlyPhase.IDLE
                return effect(warning = block.takeIf { it != FlyBlock.NONE })
            }

            VideoAsk.PARTIAL_OPEN, VideoAsk.PARTIAL_CLOSE,
            VideoAsk.HEARTBEAT, VideoAsk.IGNORE -> Unit
        }
        return effect()
    }

    /**
     * A tick. It exists for two things that must happen even when nothing else arrives: moving from
     * [FlyPhase.ARMING] to [FlyPhase.WAITING_FOR_VIDEO] once the left step has gone out, and finishing
     * a close. Three seconds without an `OPEN` changes nothing on purpose — the session simply stays
     * waiting, with no encoder created and nothing copied from the centre screen.
     */
    fun onWaitTimeout(nowMillis: Long): FlyEffect {
        advance(nowMillis)
        return effect()
    }

    fun onSafety(conditions: FlyConditions, nowMillis: Long): FlyEffect {
        advance(nowMillis)
        lastConditions = conditions
        if (phase != FlyPhase.PUSHING) return effect()

        // Not the gate: between commands the gate answers TOO_SOON, and a radar warning that happens
        // to arrive half a second after a swipe is still a radar warning.
        val block = safetyBlock(conditions) ?: return effect()

        // The factory discards the close commands in these states, so there are none to send.
        phase = FlyPhase.CLOSING
        closeNavOffAt = nowMillis
        return effect(warning = block)
    }

    /** The three states that stop a picture, independent of how recently a command went out. */
    private fun safetyBlock(conditions: FlyConditions): FlyBlock? = when {
        conditions.reversing -> FlyBlock.REVERSING
        conditions.radar -> FlyBlock.RADAR
        conditions.ecall -> FlyBlock.ECALL
        else -> null
    }

    fun onCarPlayEnded(nowMillis: Long, conditions: FlyConditions): FlyEffect {
        advance(nowMillis)
        lastConditions = conditions
        return when (phase) {
            FlyPhase.PUSHING, FlyPhase.WAITING_FOR_VIDEO -> {
                val block = ClusterFlyGate.decide(conditions)
                if (block != FlyBlock.NONE) {
                    phase = FlyPhase.IDLE
                    effect(warning = block)
                } else {
                    close(nowMillis, conditions)
                }
            }
            // The left step may not have gone out yet; nothing on the cluster needs taking down.
            FlyPhase.ARMING -> {
                phase = FlyPhase.IDLE
                effect()
            }
            else -> effect()
        }
    }

    private fun close(nowMillis: Long, conditions: FlyConditions): FlyEffect {
        val steps = ClusterFlySequencer.close(nowMillis, conditions)
        phase = FlyPhase.CLOSING
        closeNavOffAt = steps.lastOrNull()?.sendAtMillis ?: nowMillis
        return effect(steps = steps)
    }

    private fun advance(nowMillis: Long) {
        if (phase == FlyPhase.ARMING && nowMillis >= flyStepAt) phase = FlyPhase.WAITING_FOR_VIDEO
        if (phase == FlyPhase.CLOSING && nowMillis >= closeNavOffAt) {
            phase = FlyPhase.IDLE
            closeNavOffAt = NO_TIME
        }
    }

    private fun effect(
        steps: List<FlyStep> = emptyList(),
        warning: FlyBlock? = null,
    ): FlyEffect {
        val effect = FlyEffect(phase = phase, push = phase == FlyPhase.PUSHING, warning = warning, steps = steps)
        if (effect.phase != loggedPhase) {
            loggedPhase = effect.phase
            log(ClusterFlyLog.phase(effect.phase))
        }
        if (warning != null) log(ClusterFlyLog.blocked(warning))
        steps.forEach { log(ClusterFlyLog.command(it.command)) }
        return effect
    }

    private companion object {
        /** Before any step is due; the phase guards keep this from ever being compared meaningfully. */
        const val NO_TIME = Long.MAX_VALUE
    }
}
