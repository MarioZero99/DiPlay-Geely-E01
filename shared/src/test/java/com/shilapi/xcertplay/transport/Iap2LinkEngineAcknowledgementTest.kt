package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A frame a peer sends with no payload still has to be answered.
 *
 * An acknowledgement-only frame is nine bytes of header and nothing else, and the peer numbers and
 * times it like any other: after it, the peer expects this side's acknowledgement field to have
 * moved, inside the acknowledgement timeout the peer advertised in its synchronization frame. A
 * bench run where the phone answered our certificate with exactly such a frame is what this covers:
 * the engine went silent for forty seconds afterwards, and the phone never sent the challenge that
 * should have followed.
 *
 * Frames are built here as literals rather than from the engine's own constants, so that an engine
 * that agrees with itself about a wrong layout cannot make these pass.
 */
class Iap2LinkEngineAcknowledgementTest {
    @Test fun aFrameWithNoPayloadIsAcknowledgedWithRoomInsideThePeersTimeout() {
        val engine = openNegotiatedLink()

        // The phone's own numbering: it sends the next sequence number, and carries our sequence in
        // its acknowledgement field. No payload at all -- this frame says only "I got your packet".
        engine.feed(frame(control = 0x40, sequence = PEER_SEQUENCE + 1, acknowledgement = OUR_SEQUENCE, sessionId = 0), A_FEW_MILLIS_LATER)
        assertTrue("an acknowledgement-only frame must not be answered at once", engine.takeOutput().isEmpty())

        // Answering on the deadline itself is too late: the peer has already given up by the time the
        // pump reaches it, and the radio still has to carry the answer. Half the window is the room.
        val halfWindow = A_FEW_MILLIS_LATER + PEER_ACKNOWLEDGEMENT_TIMEOUT_MILLIS / 2
        engine.advanceTime(halfWindow - 1)
        assertTrue("the answer must be held until half the peer's window is gone", engine.takeOutput().isEmpty())

        engine.advanceTime(halfWindow)

        val answer = engine.takeOutput()
        assertEquals("the peer's acknowledgement-only frame was never answered", 9, answer.size)
        assertEquals(0x40, answer[4].toInt() and 0xff)
        assertEquals("the answer must acknowledge the sequence the peer just used", PEER_SEQUENCE + 1, answer[6].toInt() and 0xff)
    }

    @Test fun aFrameThatRepeatsTheLastSequenceIsAcknowledgedAtOnce() {
        val engine = openNegotiatedLink()

        // The other numbering the phone uses on these frames: it repeats the sequence of the packet
        // it sent last. Nothing new arrived, but the peer is still waiting to be told so.
        engine.feed(frame(control = 0x40, sequence = PEER_SEQUENCE, acknowledgement = OUR_SEQUENCE, sessionId = 0), A_FEW_MILLIS_LATER)

        val answer = engine.takeOutput()
        assertEquals("a repeated sequence must be acknowledged without waiting for a timer", 9, answer.size)
        assertEquals(0x40, answer[4].toInt() and 0xff)
        assertEquals(PEER_SEQUENCE, answer[6].toInt() and 0xff)
    }

    @Test fun anAcknowledgementOnlyFrameIsNeverDeliveredAsControlData() {
        val engine = openNegotiatedLink()

        engine.feed(frame(control = 0x40, sequence = PEER_SEQUENCE + 1, acknowledgement = OUR_SEQUENCE, sessionId = Iap2LinkEngine.CONTROL_SESSION_ID), A_FEW_MILLIS_LATER)
        engine.advanceTime(A_FEW_MILLIS_LATER + PEER_ACKNOWLEDGEMENT_TIMEOUT_MILLIS)

        assertEquals("a frame with no payload carries no control message", null, engine.pollEvent())
    }

