package com.shilapi.xcertplay.instrument.fly

/** What gets sent to the head unit's extraservice. The order it is sent in is part of the contract. */
enum class FlyCommand { NAV_ON, FLY_LEFT, FLY_RIGHT, NAV_OFF }

/** One command and the moment it is due, counted on the caller's clock. */
data class FlyStep(val command: FlyCommand, val sendAtMillis: Long)

/**
 * Turns a gesture into the pair of commands the factory expects.
 *
 * Opening goes `NAV_ON`, then `FLY_LEFT` 400 ms later. Closing goes `FLY_RIGHT`, then `NAV_OFF` 400 ms
 * later — the reverse order is dropped by the head unit and the cluster is left on the map page with
 * nothing behind the layer. No steps are returned when the gate refuses, and the caller may not move
 * its "last command" clock until it has actually sent one.
 */
object ClusterFlySequencer {

    /** Enough for the extraservice to notice the first broadcast before the second arrives. */
    const val STEP_DELAY_MILLIS = 400L

    fun open(nowMillis: Long, conditions: FlyConditions): List<FlyStep> =
        if (ClusterFlyGate.decide(conditions) != FlyBlock.NONE) {
            emptyList()
        } else {
            listOf(
                FlyStep(FlyCommand.NAV_ON, nowMillis),
                FlyStep(FlyCommand.FLY_LEFT, nowMillis + STEP_DELAY_MILLIS),
            )
        }

    fun close(nowMillis: Long, conditions: FlyConditions): List<FlyStep> =
        if (ClusterFlyGate.decide(conditions) != FlyBlock.NONE) {
            emptyList()
        } else {
            listOf(
                FlyStep(FlyCommand.FLY_RIGHT, nowMillis),
                FlyStep(FlyCommand.NAV_OFF, nowMillis + STEP_DELAY_MILLIS),
            )
        }

    // The broadcasts themselves. Names are the factory's, typos included.

    const val PACKAGE_EXTRASERVICE = "com.neusoft.extraservice"

    /** The receiver that turns KEY_TYPE 10019 into the navigation flag. An implicit broadcast can miss it. */
    const val RECEIVER_NAV_STATE = "com.neusoft.extraservice.receiver.AutoNaviReceiver"

    /** The receiver that sends opcode 1885. It drops the gesture while the navigation flag is off. */
    const val RECEIVER_THREE_FINGER = "com.neusoft.extraservice.receiver.ThreeFingerReceiver"

    const val ACTION_NAV_STATE = "AUTONAVI_STANDARD_BROADCAST_SEND"
    const val EXTRA_KEY_TYPE = "KEY_TYPE"
    const val KEY_TYPE_NAV_STATE = 10019
    const val EXTRA_STATE = "EXTRA_STATE"

    /** Navigation is running, so the cluster is allowed to fly. */
    const val STATE_NAV_ON = 8

    /** Navigation has stopped; the cluster must not be left flying. */
    const val STATE_NAV_OFF = 9

    const val ACTION_THREE_FINGER = "com.neusoft.alfus.three_finger"
    const val EXTRA_DIRECTION = "direction"
    const val DIRECTION_LEFT = "left"
    const val DIRECTION_RIGHT = "right"
}
