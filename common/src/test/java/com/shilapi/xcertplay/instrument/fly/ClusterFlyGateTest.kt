package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate in front of every command sent to the cluster.
 *
 * These are the states DiPlay can know about on its own. Reversing is the one it usually cannot see,
 * and the gate says so by keeping it false: the factory drops the command anyway.
 */
class ClusterFlyGateTest {

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

    @Test
    fun `radar blocks even when navigation is armed`() {
        val block = ClusterFlyGate.decide(openConditions(radar = true))
        assertEquals(FlyBlock.RADAR, block)
        assertEquals("雷达报警时不能飞屏", ClusterFlyWarning.text(block))
    }

    @Test
    fun `a second command inside one second is too soon`() {
        assertEquals(
            FlyBlock.TOO_SOON,
            ClusterFlyGate.decide(openConditions(millisSinceLastClusterCommand = 999)),
        )
    }

    @Test
    fun `clear conditions pass`() {
        assertEquals(FlyBlock.NONE, ClusterFlyGate.decide(openConditions()))
        assertEquals(null, ClusterFlyWarning.text(FlyBlock.NONE))
    }
}
