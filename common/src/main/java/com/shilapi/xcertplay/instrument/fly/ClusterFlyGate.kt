package com.shilapi.xcertplay.instrument.fly

/**
 * Where the driver's three fingers went. Downward keeps its own meaning (the quick bar); only the
 * sideways directions reach the cluster.
 */
enum class FlyDirection { LEFT, RIGHT }

/** Why a cluster command was not sent. `NONE` is the only value that lets one through. */
enum class FlyBlock {
    NONE,
    NOT_NAVIGATING,
    NOT_PROVISIONED,
    REVERSING,
    RADAR,
    ECALL,
    TOO_SOON,
}

/**
 * Everything the gate needs, and nothing it cannot know.
 *
 * [reversing] stays false when DiPlay cannot see it — the head unit keeps the reverse gear and the
 * panoramic camera in a system service ordinary apps are not given. The command is sent anyway and
 * the factory drops it; refusing to send would turn an unknowable state into a dead button.
 */
data class FlyConditions(
    val navigationArmed: Boolean,
    val deviceProvisioned: Boolean,
    val reversing: Boolean,
    val radar: Boolean,
    val ecall: Boolean,
    val millisSinceLastClusterCommand: Long,
)

/**
 * The one place that decides whether a command may go to the instrument cluster.
 *
 * Order matters: the cheapest and most certain refusals come first. A burst is refused before
 * anything else so that two quick swipes cannot produce two command pairs even if one of the slower
 * states happens to be unknown.
 */
object ClusterFlyGate {

    /** Two commands closer together than this are dropped by the factory, so we do not send them. */
    const val MIN_COMMAND_INTERVAL_MILLIS = 1_000L

    fun decide(conditions: FlyConditions): FlyBlock = when {
        conditions.millisSinceLastClusterCommand < MIN_COMMAND_INTERVAL_MILLIS -> FlyBlock.TOO_SOON
        !conditions.navigationArmed -> FlyBlock.NOT_NAVIGATING
        !conditions.deviceProvisioned -> FlyBlock.NOT_PROVISIONED
        conditions.reversing -> FlyBlock.REVERSING
        conditions.radar -> FlyBlock.RADAR
        conditions.ecall -> FlyBlock.ECALL
        else -> FlyBlock.NONE
    }
}
