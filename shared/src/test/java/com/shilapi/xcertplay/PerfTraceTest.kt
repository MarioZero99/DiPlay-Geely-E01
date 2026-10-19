package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerfTraceTest {
    private var nowNs = 0L
    private val warnings = mutableListOf<String>()

    @Before fun setUp() {
        PerfTrace.reset()
        nowNs = 1_000_000_000L
        PerfTrace.clock = { nowNs }
        PerfTrace.warn = { warnings.add(it) }
    }

    @After fun tearDown() {
        PerfTrace.reset()
        PerfTrace.clock = System::nanoTime
        PerfTrace.warn = { DiagLog.w("DiPlay-Perf", it) }
    }

    /** The tail is what a driver feels; an average of 4 ms with a 90 ms p95 is still a stutter. */
    @Test fun percentilesAreNearestRankNotAnAverage() {
        for (value in 1L..100L) PerfTrace.record("ui/tick", value)
        val line = PerfTrace.summary().lineWith("ui/tick")
        assertTrue(line, line.contains("p50=50"))
        assertTrue(line, line.contains("p95=95"))
        assertTrue(line, line.contains("max=100"))
        assertTrue(line, line.contains("n=100"))
    }

    /** The ring bounds memory; the count and the sum still describe the whole run. */
    @Test fun theRingKeepsTheNewestAndTheRunIsStillCounted() {
        repeat(PerfTrace.SAMPLE_CAPACITY + 88) { PerfTrace.record("video/decode", 7) }
        val line = PerfTrace.summary().lineWith("video/decode")
        assertTrue(line, line.contains("n=${PerfTrace.SAMPLE_CAPACITY}"))
        assertTrue(line, line.contains("sum=${(PerfTrace.SAMPLE_CAPACITY + 88) * 7}"))
    }

    @Test fun aBudgetCountsOnlyTheSamplesPastIt() {
        PerfTrace.record("ui/tick", 3, warnAboveMs = 8)
        PerfTrace.record("ui/tick", 12, warnAboveMs = 8)
        PerfTrace.record("ui/tick", 40, warnAboveMs = 8)
        val line = PerfTrace.summary().lineWith("ui/tick")
        assertTrue(line, line.contains("over=2/3"))
    }

    /** One slow tick, one line: a janky minute must not become a log storm of its own. */
    @Test fun aSlowSampleIsAnnouncedOncePerWindowNotOncePerSample() {
        repeat(5) { PerfTrace.record("ui/render", 120, warnAboveMs = 32) }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single(), warnings.single().contains("ui/render took 120ms (budget 32ms)"))
    }

    @Test fun aSampleInsideItsBudgetIsSilent() {
        PerfTrace.record("ui/render", 20, warnAboveMs = 32)
        assertTrue(warnings.isEmpty())
    }

    /** Two chains, two clocks: startup must not report the gap since a connection step. */
    @Test fun marksMeasureTheirOwnChainAndNotTheLastMarkOfAnyKind() {
        assertEquals(-1L, PerfTrace.mark("connect", "connect/usb/discover"))
        nowNs += 250_000_000L
        assertEquals(250L, PerfTrace.mark("connect", "connect/usb/config"))
        assertEquals(-1L, PerfTrace.mark("video", "video/first-frame"))
        nowNs += 4_000_000_000L
        assertEquals(4_000L, PerfTrace.mark("video", "video/first-frame"))
        assertEquals(4_000L, PerfTrace.mark("connect", "connect/usb/data"))
    }

    @Test fun marksArePrintedInOrderWithTheirGaps() {
        PerfTrace.mark("startup", "startup/create-done")
        nowNs += 812_000_000L
        PerfTrace.mark("startup", "startup/first-draw")
        val text = PerfTrace.summary()
        assertTrue(text, text.indexOf("startup/create-done") < text.indexOf("startup/first-draw"))
        assertTrue(text, text.contains("+812ms"))
        assertTrue(text, text.contains("--"))
    }

    /** Gaps are per chain, but the timeline is shared: a reader must not have to add them up. */
    @Test fun marksAlsoCarryATimeOnOneSharedLine() {
        PerfTrace.mark("startup", "startup/create-done")
        nowNs += 1_000_000_000L
        assertEquals(-1L, PerfTrace.mark("connect", "connect/usb/discover"))
        val text = PerfTrace.summary()
        assertTrue(text, text.contains("t=  1000ms  connect/usb/discover"))
    }

    @Test fun theWorstTailIsPrintedFirst() {
        PerfTrace.record("ui/tick", 2, warnAboveMs = 8)
        PerfTrace.record("ui/render", 90, warnAboveMs = 32)
        PerfTrace.record("startup/bootstrap", 300)
        val text = PerfTrace.summary()
        assertTrue(text, text.indexOf("startup/bootstrap") < text.indexOf("ui/render"))
        assertTrue(text, text.indexOf("ui/render") < text.indexOf("ui/tick"))
    }

    @Test fun anEmptyRunSaysSoInsteadOfPrintingZeros() {
        assertEquals("Perf: nothing sampled yet.", PerfTrace.summary())
        assertFalse(PerfTrace.summary().contains("p50"))
    }

    /** A reconnect loop must not push the newest ladder out of the report. */
    @Test fun theNewestMarksSurviveAFullBuffer() {
        repeat(PerfTrace.MARK_CAPACITY + 10) { index -> PerfTrace.mark("connect", "step/$index") }
        val text = PerfTrace.summary()
        assertTrue(text, text.contains("step/${PerfTrace.MARK_CAPACITY + 9}"))
        assertFalse(text, text.contains("step/0\n"))
        assertTrue(text, text.contains("earlier marks dropped"))
    }

    /** Video threads, radio threads and the tick all record; none may be lost or duplicated. */
    @Test fun concurrentWritersKeepEverySample() {
        val workers = (0 until 8).map {
            Thread { repeat(200) { PerfTrace.record("ui/tick", 4) } }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        val line = PerfTrace.summary().lineWith("ui/tick")
        assertTrue(line, line.contains("n=512"))
        assertTrue(line, line.contains("sum=6400"))
    }

    @Test fun resetForgetsEverything() {
        PerfTrace.record("ui/tick", 4)
        PerfTrace.mark("connect", "connect/usb/discover")
        PerfTrace.reset()
        assertEquals("Perf: nothing sampled yet.", PerfTrace.summary())
    }

    private fun String.lineWith(name: String): String =
        lines().firstOrNull { it.trimStart().startsWith(name) } ?: error("no line for $name in:\n$this")
}
