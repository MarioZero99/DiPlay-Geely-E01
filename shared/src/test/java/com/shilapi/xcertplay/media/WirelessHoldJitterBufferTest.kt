package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessHoldJitterBufferTest {

    @Test
    fun `wired mode always bypasses gating and requires zero packets`() {
        val buffer = WirelessHoldJitterBuffer(isWireless = false, audioType = "media", sampleRate = 48000)
        assertEquals(0, buffer.targetHoldPackets())
        assertTrue("Wired mode must be ready to drain even with 0 queued packets", buffer.isReadyToDrain(0))

        buffer.onUnderrunDetected()
        assertFalse("Wired mode must never enter underrun escalation", buffer.hasUnderrun)
        assertEquals(0, buffer.targetHoldPackets())
        assertTrue(buffer.isReadyToDrain(0))
    }

    @Test
    fun `non media audio types always bypass gating and require zero packets`() {
        for (type in listOf("telephony", "speechrecognition", "alert", "default", "compatibility")) {
            val buffer = WirelessHoldJitterBuffer(isWireless = true, audioType = type, sampleRate = 48000)
            assertEquals("Audio type $type must require 0 packets", 0, buffer.targetHoldPackets())
            assertTrue("Audio type $type must be ready to drain with 0 packets", buffer.isReadyToDrain(0))

            buffer.onUnderrunDetected()
            assertFalse("Non-media type $type must never escalate", buffer.hasUnderrun)
            assertEquals(0, buffer.targetHoldPackets())
        }
    }

    @Test
    fun `wireless 48kHz media starts at 47 packets and escalates to 104 packets on underrun`() {
        val buffer = WirelessHoldJitterBuffer(isWireless = true, audioType = "media", sampleRate = 48000)

        // Initial target: 1000ms -> ceil(1000 * 48000 / 1024 / 1000) = 47 packets
        assertEquals(47, buffer.targetHoldPackets())
        assertFalse(buffer.hasUnderrun)
        assertFalse("46 packets should not be ready to drain", buffer.isReadyToDrain(46))
        assertTrue("47 packets should be ready to drain", buffer.isReadyToDrain(47))
        assertTrue("48 packets should be ready to drain", buffer.isReadyToDrain(48))

        // Underrun detected: escalate to 2200ms -> ceil(2200 * 48000 / 1024 / 1000) = 104 packets
        buffer.onUnderrunDetected()
        assertTrue(buffer.hasUnderrun)
        assertEquals(104, buffer.targetHoldPackets())
        assertFalse("103 packets should not be ready to drain after escalation", buffer.isReadyToDrain(103))
        assertTrue("104 packets should be ready to drain after escalation", buffer.isReadyToDrain(104))
        assertTrue("105 packets should be ready to drain after escalation", buffer.isReadyToDrain(105))

        // Stream destroyed: reset back to cold start (47 packets)
        buffer.onStreamDestroyed()
        assertFalse(buffer.hasUnderrun)
        assertEquals(47, buffer.targetHoldPackets())
        assertTrue(buffer.isReadyToDrain(47))

        // Underrun again, verify stream destroyed resets penalty
        buffer.onUnderrunDetected()
        assertTrue(buffer.hasUnderrun)
        assertEquals(104, buffer.targetHoldPackets())
        buffer.onStreamDestroyed()
        assertFalse("Stream destroyed must reset underrun penalty", buffer.hasUnderrun)
        assertEquals(47, buffer.targetHoldPackets())
    }

    @Test
    fun `wireless 44_1kHz media starts at 44 packets and escalates to 95 packets on underrun`() {
        val buffer = WirelessHoldJitterBuffer(isWireless = true, audioType = "media", sampleRate = 44100)

        // Initial target: 1000ms -> ceil(1000 * 44100 / 1024 / 1000) = 44 packets
        assertEquals(44, buffer.targetHoldPackets())
        assertFalse(buffer.isReadyToDrain(43))
        assertTrue(buffer.isReadyToDrain(44))

        // Escalated target: 2200ms -> ceil(2200 * 44100 / 1024 / 1000) = 95 packets
        buffer.onUnderrunDetected()
        assertEquals(95, buffer.targetHoldPackets())
        assertFalse(buffer.isReadyToDrain(94))
        assertTrue(buffer.isReadyToDrain(95))
    }
}
