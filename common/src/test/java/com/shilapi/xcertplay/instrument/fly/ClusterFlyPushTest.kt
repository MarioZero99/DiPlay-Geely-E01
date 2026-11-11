package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What leaves the centre screen's decoder and goes to the cluster.
 *
 * Only the centre screen's own picture (type 110) is ever forwarded, only while the layer is pushing,
 * and only untouched if it is Baseline. Anything else is re-encoded; nothing is ever asked of the
 * phone a second time.
 */
class ClusterFlyPushTest {

    private fun sps(profileIdc: Int) = byteArrayOf(0, 0, 0, 1, 0x67, profileIdc.toByte(), 0, 0)

    @Test
    fun `main baseline frame is forwarded only while pushing`() {
        val sps = sps(66)
        assertEquals(false, ClusterFlyPush.onFrame(FlyPhase.WAITING_FOR_VIDEO, 110, sps, 0L).send)
        val live = ClusterFlyPush.onFrame(FlyPhase.PUSHING, 110, sps, 0L)
        assertTrue(live.send)
        assertEquals(PushMode.FORWARD, live.mode)
    }

    @Test
    fun `high profile is not forwarded`() {
        val decision = ClusterFlyPush.onFrame(FlyPhase.PUSHING, 110, sps(100), 0L)
        assertFalse(decision.send)
        assertEquals(PushMode.REENCODE, decision.mode)
    }

    @Test
    fun `the second picture is not forwarded either`() {
        ClusterFlyPush.onFrame(FlyPhase.PUSHING, 110, sps(100), 0L)
        val decision = ClusterFlyPush.onFrame(FlyPhase.PUSHING, 110, byteArrayOf(0, 0, 0, 1, 0x65), 16L)
        assertEquals(PushMode.REENCODE, decision.mode)
    }

    /**
     * The sink's config callback carries an avcC record, not Annex-B. It has to be recognised as
     * such — both to read the profile and so that it is never packetized as if it were a NAL.
     */
    @Test
    fun `an avcC record is read and rewritten as Annex-B`() {
        val config = avcCConfig(profileIdc = 66)
        assertTrue(ClusterFlyPush.isConfig(config))
        assertEquals(66, ClusterFlyPush.configProfile(config))

        val annex = ClusterFlyPush.annexB(config)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 66, 0x11, 0, 0, 0, 1, 0x68, 0x22),
            annex,
        )
    }

    @Test
    fun `an Annex-B frame is not mistaken for a config`() {
        assertFalse(ClusterFlyPush.isConfig(sps(66)))
    }

    /** With no encoder built, the log must not say one is running. */
    @Test
    fun `a missing reencoder does not claim to be encoding`() {
        val lines = mutableListOf<String>()
        ClusterFlyPush.reset()
        ClusterFlyPush.log = { lines.add(it) }
        try {
            ClusterFlyPush.onFrame(FlyPhase.PUSHING, 110, sps(100), 0L)

            assertTrue(lines.contains("fly.profile 100"))
            assertFalse(lines.contains("fly.push start reencode"))
            assertFalse(lines.contains("fly.push start forward"))
        } finally {
            ClusterFlyPush.log = {}
            ClusterFlyPush.reset()
        }
    }

    @Test
    fun `encoder output without a start code gains one`() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x65, 0x11),
            ClusterFlyPush.withStartCode(byteArrayOf(0x65, 0x11)),
        )
        val already = byteArrayOf(0, 0, 0, 1, 0x65)
        assertTrue(ClusterFlyPush.withStartCode(already) === already)
    }

    /** The E01 has one small encoder; when a frame takes too long, fewer frames are offered. */
    @Test
    fun `a slow encoder drops the frame rate cap until things are fast again`() {
        val rate = ClusterFlyFrameRate()
        assertEquals(10, rate.capFps)

        rate.onEncoded(durationMillis = 150, nowMillis = 0L)
        assertEquals(5, rate.capFps)

        rate.onEncoded(durationMillis = 50, nowMillis = 1_000L)
        rate.onEncoded(durationMillis = 50, nowMillis = 3_999L)
        assertEquals(5, rate.capFps)

        rate.onEncoded(durationMillis = 50, nowMillis = 4_000L)
        assertEquals(10, rate.capFps)
    }

    /**
     * A minimal avcC record: version, profile, compatibility, level, lengthSizeMinusOne, one SPS and
     * one PPS. The bodies are arbitrary; only the framing matters here.
     */
    private fun avcCConfig(profileIdc: Int): ByteArray = byteArrayOf(
        1, profileIdc.toByte(), 0x00, 0x1F, 0xFF.toByte(), 0xE1.toByte(),
        0x00, 0x03, 0x67, profileIdc.toByte(), 0x11,
        0x01,
        0x00, 0x02, 0x68, 0x22,
    )
}
