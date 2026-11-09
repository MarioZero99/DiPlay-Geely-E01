package com.shilapi.xcertplay.instrument.fly

/** How a frame reaches the cluster: untouched, or through our own encoder. */
enum class PushMode { FORWARD, REENCODE }

/**
 * What to do with one unit of the centre screen's video.
 *
 * [dropReason] is a fixed word for the log — `idle`, `profile`, `encoder` — never a byte count and
 * never a packet body.
 */
data class PushDecision(
    val send: Boolean,
    val mode: PushMode,
    val dropReason: String?,
)

/**
 * The encoder boundary. The real implementation wraps `MediaCodec`; tests use a fake, because only
 * "should this be sent" is answerable on a computer.
 */
interface ClusterFlyReencoder {
    /** Feed one codec unit. False means this one was dropped, which is a counter, not an error. */
    fun submit(nalu: ByteArray, nowMillis: Long): Boolean

    fun close()
}

/**
 * Decides what the cluster gets, frame by frame.
 *
 * The phone's stream is never touched: if it is already Baseline it is forwarded as-is. Only when it
 * is not does an encoder come into being, and only for as long as the layer is pushing. Nothing here
 * ever asks CarPlay for a second picture.
 */
object ClusterFlyPush {

    /** The centre screen. The second screen (111) is the cluster's own and is never forwarded. */
    const val SCREEN_TYPE_MAIN = 110

    const val BASELINE_PROFILE_IDC = 66

    /** The mode the factory's own player uses when it has to re-encode. */
    const val ENCODE_BITRATE = 2_000_000
    const val ENCODE_KEYFRAME_INTERVAL_SECONDS = 1

    /** Set by the controller: builds the real encoder from the cluster's SPS/PPS. */
    @Volatile
    var reencoderFactory: ((ByteArray) -> ClusterFlyReencoder?)? = null

    private var reencode = false
    private var reencoder: ClusterFlyReencoder? = null

    fun onFrame(
        phase: FlyPhase,
        screenType: Int,
        nalu: ByteArray,
        nowMillis: Long,
    ): PushDecision {
        if (phase != FlyPhase.PUSHING) {
            release()
            return PushDecision(send = false, mode = mode(), dropReason = "idle")
        }
        if (screenType != SCREEN_TYPE_MAIN) {
            return PushDecision(send = false, mode = mode(), dropReason = "idle")
        }

        if (reencode) {
            val encoder = reencoder
                ?: return PushDecision(send = false, mode = PushMode.REENCODE, dropReason = "encoder")
            val accepted = encoder.submit(nalu, nowMillis)
            return PushDecision(
                send = accepted,
                mode = PushMode.REENCODE,
                dropReason = if (accepted) null else "encoder",
            )
        }

        val profile = profileIdc(nalu)
        if (profile == null || profile == BASELINE_PROFILE_IDC) {
            return PushDecision(send = true, mode = PushMode.FORWARD, dropReason = null)
        }

        // The first non-Baseline unit — usually the configuration with SPS and PPS — is dropped, and
        // from here on the encoder owns the picture.
        reencode = true
        reencoder = reencoderFactory?.invoke(nalu)
        return PushDecision(send = false, mode = PushMode.REENCODE, dropReason = "profile")
    }

    /** Forgets the encoder. Called when the layer closes, and safe to call when nothing is open. */
    fun release() {
        reencode = false
        reencoder?.close()
        reencoder = null
    }

    private fun mode(): PushMode = if (reencode) PushMode.REENCODE else PushMode.FORWARD

    /**
     * `profile_idc` of the first SPS in the unit, or null if there is none.
     *
     * A unit without an SPS continues whatever the stream already was; it is not a reason to switch.
     */
    private fun profileIdc(nalu: ByteArray): Int? {
        var i = 0
        while (i < nalu.size) {
            val header = startCodeEnd(nalu, i)
            if (header == null) {
                i++
                continue
            }
            if (header >= nalu.size) return null
            if ((nalu[header].toInt() and 0x1F) == NAL_TYPE_SPS) {
                return if (header + 1 < nalu.size) nalu[header + 1].toInt() and 0xFF else null
            }
            i = header
        }
        return null
    }

    /** Index of the NAL header if a start code begins at [at], or null. */
    private fun startCodeEnd(bytes: ByteArray, at: Int): Int? = when {
        at + 3 <= bytes.size &&
            bytes[at].toInt() == 0 && bytes[at + 1].toInt() == 0 && bytes[at + 2].toInt() == 1 -> at + 3
        at + 4 <= bytes.size &&
            bytes[at].toInt() == 0 && bytes[at + 1].toInt() == 0 &&
            bytes[at + 2].toInt() == 0 && bytes[at + 3].toInt() == 1 -> at + 4
        else -> null
    }

    private const val NAL_TYPE_SPS = 7
}

/**
 * The frame-rate cap the factory's own player settles on.
 *
 * Ten frames a second while the encoder keeps up; five once one frame takes longer than 100 ms, and
 * back to ten only after three seconds in a row under 80 ms. The hysteresis is what keeps it from
 * flapping between the two rates on a machine that is merely busy.
 */
class ClusterFlyFrameRate {

    var capFps: Int = FAST_FPS
        private set

    /** When the current run of fast frames began, or [NONE] if there is no such run. */
    private var fastSince = NONE

    fun onEncoded(durationMillis: Long, nowMillis: Long) {
        if (durationMillis > SLOW_FRAME_MILLIS) {
            capFps = SLOW_FPS
            fastSince = NONE
            return
        }
        if (capFps == FAST_FPS) return
        if (durationMillis >= FAST_FRAME_MILLIS) {
            // Not slow enough to stay down, not fast enough to count: the run is broken.
            fastSince = NONE
            return
        }
        if (fastSince == NONE) fastSince = nowMillis
        if (nowMillis - fastSince >= FAST_STREAK_MILLIS) {
            capFps = FAST_FPS
            fastSince = NONE
        }
    }

    private companion object {
        const val FAST_FPS = 10
        const val SLOW_FPS = 5
        const val SLOW_FRAME_MILLIS = 100L
        const val FAST_FRAME_MILLIS = 80L
        const val FAST_STREAK_MILLIS = 3_000L
        const val NONE = Long.MIN_VALUE
    }
}
