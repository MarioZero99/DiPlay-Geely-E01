package com.shilapi.xcertplay.instrument.fly

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
 * Two rules the factory forces:
 *
 *  - the picture is not pushed until the cluster's display is up and the decoder has started;
 *  - once the layer is up it must come down even if the first attempt was too early or a radar
 *    warning interrupted it. A close that cannot go out now is owed and sent when it can, because
 *    the cluster otherwise stays on a frozen map with the navigation card still lit.
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

    /** A close was refused by the car's state and still has to be sent when that state clears. */
    private var closeOwed = false

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
        flyStepAt = steps.first { it.command == FlyCommand.FLY_LEFT }.sendAtMillis
        return effect(steps = steps)
    }

    fun onRight(nowMillis: Long, conditions: FlyConditions): FlyEffect {
        advance(nowMillis)
        lastConditions = conditions
        if (phase == FlyPhase.IDLE) return effect()
        return requestClose(nowMillis, conditions)
    }

    /**
     * The cluster's display is up and the first picture has arrived: the wait is over.
     *
     * Only the wait can turn into a picture. While arming the left step has not gone out, and in
     * every other phase the layer is either already on or already going down.
     */
    fun onDisplayReady(nowMillis: Long): FlyEffect {
        advance(nowMillis)
        if (phase != FlyPhase.WAITING_FOR_VIDEO) return effect()
        val safety = lastConditions?.let { safetyBlock(it) }
        if (safety != null) return effect(warning = safety)
        phase = FlyPhase.PUSHING
        return effect()
    }

    /**
     * A tick. It exists for two things that must happen even when nothing else arrives: moving from
     * [FlyPhase.ARMING] to the wait once the left step has gone out, and finishing a close. Three
     * seconds without a display changes nothing on purpose — the session simply stays waiting, with
     * no decoder created and nothing copied from the centre screen.
     */
    fun onWaitTimeout(nowMillis: Long): FlyEffect {
        advance(nowMillis)
        return effect()
    }

    fun onSafety(conditions: FlyConditions, nowMillis: Long): FlyEffect {
        advance(nowMillis)
        lastConditions = conditions
        val safety = safetyBlock(conditions)

        if (phase == FlyPhase.PUSHING && safety != null) {
            // Stop the picture now; the factory would drop a close sent during the warning, so the
            // close is owed until the warning clears.
            closeOwed = true
            phase = FlyPhase.CLOSING
            closeNavOffAt = nowMillis
            return effect(warning = safety)
        }

        if (phase == FlyPhase.CLOSING && closeOwed && safety == null) {
            return when (val block = closeBlock(conditions)) {
                FlyBlock.NONE -> close(nowMillis, conditions)
                FlyBlock.TOO_SOON -> scheduleClose(nowMillis, conditions)
                else -> effect()
            }
        }
        return effect()
    }

    /** The three states that stop a picture, independent of how recently a command went out. */
    private fun safetyBlock(conditions: FlyConditions): FlyBlock? = when {
        conditions.reversing -> FlyBlock.REVERSING
        conditions.radar -> FlyBlock.RADAR
        conditions.ecall -> FlyBlock.ECALL
        else -> null
    }

    /**
     * The states that stop a *close*. Navigation is deliberately absent: the layer is already up, so
     * the close must go out even if the picture that was flying has just gone away.
     */
    private fun closeBlock(conditions: FlyConditions): FlyBlock = when {
        conditions.millisSinceLastClusterCommand < ClusterFlyGate.MIN_COMMAND_INTERVAL_MILLIS ->
            FlyBlock.TOO_SOON
        conditions.reversing -> FlyBlock.REVERSING
        conditions.radar -> FlyBlock.RADAR
        conditions.ecall -> FlyBlock.ECALL
        else -> FlyBlock.NONE
    }

    fun onCarPlayEnded(nowMillis: Long, conditions: FlyConditions): FlyEffect {
        advance(nowMillis)
        lastConditions = conditions
        return when (phase) {
            FlyPhase.PUSHING, FlyPhase.WAITING_FOR_VIDEO -> requestClose(nowMillis, conditions)

            // The left step may not have gone out yet; nothing on the cluster needs taking down.
            FlyPhase.ARMING -> {
                phase = FlyPhase.IDLE
                effect()
            }

            // The close is owed and this call may be what clears the state that refused it.
            FlyPhase.CLOSING -> if (closeOwed) onSafety(conditions, nowMillis) else effect()

            FlyPhase.IDLE -> effect()
        }
    }

    /**
     * 1885 went out but the cluster never asked for the picture, or 1885 never went out at all.
     *
     * Back to idle at once and the left step's time is cleared: running [advance] first would turn a
     * stale `ARMING` into a wait the next swipe could not escape. The navigation flag and the
     * factory's front message are taken back. No `FLY_RIGHT`: this page change never took effect, so
     * there is nothing to cover. A picture already flowing is left alone; its close is the normal
     * right swipe or the disconnect.
     */
    fun onAbort(nowMillis: Long): FlyEffect {
        if (phase != FlyPhase.ARMING && phase != FlyPhase.WAITING_FOR_VIDEO) {
            return effect()
        }
        phase = FlyPhase.IDLE
        flyStepAt = NO_TIME
        closeNavOffAt = NO_TIME
        closeOwed = false
        return effect(
            steps = listOf(
                FlyStep(FlyCommand.NAV_MODE_OFF, nowMillis, retraction = true),
                FlyStep(FlyCommand.NAV_OFF, nowMillis, retraction = true),
                FlyStep(
                    FlyCommand.NAV_BACK,
                    nowMillis + ClusterFlySequencer.MCU_GAP_MILLIS,
                    retraction = true,
                ),
            ),
        )
    }

    /**
     * Takes the layer down, or promises to.
     *
     * A close that arrives before the one-second interval is not dropped: it is placed at the
     * boundary, which is the earliest moment the factory would accept it. A close under a radar or
     * emergency call stops the picture now and is owed until the state clears.
     */
    private fun requestClose(nowMillis: Long, conditions: FlyConditions): FlyEffect =
        when (val block = closeBlock(conditions)) {
            FlyBlock.NONE -> close(nowMillis, conditions)
            FlyBlock.TOO_SOON -> scheduleClose(nowMillis, conditions)
            else -> {
                closeOwed = true
                phase = FlyPhase.CLOSING
                closeNavOffAt = nowMillis
                effect(warning = block)
            }
        }

    private fun scheduleClose(nowMillis: Long, conditions: FlyConditions): FlyEffect {
        val wait = (ClusterFlyGate.MIN_COMMAND_INTERVAL_MILLIS - conditions.millisSinceLastClusterCommand)
            .coerceAtLeast(0L)
        val at = nowMillis + wait
        // The close list lives in the sequencer only. Moving the clock to the boundary is what makes
        // its gate pass; a safety state that is still set comes back as no steps, and the close stays
        // owed until that state clears.
        val steps = ClusterFlySequencer.close(
            at,
            conditions.copy(
                millisSinceLastClusterCommand = ClusterFlyGate.MIN_COMMAND_INTERVAL_MILLIS,
            ),
        )
        phase = FlyPhase.CLOSING
        closeOwed = steps.isEmpty()
        closeNavOffAt = steps.lastOrNull()?.sendAtMillis ?: at
        return effect(steps = steps)
    }

    private fun close(nowMillis: Long, conditions: FlyConditions): FlyEffect {
        val steps = ClusterFlySequencer.close(nowMillis, conditions)
        phase = FlyPhase.CLOSING
        closeOwed = false
        closeNavOffAt = steps.lastOrNull()?.sendAtMillis ?: nowMillis
        return effect(steps = steps)
    }

    private fun advance(nowMillis: Long) {
        if (phase == FlyPhase.ARMING && nowMillis >= flyStepAt) {
            phase = FlyPhase.WAITING_FOR_VIDEO
        }
        // A close that is still owed must not fall back to idle: nobody has sent it yet.
        if (phase == FlyPhase.CLOSING && !closeOwed && nowMillis >= closeNavOffAt) {
            phase = FlyPhase.IDLE
            closeNavOffAt = NO_TIME
        }
    }

    private fun effect(
        steps: List<FlyStep> = emptyList(),
        warning: FlyBlock? = null,
    ): FlyEffect {
        val effect = FlyEffect(
            phase = phase,
            push = phase == FlyPhase.PUSHING,
            warning = warning,
            steps = steps,
        )
        if (effect.phase != loggedPhase) {
            loggedPhase = effect.phase
            log(ClusterFlyLog.phase(effect.phase))
        }
        if (warning != null) log(ClusterFlyLog.blocked(warning))
        // Commands are logged where they are sent, not here: a step cancelled by a newer gesture
        // must not be recorded as if it had gone out.
        return effect
    }

    private companion object {
        /** Before any step is due; the phase guards keep this from ever being compared meaningfully. */
        const val NO_TIME = Long.MAX_VALUE
    }
}