    @Test fun theControlPayloadThatFollowsItsOwnAcknowledgementIsStillDelivered() {
        val engine = openNegotiatedLink()

        // The phone puts its acknowledgement and its data under one sequence number, and the radio
        // decides which of the two arrives first. This is the order that used to lose the challenge:
        // the empty frame takes the sequence, and the control-session frame behind it -- same number,
        // a tenth of a second later -- looks like a repeat of a packet already read. The run then sat
        // waiting for a signature the phone had already asked for, and timed out.
        val challenge = byteArrayOf(0x40, 0x40, 0x00, 0x2a, 0xaa.toByte(), 0x02, 0x00, 0x24, 0x00, 0x00)
        engine.feed(frame(control = 0x40, sequence = PEER_SEQUENCE + 1, acknowledgement = OUR_SEQUENCE, sessionId = 0), A_FEW_MILLIS_LATER)
        engine.feed(
            frame(
                control = 0x40,
                sequence = PEER_SEQUENCE + 1,
                acknowledgement = OUR_SEQUENCE,
                sessionId = Iap2LinkEngine.CONTROL_SESSION_ID,
                payload = challenge,
            ),
            A_FEW_MILLIS_LATER + 107,
        )

        val event = engine.pollEvent()
        assertTrue(
            "the control payload must survive its own acknowledgement arriving first",
            event is Iap2LinkEngine.Event.Control,
        )
        assertEquals(challenge.toList(), (event as Iap2LinkEngine.Event.Control).bytes.toList())
    }

    /**
     * Brings a fresh engine to the state a wireless run reaches once the phone has synchronized:
     * marker exchanged, our synchronization sent, the phone's synchronization acknowledged.
     */
    private fun openNegotiatedLink(): Iap2LinkEngine {
        val engine = Iap2LinkEngine(Iap2LinkConfig(maxOutgoing = 4, controlSessionVersion = 2))
        engine.start(wiredInitiator = false, nowMillis = 0)
        assertEquals("starting a link should emit only the marker", IAP2_MARKER.size, engine.takeOutput().size)

        engine.feed(IAP2_MARKER, 1)

        val synchronization = engine.takeOutput()
        assertEquals("the marker should have produced exactly one synchronization frame", 29, synchronization.size)
        assertEquals(0x80, synchronization[4].toInt() and 0xff)
        assertEquals(OUR_SEQUENCE, synchronization[5].toInt() and 0xff)

        engine.feed(
            frame(
                control = 0x80 or 0x40,
                sequence = PEER_SEQUENCE,
                acknowledgement = OUR_SEQUENCE,
                sessionId = 0,
                payload = PEER_SYNCHRONIZATION,
            ),
            A_FEW_MILLIS_LATER,
        )
        assertEquals("the phone's synchronization should have been acknowledged", 9, engine.takeOutput().size)
        assertTrue("the phone's synchronization should have made the link writable", engine.writable())
        while (engine.pollEvent() != null) {
            // Negotiation's own events are not what these tests are about.
        }
        return engine
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

    private companion object {
        /** The iAP2 marker this side emits to open a link. */
        val IAP2_MARKER = byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10)

        /**
         * The synchronization payload an iPhone sends, byte for byte: version 1, 127 packets
         * outstanding, 65535-byte frames, resend after 4000 ms, acknowledge within 500 ms, four
         * retransmissions, an acknowledgement every three packets, control session 10 version 2.
         */
        val PEER_SYNCHRONIZATION = byteArrayOf(
            0x01, 0x7f, 0xff.toByte(), 0xff.toByte(),
            0x0f, 0xa0.toByte(), 0x01, 0xf4.toByte(), 0x04, 0x03,
            0x0a, 0x00, 0x02,
        )

        /** Where this side starts numbering, and where the phone's first frame lands. */
        const val OUR_SEQUENCE = 0x63
        const val PEER_SEQUENCE = 0x40
        const val PEER_ACKNOWLEDGEMENT_TIMEOUT_MILLIS = 500L
        const val A_FEW_MILLIS_LATER = 10L
    }
}
