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
    }
}
