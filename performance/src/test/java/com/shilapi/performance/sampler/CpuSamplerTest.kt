package com.shilapi.performance.sampler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CpuSamplerTest {
    @Test
    fun firstSampleReturnsNegativeOneAndArmsBaseline() {
        val sampler = CpuSampler()
        val line1 = "cpu  100 20 30 850 0 0 0 0 0 0"
        val first = sampler.computeUsageFromLine(line1)
        assertEquals(-1, first)
    }

    @Test
    fun secondSampleCalculatesInstantaneousDelta() {
        val sampler = CpuSampler()
        val line1 = "cpu  100 20 30 850 0 0 0 0 0 0" // total 1000, idle 850
        val line2 = "cpu  200 40 60 900 0 0 0 0 0 0" // total 1200, idle 900 -> totalDelta 200, idleDelta 50 -> 75%
        sampler.computeUsageFromLine(line1)
        val usage = sampler.computeUsageFromLine(line2)
        assertEquals(75, usage)
    }

    @Test
    fun handlesZeroDeltaOrMalformedLineGracefully() {
        val sampler = CpuSampler()
        val usage = sampler.computeUsageFromLine("")
        assertEquals(-1, usage)
    }

    @Test
    fun samplePerformsInlineDeltaWhenBaselineUnset() {
        var sleptMs = 0L
        val sampler = CpuSampler()
        sampler.reset()

        val lines = listOf(
            "cpu  100 20 30 850 0 0 0 0 0 0",
            "cpu  200 40 60 900 0 0 0 0 0 0",
        )
        var callIndex = 0

        val metrics = sampler.sample(
            readStatLine = { lines.getOrNull(callIndex++) },
            sleepMs = { delay -> sleptMs = delay },
        )
        assertNotNull(metrics)
        assertEquals(120L, sleptMs)
        assertEquals(75, metrics.usagePercent)
    }
}
