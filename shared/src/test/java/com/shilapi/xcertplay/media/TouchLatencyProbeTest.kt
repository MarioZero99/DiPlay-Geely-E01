package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLatencyProbeTest {

    @After fun reset() = TouchLatencyProbe.resetForTest()

    @Test fun arrivalAndRenderAreMeasuredFromTheSameTouch() {
        TouchLatencyProbe.onTouchSent(originNs = 1_000_000_000L, sendDurationNs = 2_000_000L)
        assertEquals(60_000_000L, TouchLatencyProbe.onFrame(1_060_000_000L))
        assertEquals(195_000_000L, TouchLatencyProbe.onRendered(1_195_000_000L))
    }

    @Test fun aSecondTouchBeforeAFrameDoesNotMoveTheOrigin() {
        TouchLatencyProbe.onTouchSent(1_000_000_000L, 0L)
        TouchLatencyProbe.onTouchSent(1_020_000_000L, 0L)
        assertEquals(40_000_000L, TouchLatencyProbe.onFrame(1_040_000_000L))
    }

    @Test fun staleSampleIsDroppedInsteadOfInflatingTheMaximum() {
        TouchLatencyProbe.onTouchSent(1_000_000_000L, 0L)
        assertEquals(-1L, TouchLatencyProbe.onRendered(3_000_000_000L))
        assertEquals(1, TouchLatencyProbe.staleForTest())
    }

    @Test fun inputQueueCoalescingAndDropsAllLandInOneSummary() {
        TouchLatencyProbe.onInputDelay(4_000_000L)
        TouchLatencyProbe.onInputDelay(6_000_000L)
        TouchLatencyProbe.onQueueDelay(10_000_000L)
        TouchLatencyProbe.onQueueDelay(30_000_000L)
        TouchLatencyProbe.onMoveCoalesced()
        TouchLatencyProbe.onMoveCoalesced()
        TouchLatencyProbe.onDropped()
        val line = TouchLatencyProbe.queueSummary()
        assertTrue(line.contains("input avg=5ms max=6ms"))
        assertTrue(line.contains("touchQueue avg=20ms max=30ms"))
        assertTrue(line.contains("coalesced=2"))
        assertTrue(line.contains("dropped=1"))
    }

    @Test fun aResetWindowReportsMinusOneInsteadOfZero() {
        TouchLatencyProbe.onQueueDelay(10_000_000L)
        TouchLatencyProbe.queueSummary()
        val line = TouchLatencyProbe.queueSummary()
        assertTrue(line.contains("touchQueue avg=-1ms"))
        assertTrue(line.contains("input avg=-1ms"))
    }
}
