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

    fun safetyOn(block: FlyBlock): String = "fly.safety on ${block.name}"

    fun safetyOff(): String = "fly.safety off"

    fun mcu(front: Boolean, ok: Boolean): String =
        "fly.mcu 1887 ${if (front) "front" else "back"} $ok"

    /** Binder accepted the 1885 write. This is not the cluster changing page. */
    fun mcu1885(open: Boolean, ok: Boolean): String =
        "fly.mcu 1885 ${if (open) "left" else "right"} $ok"

    /** Binder accepted the 1900 write. That is not the factory taking the navigation mode. */
    fun mcu1900(on: Boolean, ok: Boolean): String =
        "fly.mcu 1900 ${if (on) "on" else "off"} $ok"

    /** Binder accepted the 1899 IPKServer navigation status write. */
    fun mcu1899(active: Boolean, ok: Boolean): String =
        "fly.mcu 1899 ${if (active) "on" else "off"} $ok"

    /** A broadcast went out. `sendBroadcast` has no reply, so this says only that it was sent. */
    fun broadcast(on: Boolean): String =
        "fly.broadcast nav ${if (on) "on" else "off"} sent"

    /** The three-finger broadcast that asks Extra to write 1885. */
    fun pageBroadcast(open: Boolean): String =
        "fly.broadcast page ${if (open) "left" else "right"} sent"

    /** How long since the previous MCU command; `null` before the first one. Not part of the gate. */
    fun mcuGap(millis: Long?): String =
        if (millis == null) "fly.mcu gap none" else "fly.mcu gap $millis"

    /** What Extra's new log lines say about the page the swipe asked for. */
    fun page(proof: PageProof): String = when (proof) {
        PageProof.OPENED -> "fly.extra page open"
        PageProof.CLOSED -> "fly.extra page close"
        PageProof.DENIED -> "fly.extra page denied"
        PageProof.DROPPED -> "fly.extra page dropped"
        PageProof.MISSING -> "fly.extra page miss"
        PageProof.UNCONFIRMED -> "fly.extra page unconfirmed"
    }

    /** A right swipe that Extra's log did not confirm. The close sequence still runs. */
    fun pageCloseMiss(): String = "fly.extra page close miss"

    fun abort(reason: String): String = "fly.abort $reason"
}
