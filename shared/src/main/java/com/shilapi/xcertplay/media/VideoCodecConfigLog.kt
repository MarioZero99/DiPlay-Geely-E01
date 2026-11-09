package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec

/**
 * The one line that answers "what did the phone decide to send".
 *
 * The profile is the sender's choice and is only visible in the stream configuration — the avcC or
 * hvcC record — never in the frames. It is logged for every session, not only the ones that fly,
 * because it decides whether the cluster can take the picture as it is.
 */
object VideoCodecConfigLog {

    fun line(codec: VideoCodec, codecData: ByteArray): String =
        "video config codec=$codec profile=${profile(codec, codecData)}"

    /**
     * `profile_idc` of an avcC record, or the HEVC general profile bits, or -1 when the bytes are
     * not a configuration record we recognise.
     */
    private fun profile(codec: VideoCodec, codecData: ByteArray): Int = when {
        codecData.size < 2 -> -1
        codec == VideoCodec.H264 && codecData[0].toInt() == 1 -> codecData[1].toInt() and 0xFF
        codec == VideoCodec.H265 -> codecData[1].toInt() and 0x1F
        else -> -1
    }
}
