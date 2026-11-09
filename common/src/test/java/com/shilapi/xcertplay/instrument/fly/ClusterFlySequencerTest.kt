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
    fun `open sends navigation before the left swipe`() {
        val steps = ClusterFlySequencer.open(1_000L, openConditions())
        assertEquals(
            listOf(FlyStep(FlyCommand.NAV_ON, 1_000L), FlyStep(FlyCommand.FLY_LEFT, 1_400L)),
            steps,
        )
    }

    @Test
    fun `close keeps navigation until after the right swipe`() {
        val steps = ClusterFlySequencer.close(5_000L, openConditions(millisSinceLastClusterCommand = 5_000))
        assertEquals(FlyCommand.FLY_RIGHT, steps[0].command)
        assertEquals(FlyCommand.NAV_OFF, steps[1].command)
        assertEquals(400L, steps[1].sendAtMillis - steps[0].sendAtMillis)
    }

    @Test
    fun `ecall produces no command`() {
        assertEquals(emptyList<FlyStep>(), ClusterFlySequencer.open(0L, openConditions(ecall = true)))
    }
}
