package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one line that answers "what did the phone decide to send".
 *
 * Only the stream configuration carries it; the frames do not. It is written for every session, not
 * just the ones that fly, because it decides whether the cluster can take the picture as it is.
 */
class VideoCodecConfigLogTest {

    @Test
    fun `an avcC config reports the phone's profile`() {
        val config = byteArrayOf(1, 100, 0, 0x1F, 0xFF.toByte(), 0xE1.toByte(), 0, 1, 0x67)

        assertEquals(
            "video config codec=H264 profile=100",
            VideoCodecConfigLog.line(VideoCodec.H264, config),
        )
    }

    @Test
    fun `an hvcC config masks the profile bits`() {
        val config = byteArrayOf(1, 0x62, 0, 0)

        assertEquals(
            "video config codec=H265 profile=2",
            VideoCodecConfigLog.line(VideoCodec.H265, config),
        )
    }

    @Test
    fun `a config that is neither says unknown`() {
        assertEquals(
            "video config codec=H264 profile=-1",
            VideoCodecConfigLog.line(VideoCodec.H264, byteArrayOf(0, 0, 1)),
        )
    }
}
