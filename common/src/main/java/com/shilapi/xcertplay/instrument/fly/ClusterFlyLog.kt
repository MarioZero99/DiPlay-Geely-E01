package com.shilapi.xcertplay.instrument.fly

/** The four things that can happen to the cluster's 1028 socket, as log words. */
enum class TcpEvent { LISTEN, ACCEPT, CLOSED, BUSY }

/** The six answers we can send. */
enum class FlyReply { HEARTBEAT, READY, START, STOP, REFUSED }

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

    fun tcp(event: TcpEvent): String = "fly.tcp ${event.name.lowercase()}"

    fun ask(ask: VideoAsk): String = "fly.ask ${ask.name}"

    fun reply(reply: FlyReply): String = "fly.reply ${reply.name.lowercase()}"

    fun pushStart(mode: PushMode): String = "fly.push start ${mode.name.lowercase()}"

    /** The phone's H.264 profile, once per value: 66 is Baseline, anything else has to be re-encoded. */
    fun profile(profileIdc: Int): String = "fly.profile $profileIdc"

    fun pushStop(): String = "fly.push stop"

    fun stat(packets: Long, bytes: Long, drops: Long, fps: Int, keyframeAgeMs: Long): String =
        "fly.stat packets=$packets bytes=$bytes drops=$drops fps=$fps keyframeAgeMs=$keyframeAgeMs"

    fun safetyOn(block: FlyBlock): String = "fly.safety on ${block.name}"

    fun safetyOff(): String = "fly.safety off"
}
