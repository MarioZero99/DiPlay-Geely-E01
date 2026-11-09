package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cluster's 24-byte packets, written out as literals.
 *
 * The samples here are the spec's bytes, not the class's own constants: a test that builds its input
 * out of the code it checks would pass with a wrong constant in both places.
 */
class ClusterFlyProtocolTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** A 24-byte ask: the function sits at bytes 4-5, the switch in the last byte. */
    private fun ask(function: Int, switch: Int): ByteArray {
        val packet = ByteArray(24)
        packet[0] = 0x03
        packet[1] = 0xF0.toByte()
        packet[2] = 0x00
        packet[3] = 0x01
        packet[4] = (function shr 8).toByte()
        packet[5] = function.toByte()
        packet[23] = switch.toByte()
        return packet
    }

    @Test
    fun `function 301 with the switch off asks for video`() {
        assertEquals(VideoAsk.OPEN, ClusterFlyProtocol.parse(ask(301, 0)))
    }

    @Test
    fun `function 301 with the switch on closes`() {
        assertEquals(VideoAsk.CLOSE, ClusterFlyProtocol.parse(ask(301, 1)))
    }

    @Test
    fun `function 303 is a heartbeat`() {
        assertEquals(VideoAsk.HEARTBEAT, ClusterFlyProtocol.parse(ask(303, 0)))
    }

    @Test
    fun `function 302 is the junction map`() {
        assertEquals(VideoAsk.PARTIAL_OPEN, ClusterFlyProtocol.parse(ask(302, 0)))
        assertEquals(VideoAsk.PARTIAL_CLOSE, ClusterFlyProtocol.parse(ask(302, 1)))
    }

    @Test
    fun `the junction map refusal is function 302 with an error`() {
        assertArrayEquals(
            bytes(
                0x03, 0xF0, 0x00, 0x01, 0x01, 0x2E, 0x02, 0x53, 0x00, 0x00, 0x5A, 0xA5,
                0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0,
            ),
            ClusterFlyProtocol.partialRefused(),
        )
    }

    @Test
    fun `a packet that is not 24 bytes is ignored`() {
        assertEquals(VideoAsk.IGNORE, ClusterFlyProtocol.parse(ByteArray(23)))
    }

    @Test
    fun `heartbeat is the bare 20 byte header`() {
        assertArrayEquals(
            bytes(
                0x03, 0xF0, 0x00, 0x01, 0x00, 0x64, 0x01, 0x57, 0x00, 0x00, 0x5A, 0xA5,
                0, 0, 0, 0, 0, 0, 0, 0,
            ),
            ClusterFlyProtocol.heartbeat(),
        )
    }

    @Test
    fun `ready and refused share a packet and differ in the last byte`() {
        assertArrayEquals(
            bytes(
                0x03, 0xF0, 0x00, 0x01, 0x01, 0x2D, 0x02, 0x53, 0x00, 0x00, 0x5A, 0xA5,
                0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 2,
            ),
            ClusterFlyProtocol.videoReady(),
        )
        assertArrayEquals(
            bytes(
                0x03, 0xF0, 0x00, 0x01, 0x01, 0x2D, 0x02, 0x53, 0x00, 0x00, 0x5A, 0xA5,
                0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0,
            ),
            ClusterFlyProtocol.videoRefused(),
        )
    }

    @Test
    fun `start and stop share a packet and differ in the last byte`() {
        assertArrayEquals(
            bytes(
                0x03, 0xF0, 0x00, 0x01, 0x01, 0x2F, 0x04, 0x08, 0x00, 0x00, 0x5A, 0xA5,
                0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0,
            ),
            ClusterFlyProtocol.videoStart(),
        )
        assertArrayEquals(
            bytes(
                0x03, 0xF0, 0x00, 0x01, 0x01, 0x2F, 0x04, 0x08, 0x00, 0x00, 0x5A, 0xA5,
                0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 1,
            ),
            ClusterFlyProtocol.videoStop(),
        )
    }
}
