package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredLivenessStatsTest {
    @Test
    fun firstFaultRecorderOnlyAcceptsTheFirstFault() {
        val recorder = FirstFaultRecorder()
        assertEquals(DisconnectOrigin.UNKNOWN, recorder.origin)

        val firstRecorded = recorder.record(DisconnectOrigin.USB_PHYSICAL_DETACH, "Phone detached")
        assertTrue("First record should return true", firstRecorded)
        assertEquals(DisconnectOrigin.USB_PHYSICAL_DETACH, recorder.origin)
        assertEquals("Phone detached", recorder.message)

        // Subsequent records must be ignored (cascading errors)
        val secondRecorded = recorder.record(DisconnectOrigin.NCM_WRITE_EXHAUSTED, "Bulk out failed")
        assertFalse("Subsequent records should return false", secondRecorded)
        assertEquals(DisconnectOrigin.USB_PHYSICAL_DETACH, recorder.origin)
        assertEquals("Phone detached", recorder.message)
    }

    @Test
    fun firstFaultRecorderRejectsUnknownOrigin() {
        val recorder = FirstFaultRecorder()
        val recorded = recorder.record(DisconnectOrigin.UNKNOWN, "Generic error", nowMillis = 1000L)
        assertFalse("UNKNOWN origin must be ignored", recorded)
        assertEquals(DisconnectOrigin.UNKNOWN, recorder.origin)

        // Subsequent valid fault should succeed
        val validRecorded = recorder.record(DisconnectOrigin.NCM_READ_FAULT, "Bulk in fault", nowMillis = 1100L)
        assertTrue("Valid fault after UNKNOWN should be accepted", validRecorded)
        assertEquals(DisconnectOrigin.NCM_READ_FAULT, recorder.origin)
    }

    @Test
    fun physicalDetachSupersedesSecondaryFaults() {
        val recorder = FirstFaultRecorder()
        // Suppose NCM read failed first due to cable disconnect race
        val readFaultRecorded = recorder.record(DisconnectOrigin.NCM_READ_FAULT, "NCM read failed", nowMillis = 1000L)
        assertTrue(readFaultRecorded)
        assertEquals(DisconnectOrigin.NCM_READ_FAULT, recorder.origin)

        // Broadcast arrives later with physical detach: must supersede secondary read fault
        val detachRecorded = recorder.record(DisconnectOrigin.USB_PHYSICAL_DETACH, "Phone detached", nowMillis = 1100L)
        assertTrue("Physical detach must upgrade secondary fault", detachRecorded)
        assertEquals(DisconnectOrigin.USB_PHYSICAL_DETACH, recorder.origin)
        assertEquals("Phone detached", recorder.message)

        // Further secondary faults cannot overwrite physical detach
        val writeFaultRecorded = recorder.record(DisconnectOrigin.NCM_WRITE_EXHAUSTED, "NCM write failed", nowMillis = 1200L)
        assertFalse(writeFaultRecorded)
        assertEquals(DisconnectOrigin.USB_PHYSICAL_DETACH, recorder.origin)
    }

    @Test
    fun livenessStatsTracksCountersAndSilenceGapsWithoutAllocation() {
        val stats = WiredLivenessStats()
        stats.noteInbound(100, nowMillis = 1000L)
        stats.noteInbound(200, nowMillis = 1500L)
        stats.noteOutbound(50, nowMillis = 1200L)

        val snap = stats.snapshot(nowMillis = 2000L)
        assertEquals(2L, snap.inboundPackets)
        assertEquals(300L, snap.inboundBytes)
        assertEquals(1L, snap.outboundPackets)
        assertEquals(50L, snap.outboundBytes)
        assertEquals(500L, snap.sinceLastInboundMs) // 2000 - 1500
        assertEquals(800L, snap.sinceLastOutboundMs) // 2000 - 1200
        assertEquals("rx=2pkts/300B(gap 500ms) tx=1pkts/50B(gap 800ms)", snap.summary())
    }
}
