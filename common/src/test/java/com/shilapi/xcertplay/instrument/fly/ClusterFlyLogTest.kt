package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session log's fly lines, as strings.
 *
 * They are what a car with no adb has to go on, so each one has a fixed prefix and nothing that
 * looks like video: no NAL, no RTP body, no address.
 */
class ClusterFlyLogTest {

    private fun openConditions() = FlyConditions(
        navigationArmed = true,
        deviceProvisioned = true,
        reversing = false,
        radar = false,
        ecall = false,
        millisSinceLastClusterCommand = 1_000L,
    )

    @Test
    fun `a blocked reason appears exactly`() {
        assertEquals("fly.blocked RADAR", ClusterFlyLog.blocked(FlyBlock.RADAR))
    }

    @Test
    fun `each phase has its own line`() {
        FlyPhase.values().forEach { assertEquals("fly.phase ${it.name}", ClusterFlyLog.phase(it)) }
    }

    @Test
    fun `the statistics line carries numbers and no payload`() {
        val line = ClusterFlyLog.stat(packets = 12, bytes = 34_567, drops = 2, fps = 10, keyframeAgeMs = 250)
        assertTrue(line.startsWith("fly.stat packets=12 bytes=34567 drops=2 fps=10 keyframeAgeMs=250"))
        assertFalse(line.contains("[B@"))
    }

    @Test
    fun `a session reports its phase changes through the sink`() {
        val lines = mutableListOf<String>()
        val session = ClusterFlySession { lines.add(it) }

        session.onLeft(1_000L, openConditions())

        assertTrue(lines.contains("fly.phase ARMING"))
        // Commands are logged where they are actually sent, by the controller, not here: a step
        // that is cancelled before it goes out must not appear in the log as if it had.
        assertFalse(lines.contains("fly.command NAV_ON"))
    }
}
