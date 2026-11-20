package com.shilapi.xcertplay.instrument.fly

/**
 * The fly feature's lines in the session log.
 *
 * A fixed prefix per kind, fields separated by spaces, and never any of the picture: no NAL, no RTP
 * body, no address. On a car with no adb these lines are the only record of why the layer did or did
 * not come up, so the words are stable and the caller only supplies numbers.
 */
object ClusterFlyLog {

    const val TAG = "xcertplay-fly"

    fun gesture(direction: FlyDirection): String = "fly.gesture ${direction.name.lowercase()}"

    fun gestureDown(): String = "fly.gesture down"

    fun blocked(block: FlyBlock): String = "fly.blocked ${block.name}"

    fun command(command: FlyCommand): String = "fly.command ${command.name}"

    fun phase(phase: FlyPhase): String = "fly.phase ${phase.name}"

    fun stat(packets: Long, bytes: Long, drops: Long, fps: Int, keyframeAgeMs: Long): String =
        "fly.stat packets=$packets bytes=$bytes drops=$drops fps=$fps keyframeAgeMs=$keyframeAgeMs"

    fun safetyOn(block: FlyBlock): String = "fly.safety on ${block.name}"

    fun safetyOff(): String = "fly.safety off"

    fun mcu(front: Boolean, ok: Boolean): String =
        "fly.mcu 1887 ${if (front) "front" else "back"} $ok"

    /** Binder accepted the 1885 write. This is not the cluster changing page. */
    fun mcu1885(open: Boolean, ok: Boolean): String =
        "fly.mcu 1885 ${if (open) "left" else "right"} $ok"

    fun abort(reason: String): String = "fly.abort $reason"
}
