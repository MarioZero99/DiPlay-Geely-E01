package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order the factory expects, not the order that looks natural.
 *
 * Navigation must be on before the swipe and must stay on until after the opposite swipe; closing in
 * the other order is dropped, and the cluster is left showing the layer with nothing behind it.
 */
class ClusterFlySequencerTest {

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
    fun `open arms the cluster before the left swipe`() {
        val steps = ClusterFlySequencer.open(1_000L, openConditions())
        assertEquals(
            listOf(
                FlyStep(FlyCommand.NAV_ON, 1_000L),
                FlyStep(FlyCommand.NAV_FRONT, 1_400L),
                FlyStep(FlyCommand.FLY_LEFT, 2_400L),
            ),
            steps,
        )
        assertEquals(1_000L, steps[2].sendAtMillis - steps[1].sendAtMillis)
    }

    @Test
    fun `close keeps navigation until after the right swipe`() {
        val steps = ClusterFlySequencer.close(5_000L, openConditions(millisSinceLastClusterCommand = 5_000))
        assertEquals(
            listOf(
                FlyStep(FlyCommand.FLY_RIGHT, 5_000L),
                FlyStep(FlyCommand.NAV_OFF, 5_400L),
                FlyStep(FlyCommand.NAV_BACK, 6_000L),
            ),
            steps,
        )
        assertEquals(1_000L, steps[2].sendAtMillis - steps[0].sendAtMillis)
    }

    @Test
    fun `ecall produces no command`() {
        assertEquals(emptyList<FlyStep>(), ClusterFlySequencer.open(0L, openConditions(ecall = true)))
    }
}
