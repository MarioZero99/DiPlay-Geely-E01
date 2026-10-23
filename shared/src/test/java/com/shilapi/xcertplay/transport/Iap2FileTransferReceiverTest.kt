package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Iap2FileTransferReceiverTest {
    @Test
    fun reassemblesTwoDataPacketsIntoCompletedArtwork() {
        val receiver = Iap2FileTransferReceiver()
        val payload1 = byteArrayOf(0x01, 0x02, 0x03)
        val payload2 = byteArrayOf(0x04, 0x05)

        // Setup packet: ID=1, Flags=0x04 (Setup), 8-byte size=5, 2-byte type=2
        val setup = ByteArray(12).apply {
            this[0] = 1.toByte()
            this[1] = 0x04.toByte()
            this[9] = 5.toByte()
            this[11] = 2.toByte()
        }
        val setupOutcome = receiver.accept(setup)
        assertNull(setupOutcome.completed)
        assertEquals(1, setupOutcome.replies.size)

        // Data packet 1: ID=1, Flags=0x00
        val p1 = byteArrayOf(1.toByte(), 0x00.toByte()) + payload1
        val p1Outcome = receiver.accept(p1)
        assertNull(p1Outcome.completed)

        // Data packet 2 (Last): ID=1, Flags=0x40 (FLAG_LAST)
        val p2 = byteArrayOf(1.toByte(), 0x40.toByte()) + payload2
        val outcome = receiver.accept(p2)

        assertNotNull(outcome.completed)
        assertEquals(1, outcome.completed?.id)
        assertArrayEquals(payload1 + payload2, outcome.completed?.bytes)
    }

    @Test
    fun cancelsTransferExceedingMaximumAllowedSize() {
        val receiver = Iap2FileTransferReceiver(maximumArtworkBytes = 100)
        // Setup packet declaring 101 bytes
        val setup = ByteArray(12).apply {
            this[0] = 2.toByte()
            this[1] = 0x04.toByte()
            this[9] = 101.toByte()
            this[11] = 2.toByte()
        }
        val outcome = receiver.accept(setup)
        assertNull(outcome.completed)
        assertEquals(1, outcome.replies.size)
        // Reply should be cancel opcode 2
        val reply = outcome.replies[0]
        assertEquals(2.toByte(), reply[0])
        assertEquals(2.toByte(), reply[1])

        // Subsequent data packet for this canceled setup must be declined and never complete
        val followUpData = byteArrayOf(2.toByte(), 0xC0.toByte(), 0x01, 0x02)
        val dataOutcome = receiver.accept(followUpData)
        assertNull(dataOutcome.completed)
        org.junit.Assert.assertTrue(dataOutcome.replies.isEmpty())
    }

    @Test
    fun reassemblesFirstDataAndLastDataPacketsUsingWireFlags() {
        val receiver = Iap2FileTransferReceiver()
        val payload1 = byteArrayOf(0x01, 0x02)
        val payload2 = byteArrayOf(0x03, 0x04)

        // Setup packet: ID=5, Flags=0x04 (Setup), size=4
        val setup = ByteArray(12).apply {
            this[0] = 5.toByte()
            this[1] = 0x04.toByte()
            this[9] = 4.toByte()
            this[11] = 2.toByte()
        }
        receiver.accept(setup)

        // Data chunk 1 with FirstData flag (0x80)
        val p1 = byteArrayOf(5.toByte(), 0x80.toByte()) + payload1
        val p1Outcome = receiver.accept(p1)
        assertNull(p1Outcome.completed)

        // Data chunk 2 with LastData flag (0x40)
        val p2 = byteArrayOf(5.toByte(), 0x40.toByte()) + payload2
        val outcome = receiver.accept(p2)
        assertNotNull(outcome.completed)
        assertEquals(5, outcome.completed?.id)
        assertArrayEquals(payload1 + payload2, outcome.completed?.bytes)
    }

    @Test
    fun reassemblesSinglePacketArtworkUsingFirstAndOnlyFlag() {
        val receiver = Iap2FileTransferReceiver()
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())

        // Setup packet: ID=6, Flags=0x04 (Setup), size=3
        val setup = ByteArray(12).apply {
            this[0] = 6.toByte()
            this[1] = 0x04.toByte()
            this[9] = 3.toByte()
            this[11] = 2.toByte()
        }
        receiver.accept(setup)

        // FirstAndOnlyData flag (0x80 | 0x40 = 0xC0)
        val packet = byteArrayOf(6.toByte(), 0xC0.toByte()) + payload
        val outcome = receiver.accept(packet)
        assertNotNull(outcome.completed)
        assertEquals(6, outcome.completed?.id)
        assertArrayEquals(payload, outcome.completed?.bytes)
    }

    @Test
    fun evictingOldestPendingTransferSendsCancelReply() {
        val receiver = Iap2FileTransferReceiver()
        // Setup 4 transfers (the maximum pending)
        for (id in 1..4) {
            val setup = ByteArray(12).apply {
                this[0] = id.toByte()
                this[1] = 0x04.toByte()
                this[9] = 10.toByte()
                this[11] = 2.toByte()
            }
            receiver.accept(setup)
        }

        // Setup a 5th transfer: ID 1 must be evicted and a cancel reply for ID 1 must be sent
        val setup5 = ByteArray(12).apply {
            this[0] = 5.toByte()
            this[1] = 0x04.toByte()
            this[9] = 10.toByte()
            this[11] = 2.toByte()
        }
        val outcome = receiver.accept(setup5)
        assertEquals(2, outcome.replies.size)
        // reply 0: start(5)
        assertEquals(5.toByte(), outcome.replies[0][0])
        assertEquals(1.toByte(), outcome.replies[0][1]) // OPCODE_START = 1
        // reply 1: cancel(1)
        assertEquals(1.toByte(), outcome.replies[1][0])
        assertEquals(2.toByte(), outcome.replies[1][1]) // OPCODE_CANCEL = 2
    }
}
