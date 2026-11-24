package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer's state machine, driven by numbers.
 *
 * The thing it must never do is push before the cluster's display is up: a cluster that keeps its
 * map page gets nothing, and nothing is copied from the centre screen to make up for it.
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

    /** Left at 1000 ms: `FLY_LEFT` is due at 3000 ms, the display at 3100. */
    private fun pushing(): ClusterFlySession {
        session.onLeft(1_000L, openConditions())
        session.onWaitTimeout(3_000L)
        session.onDisplayReady(3_100L)
        return session
    }

    @Test
    fun `no picture before the left step is due`() {
        val left = session.onLeft(1_000L, openConditions())
        assertEquals(FlyPhase.ARMING, left.phase)
        assertFalse(left.push)

        // The wait does not begin until the fly step itself is due at 3000.
        assertEquals(FlyPhase.ARMING, session.onWaitTimeout(2_000L).phase)
    }

    @Test
    fun `a wait with no display starts nothing`() {
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
     * The display can come up while a radar warning is already active. The picture stays away until
     * the warning clears — the one state the warning line describes as forbidden.
     */
    @Test
    fun `a display under radar is refused`() {
        session.onLeft(1_000L, openConditions())
        session.onWaitTimeout(3_000L)
        session.onSafety(openConditions(radar = true), 3_050L)

        val display = session.onDisplayReady(3_100L)

        assertFalse(display.push)
        assertEquals(FlyPhase.WAITING_FOR_VIDEO, display.phase)
        assertEquals(FlyBlock.RADAR, display.warning)
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
        assertEquals(FlyCommand.NAV_BACK, cleared.steps.last().command)
    }

    @Test
    fun `abort from arming returns to idle without fly right`() {
        session.onLeft(1_000L, openConditions())
        assertEquals(FlyPhase.ARMING, session.currentPhase)

        val effect = session.onAbort(1_500L)
        assertEquals(FlyPhase.IDLE, effect.phase)
        assertEquals(FlyPhase.IDLE, session.currentPhase)
        assertFalse(effect.push)
        assertEquals(
            listOf(FlyCommand.NAV_MODE_OFF, FlyCommand.NAV_OFF, FlyCommand.NAV_BACK),
            effect.steps.map { it.command },
        )
        assertEquals(1_500L, effect.steps[0].sendAtMillis)
        assertEquals(1_500L, effect.steps[1].sendAtMillis)
        assertEquals(2_500L, effect.steps[2].sendAtMillis)
        assertTrue(effect.steps.all { it.retraction })
        assertFalse(effect.steps.any { it.command == FlyCommand.FLY_RIGHT })

        assertEquals(FlyPhase.IDLE, session.onWaitTimeout(3_000L).phase)

        val next = session.onLeft(3_100L, openConditions())
        assertEquals(FlyPhase.ARMING, next.phase)
        assertEquals(FlyCommand.NAV_ON, next.steps.first().command)
    }

    @Test
    fun `abort from waiting returns to idle without fly right`() {
        session.onLeft(1_000L, openConditions())
        session.onWaitTimeout(3_000L)
        assertEquals(FlyPhase.WAITING_FOR_VIDEO, session.currentPhase)

        val effect = session.onAbort(5_400L)
        assertEquals(FlyPhase.IDLE, session.currentPhase)
        assertEquals(
            listOf(FlyCommand.NAV_MODE_OFF, FlyCommand.NAV_OFF, FlyCommand.NAV_BACK),
            effect.steps.map { it.command },
        )
        assertEquals(1_000L, effect.steps[2].sendAtMillis - effect.steps[0].sendAtMillis)
        assertFalse(effect.steps.any { it.command == FlyCommand.FLY_RIGHT })
    }

    @Test
    fun `abort during pushing does not drop the layer`() {
        pushing()
        val effect = session.onAbort(3_000L)
        assertEquals(FlyPhase.PUSHING, effect.phase)
        assertTrue(effect.steps.isEmpty())
        assertTrue(effect.push)
    }

    @Test fun aDisplayWhileWaitingStartsThePicture() {
        val session = ClusterFlySession()
        val now = 10_000L
        val opened = session.onLeft(now, openConditions())
        val due = opened.steps.first { it.command == FlyCommand.FLY_LEFT }.sendAtMillis
        assertEquals(FlyPhase.ARMING, session.onWaitTimeout(due - 1).phase)
        assertEquals(FlyPhase.WAITING_FOR_VIDEO, session.onWaitTimeout(due).phase)
        val effect = session.onDisplayReady(due + 1)
        assertEquals(FlyPhase.PUSHING, effect.phase)
        assertTrue(effect.push)
    }

    @Test fun aDisplayWhileArmingDoesNothing() {
        val session = ClusterFlySession()
        val effect = session.onLeft(10_000L, openConditions()).let {
            session.onDisplayReady(10_000L)
        }
        assertEquals(FlyPhase.ARMING, effect.phase)
        assertFalse(effect.push)
    }

    @Test fun aDisplayWhileIdleDoesNothing() {
        val effect = ClusterFlySession().onDisplayReady(0L)
        assertEquals(FlyPhase.IDLE, effect.phase)
        assertFalse(effect.push)
    }
}
