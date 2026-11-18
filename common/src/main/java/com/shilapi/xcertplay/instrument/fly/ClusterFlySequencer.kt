package com.shilapi.xcertplay.instrument.fly

/** What gets sent to the head unit's extraservice. The order it is sent in is part of the contract. */
enum class FlyCommand { NAV_ON, NAV_FRONT, FLY_LEFT, FLY_RIGHT, NAV_OFF, NAV_BACK }

/**
 * One command and the moment it is due, counted on the caller's clock.
 *
 * A [retraction] withdraws what a failed attempt already sent (navigation off, the factory's front
 * message back). It is a command like any other on the wire, but it must not restart the
 * one-second interval: that interval protects the *next* attempt, and counting the withdrawal
 * would make the driver wait 1.4 s instead of 1 s before the next swipe is accepted.
 */
data class FlyStep(
    val command: FlyCommand,
    val sendAtMillis: Long,
    val retraction: Boolean = false,
)

/**
 * Turns a gesture into the commands the factory expects.
 *
 * Opening goes `NAV_ON`, then `NAV_FRONT` (the factory's "navigation is in front" message) 400 ms
 * later, then `FLY_LEFT` another 1000 ms after that. Closing goes `FLY_RIGHT`, then `NAV_OFF` 400 ms
 * later, then `NAV_BACK` 1000 ms after the swipe — clearing the navigation flag before the swipe
 * makes the head unit drop it, and the cluster is left on the map page with nothing behind the
 * layer. No steps are returned when the gate refuses, and the caller may not move its "last
 * command" clock until it has actually sent one.
 */
object ClusterFlySequencer {

    /** Enough for the extraservice to notice the first broadcast before the second arrives. */
    const val STEP_DELAY_MILLIS = 400L

    /** Two MCU opcodes, 1887 and 1885, must be at least this far apart. */
    const val MCU_GAP_MILLIS = 1_000L

    fun open(nowMillis: Long, conditions: FlyConditions): List<FlyStep> =
        if (ClusterFlyGate.decide(conditions) != FlyBlock.NONE) {
            emptyList()
        } else {
            listOf(
                FlyStep(FlyCommand.NAV_ON, nowMillis),
                FlyStep(FlyCommand.NAV_FRONT, nowMillis + STEP_DELAY_MILLIS),
                FlyStep(FlyCommand.FLY_LEFT, nowMillis + STEP_DELAY_MILLIS + MCU_GAP_MILLIS),
            )
        }

    fun close(nowMillis: Long, conditions: FlyConditions): List<FlyStep> =
        if (ClusterFlyGate.decide(conditions) != FlyBlock.NONE) {
            emptyList()
        } else {
            listOf(
                FlyStep(FlyCommand.FLY_RIGHT, nowMillis),
                FlyStep(FlyCommand.NAV_OFF, nowMillis + STEP_DELAY_MILLIS),
                FlyStep(FlyCommand.NAV_BACK, nowMillis + MCU_GAP_MILLIS),
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
