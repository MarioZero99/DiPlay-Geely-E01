// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui

/**
 * The three-finger swipe-down that opens DiPlay from the CarPlay picture.
 *
 * Split out of the host Activity because the interesting part is not a view: it is the question of
 * which events belong to the gesture and which to CarPlay. As a plain state machine it can be driven
 * with numbers in a test, which is the only way the edge cases — a finger that lands late, a swipe
 * that ends on the lift instead of on a move, the events that arrive after the settings page has
 * already been asked for — get exercised at all.
 *
 * Three states:
 *
 *  - **IDLE** everything goes to CarPlay. The first finger's `DOWN` is forwarded as it always was;
 *    CarPlay is only told otherwise once the third finger has landed.
 *  - **TRACKING** the gesture owns the stream. Arming sends a touch cancel, so the two-finger scroll
 *    CarPlay was starting is taken back rather than left half-done, and nothing else is forwarded.
 *  - **DRAINING** the gesture has fired and the driver is on their way to the settings page, but
 *    three fingers are still on the glass. Whatever is left of that touch stream is swallowed; a
 *    forwarded `MOVE` with no `DOWN` in front of it is an orphan the phone would have to guess at.
 *
 * The arm has to happen quickly: three fingers that land as three separate, leisurely presses are a
 * rest, not a swipe, and the old detector would have taken a thumb coming to rest beside two parked
 * fingers and opened the settings under it.
 */
class ThreeFingerSwipeDetector(
    /** How far the centroid has to travel downwards before the swipe counts. */
    private val distance: Float,
    /** How much more vertical than horizontal that travel has to be. */
    private val directionRatio: Float,
    /** How long the three fingers may take to arrive, counted from the first one's `DOWN`. */
    private val armWindowMillis: Long,
) {

    /** What the host should do with the event it just handed over. */
    enum class Decision {
        /** Send it to CarPlay. */
        PASS,

        /** This is the third finger: cancel CarPlay's touch, then consume. */
        ARM,

        /** The gesture owns this event; CarPlay must not see it. */
        CONSUME,

        /** The swipe completed: open the settings, and keep consuming until every finger is up. */
        TRIGGER,
    }

    /** The six `MotionEvent` actions this machine distinguishes, without an Android dependency. */
    enum class Phase { DOWN, MOVE, POINTER_DOWN, POINTER_UP, UP, CANCEL }

    private enum class State { IDLE, TRACKING, DRAINING }

    private var state = State.IDLE
    private var firstDownAt = NO_DOWN
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** A touch sequence that never ended: called when the window loses the screen. */
    fun reset() {
        state = State.IDLE
        firstDownAt = NO_DOWN
    }

    fun onTouch(
        phase: Phase,
        pointerCount: Int,
        eventTime: Long,
        centroidX: Float,
        centroidY: Float,
    ): Decision {
        lastX = centroidX
        lastY = centroidY
        return when (state) {
            State.IDLE -> {
                if (phase == Phase.DOWN) {
                    firstDownAt = eventTime
                    return Decision.PASS
                }
                if (
                    phase == Phase.POINTER_DOWN &&
                    pointerCount >= FINGERS &&
                    (firstDownAt == NO_DOWN || eventTime - firstDownAt <= armWindowMillis)
                ) {
                    state = State.TRACKING
                    startX = centroidX
                    startY = centroidY
                    return Decision.ARM
                }
                return Decision.PASS
            }

            State.TRACKING -> when (phase) {
                Phase.MOVE ->
                    if (travelledFarEnough()) {
                        state = State.DRAINING
                        Decision.TRIGGER
                    } else {
                        Decision.CONSUME
                    }

                // The last event of a fast flick is often the lift, with the move before it still
                // short of the threshold. The lift carries the finger's final position, so it is
                // checked too instead of being thrown away.
                Phase.POINTER_UP -> {
                    val fired = pointerCount >= FINGERS && travelledFarEnough()
                    state = State.DRAINING
                    if (fired) Decision.TRIGGER else Decision.CONSUME
                }

                Phase.UP, Phase.CANCEL -> {
                    state = State.IDLE
                    Decision.CONSUME
                }

                Phase.POINTER_DOWN, Phase.DOWN -> Decision.CONSUME
            }

            State.DRAINING -> {
                // A `DOWN` here can only be the start of a new sequence — the old one ended without
                // the host seeing its `UP`. Reset rather than swallow a fresh tap.
                if (phase == Phase.DOWN) {
                    state = State.IDLE
                    firstDownAt = eventTime
                    return Decision.PASS
                }
                if (phase == Phase.UP || phase == Phase.CANCEL) state = State.IDLE
                Decision.CONSUME
            }
        }
    }

    private fun travelledFarEnough(): Boolean {
        val down = lastY - startY
        val across = kotlin.math.abs(lastX - startX)
        return down >= distance && down >= across * directionRatio
    }

    private companion object {
        /** Three fingers, and that is the whole point: CarPlay itself takes two contacts. */
        const val FINGERS = 3

        /**
         * No `DOWN` seen yet. Not `0`: event times are the device's uptime in milliseconds, and a
         * test — or a head unit that has just booted — can legitimately start at zero, which would
         * then read as "no down" and arm a third finger that landed a second late.
         */
        const val NO_DOWN = Long.MIN_VALUE
    }
}
