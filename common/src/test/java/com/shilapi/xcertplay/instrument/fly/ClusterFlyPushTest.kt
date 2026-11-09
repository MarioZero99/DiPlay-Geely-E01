package com.shilapi.xcertplay.instrument.fly

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
}
