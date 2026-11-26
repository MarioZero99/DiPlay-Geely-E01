package com.shilapi.xcertplay.instrument.fly

/** What gets sent to the head unit's extraservice. The order it is sent in is part of the contract. */
enum class FlyCommand {
    NAV_ON,
    NAV_MODE_ON,
    NAV_FRONT,
    FLY_LEFT,
    FLY_RIGHT,
    NAV_MODE_OFF,
    NAV_OFF,
    NAV_BACK,
}

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
 * Opening turns navigation on and puts the factory's navigation mode on at the same instant, then
 * says navigation is in front one gap later, then flips the page another gap after that. Closing
 * flips the page back first, then — one gap later — takes the navigation mode and flag off at the
 * same instant, then says navigation is in the back one more gap later. Clearing the navigation
 * flag before the swipe makes the head unit drop it, and the cluster is left on the map page with
 * nothing behind the layer. Every pair of MCU writes is at least [MCU_GAP_MILLIS] apart; the
 * broadcast shares its instant with the MCU next to it because it is not an MCU write. No steps are
 * returned when the gate refuses, and the caller may not move its "last command" clock until it has
 * actually sent one.
 */
object ClusterFlySequencer {

    /** Two MCU writes (1900, 1887, 1885) must be at least this far apart. */
    const val MCU_GAP_MILLIS = 1_000L

    fun open(nowMillis: Long, conditions: FlyConditions): List<FlyStep> =
        if (ClusterFlyGate.decide(conditions) != FlyBlock.NONE) {
            emptyList()
        } else {
            listOf(
                FlyStep(FlyCommand.NAV_ON, nowMillis),
                FlyStep(FlyCommand.NAV_MODE_ON, nowMillis),
                FlyStep(FlyCommand.NAV_FRONT, nowMillis + MCU_GAP_MILLIS),
                FlyStep(FlyCommand.FLY_LEFT, nowMillis + MCU_GAP_MILLIS * 2),
            )
        }

    /** When `NAV_ON`, `1900`, `1899`, and `1887` were already pre-armed on stream start, flip immediately. */
    fun openPreArmed(nowMillis: Long, conditions: FlyConditions): List<FlyStep> =
        if (ClusterFlyGate.decide(conditions) != FlyBlock.NONE) {
            emptyList()
        } else {
            listOf(FlyStep(FlyCommand.FLY_LEFT, nowMillis))
        }

    fun close(nowMillis: Long, conditions: FlyConditions): List<FlyStep> =
        if (ClusterFlyGate.decide(conditions) != FlyBlock.NONE) {
            emptyList()
        } else {
            listOf(
                FlyStep(FlyCommand.FLY_RIGHT, nowMillis),
                FlyStep(FlyCommand.NAV_MODE_OFF, nowMillis + MCU_GAP_MILLIS),
                FlyStep(FlyCommand.NAV_OFF, nowMillis + MCU_GAP_MILLIS),
                FlyStep(FlyCommand.NAV_BACK, nowMillis + MCU_GAP_MILLIS * 2),
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
