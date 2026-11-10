package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer's state machine, driven by numbers.
 *
 * The thing it must never do is push before the cluster has asked: a cluster that keeps its map page
 * and never opens 1028 gets nothing, and nothing is copied from the centre screen to make up for it.
 */
class ClusterFlySessionTest {

    private val session = ClusterFlySession()

    private fun openConditions(
        navigationArmed: Boolean = true,
        deviceProvisioned: Boolean = true,
        reversing: Boolean = false,
        radar: Boolean = false,
        ecall: Boolean = false,
        millisSinceLastClusterCommand: Long = 1_000L,
    ) = FlyConditions(
        navigationArmed = navigationArmed,
        deviceProvisioned = deviceProvisioned,
        reversing = reversing,
        radar = radar,
        ecall = ecall,
        millisSinceLastClusterCommand = millisSinceLastClusterCommand,
    )

    /** Left at 1000 ms: `FLY_LEFT` is due at 1400 ms and the cluster has until 4400 ms to ask. */
    private fun pushing(): ClusterFlySession {
        session.onLeft(1_000L, openConditions())
        session.onWaitTimeout(1_400L)
        session.onVideoAsk(VideoAsk.OPEN, 1_500L)
        return session
    }

    @Test
    fun `no video before the left step is due`() {
        val left = session.onLeft(1_000L, openConditions())
        assertEquals(FlyPhase.ARMING, left.phase)
        assertFalse(left.push)

        val early = session.onVideoAsk(VideoAsk.OPEN, 1_200L)
        assertFalse(early.push)
        assertEquals(FlyPhase.ARMING, early.phase)
    }

    @Test
    fun `video starts only after the cluster asks`() {
        assertEquals(FlyPhase.ARMING, session.onLeft(1_000L, openConditions()).phase)
        assertEquals(FlyPhase.WAITING_FOR_VIDEO, session.onWaitTimeout(1_400L).phase)

        val open = session.onVideoAsk(VideoAsk.OPEN, 1_500L)
        assertTrue(open.push)
        assertEquals(FlyPhase.PUSHING, open.phase)
    }

    @Test
    fun `a cluster that never asks gets no video`() {
        session.onLeft(1_000L, openConditions())
        val late = session.onWaitTimeout(4_500L)
        assertFalse(late.push)
        assertEquals(FlyPhase.WAITING_FOR_VIDEO, late.phase)
    }

    @Test
    fun `a second left already flying sends nothing`() {
        pushing()
        val again = session.onLeft(2_000L, openConditions(millisSinceLastClusterCommand = 1_000))
        assertTrue(again.steps.isEmpty())
        assertTrue(again.push)
    }

    @Test
    fun `radar during playback stops the picture without a close command`() {
        pushing()
        val safety = session.onSafety(openConditions(radar = true), 2_000L)
        assertFalse(safety.push)
        assertTrue(safety.steps.isEmpty())
        assertEquals(FlyBlock.RADAR, safety.warning)
    }

    @Test
    fun `right stops the picture before the close commands`() {
        pushing()
        val right = session.onRight(5_000L, openConditions(millisSinceLastClusterCommand = 5_000))
        assertFalse(right.push)
        assertEquals(FlyPhase.CLOSING, right.phase)
        assertEquals(FlyCommand.FLY_RIGHT, right.steps.first().command)
    }

    /**
     * The cluster can ask while a radar warning is already up. The answer is no, and the picture
     * stays away until the warning clears — the one state the warning line describes as forbidden.
     */
    @Test
    fun `an open under radar is refused`() {
        session.onLeft(1_000L, openConditions())
        session.onWaitTimeout(1_400L)

        val open = session.onVideoAsk(VideoAsk.OPEN, 1_500L, openConditions(radar = true))

        assertFalse(open.push)
        assertEquals(FlyPhase.WAITING_FOR_VIDEO, open.phase)
        assertEquals(FlyBlock.RADAR, open.warning)
    }

    /** The close is a command like any other: two inside a second are dropped by the factory. */
    @Test
    fun `a close inside one second waits for the boundary`() {
        pushing()

        val close = session.onVideoAsk(VideoAsk.CLOSE, 2_000L, openConditions(millisSinceLastClusterCommand = 200))

        assertFalse(close.push)
        assertEquals(FlyPhase.CLOSING, close.phase)
        assertEquals(FlyCommand.FLY_RIGHT, close.steps.first().command)
        assertEquals(2_800L, close.steps.first().sendAtMillis)
    }

    /** A right swipe made too quickly still has to close the layer; it just has to wait. */
    @Test
    fun `a right inside one second still closes at the boundary`() {
        pushing()

        val right = session.onRight(2_000L, openConditions(millisSinceLastClusterCommand = 200))

        assertFalse(right.push)
        assertEquals(FlyPhase.CLOSING, right.phase)
        assertEquals(2_800L, right.steps.first().sendAtMillis)
    }

    /**
     * Radar stops the picture at once, but the layer is still up. When the warning clears and the
     * interval allows it, the close goes out instead of being forgotten.
     */
    @Test
    fun `radar during playback closes once it clears`() {
        pushing()
        val radar = session.onSafety(openConditions(radar = true), 2_000L)
        assertFalse(radar.push)
        assertTrue(radar.steps.isEmpty())

        val cleared = session.onSafety(openConditions(millisSinceLastClusterCommand = 5_000), 6_000L)

        assertEquals(FlyPhase.CLOSING, cleared.phase)
        assertEquals(FlyCommand.FLY_RIGHT, cleared.steps.first().command)
        assertEquals(FlyCommand.NAV_OFF, cleared.steps.last().command)
    }

    /**
     * The cluster can ask before the left step is due. Refusing then would cost the picture for the
     * rest of the session, so the ask is remembered and answered when the wait begins.
     */
    @Test
    fun `an open during arming starts pushing at the fly step`() {
        session.onLeft(1_000L, openConditions())

        val early = session.onVideoAsk(VideoAsk.OPEN, 1_200L, openConditions())
        assertFalse(early.push)
        assertTrue(early.holdOpen)
        assertEquals(FlyPhase.ARMING, early.phase)

        val waited = session.onWaitTimeout(1_400L)

        assertTrue(waited.push)
        assertEquals(FlyPhase.PUSHING, waited.phase)
        assertTrue(session.takePendingOpen())
    }
}
