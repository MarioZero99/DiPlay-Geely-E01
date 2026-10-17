package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LinkEngineSessionTest {

    @Test
    fun controlSessionOnlySyncFrameDeclaresJustTheControlSession() {
        val engine = Iap2LinkEngine(Iap2LinkConfig(declareOptionalSessions = false))
        engine.start(wiredInitiator = false, nowMillis = 0)
        assertEquals(IAP2_MARKER.size, engine.takeOutput().size)

        engine.feed(IAP2_MARKER, 1)

        val syncFrame = engine.takeOutput()
        // 9 header + 10 sync payload + (1 session * 3 bytes) + 1 checksum = 23 bytes
        assertEquals(23, syncFrame.size)
        // Control byte (SYN)
        assertEquals(0x80, syncFrame[4].toInt() and 0xff)
    }

    /**
     * The default is the declaration the accessory has always made.
     *
     * A route that needs fewer sessions has to say so; the wired link — the one that works on the
     * car — must not lose EA because a wireless change moved the default under it.
     */
    @Test
    fun defaultSyncFrameDeclaresAllThreeSessions() {
        val engine = Iap2LinkEngine(Iap2LinkConfig())
        engine.start(wiredInitiator = false, nowMillis = 0)
        assertEquals(IAP2_MARKER.size, engine.takeOutput().size)

        engine.feed(IAP2_MARKER, 1)

        val syncFrame = engine.takeOutput()
        // 9 header + 10 sync payload + (3 sessions * 3 bytes) + 1 checksum = 29 bytes
        assertEquals(29, syncFrame.size)
        assertEquals(0x80, syncFrame[4].toInt() and 0xff)
    }

    @Test
    fun unhandledSessionTracesWarningInsteadOfSilentDiscard() {
        val traces = mutableListOf<String>()
        val engine = Iap2LinkEngine(
            Iap2LinkConfig(declareOptionalSessions = true),
            onTrace = { traces += it },
        )
        engine.start(wiredInitiator = false, nowMillis = 0)
        engine.takeOutput() // drain marker
        engine.feed(IAP2_MARKER, 1)
        engine.takeOutput() // drain sync

        // Feed peer synchronization
        val peerSync = frame(
            control = 0x80 or 0x40,
            sequence = PEER_SEQUENCE,
            acknowledgement = OUR_SEQUENCE,
            sessionId = 0,
            payload = PEER_SYNCHRONIZATION,
        )
        engine.feed(peerSync, 2)
        engine.takeOutput() // drain ack

        // Feed payload on session 11 (EA) with next in-order sequence
        val eaPayload = byteArrayOf(0x01, 0x02, 0x03)
        val eaFrame = frame(
            control = 0x00,
            sequence = PEER_SEQUENCE + 1,
            acknowledgement = OUR_SEQUENCE,
            sessionId = Iap2LinkEngine.EA_SESSION_ID,
            payload = eaPayload,
        )
        engine.feed(eaFrame, 3)

        assertTrue(
            "Expected trace for unhandled session 11, but got: $traces",
            traces.any { it.contains("iap2 rx unhandled session=11 payload_bytes=3") },
        )
    }

    private fun frame(
        control: Int,
        sequence: Int,
        acknowledgement: Int,
        sessionId: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val length = if (payload.isEmpty()) 9 else 9 + payload.size + 1
        val header = byteArrayOf(
            0xff.toByte(), 0x5a,
            (length ushr 8).toByte(), length.toByte(),
            control.toByte(), sequence.toByte(), acknowledgement.toByte(), sessionId.toByte(),
            0,
        )
        header[8] = (-header.take(8).sumOf { it.toInt() and 0xff }).toByte()
        if (payload.isEmpty()) return header
        return header + payload + byteArrayOf((-payload.sumOf { it.toInt() and 0xff }).toByte())
    }

    companion object {
        val IAP2_MARKER = byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10)
        val PEER_SYNCHRONIZATION = byteArrayOf(
            0x01, 0x7f, 0xff.toByte(), 0xff.toByte(),
            0x0f, 0xa0.toByte(), 0x01, 0xf4.toByte(), 0x04, 0x03,
            0x0a, 0x00, 0x02,
        )
        const val OUR_SEQUENCE = 0x63
        const val PEER_SEQUENCE = 0x40
    }
}
