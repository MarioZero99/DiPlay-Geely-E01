package com.shilapi.xcertplay.instrument.fly

/**
 * The bytes the cluster speaks on 1028, and the bytes it expects back.
 *
 * The packet is a 20-byte header — `03 F0`, `00 01`, the 2-byte function, a 2-byte tail, `00 00`,
 * `5A A5`, four zero bytes, and a 4-byte length — followed by the status word. The length is written
 * big-endian, the same way as the function: the same source sends both, and mixing the two would be a
 * wire bug nothing on our side could explain.
 */
object ClusterFlyProtocol {

    private const val HEADER_SIZE = 20
    private const val PACKET_SIZE = 24

    /** The cluster's own heartbeat: function 100, no payload. */
    const val FUNCTION_HEARTBEAT = 0x0064

    /** The cluster's question about the picture: function 301. */
    const val FUNCTION_ASK = 0x012D

    /**
     * The cluster's "are you still there": function 303.
     *
     * It shares its number with [videoStart], which is the factory's habit rather than a mistake on
     * this side: the same function goes both ways, the direction is what tells them apart.
     */
    const val FUNCTION_STILL_THERE = 0x012F

    fun parse(packet: ByteArray): VideoAsk {
        if (packet.size != PACKET_SIZE) return VideoAsk.IGNORE
        val function = u16(packet, 4)
        return when (function) {
            FUNCTION_ASK ->
                if ((packet[PACKET_SIZE - 1].toInt() and 0xFF) == 0) VideoAsk.OPEN else VideoAsk.CLOSE
            FUNCTION_STILL_THERE -> VideoAsk.HEARTBEAT
            else -> VideoAsk.IGNORE
        }
    }

    /** "I am here." Sent every 3000 ms while the connection is open. */
    fun heartbeat(): ByteArray = reply(FUNCTION_HEARTBEAT, 0x0157, length = 0, status = 0)

    /** "The picture is ready, ask for it." The only reply that means yes. */
    fun videoReady(): ByteArray = reply(FUNCTION_ASK, 0x0253, length = 4, status = 2)

    /** "Not now." Sent when the session is not waiting for or pushing a picture. */
    fun videoRefused(): ByteArray = reply(FUNCTION_ASK, 0x0253, length = 4, status = 0)

    fun videoStart(): ByteArray = reply(FUNCTION_STILL_THERE, 0x0408, length = 4, status = 0)

    fun videoStop(): ByteArray = reply(FUNCTION_STILL_THERE, 0x0408, length = 4, status = 1)

    private fun reply(function: Int, tail: Int, length: Int, status: Int): ByteArray {
        val packet = ByteArray(HEADER_SIZE + length)
        packet[0] = 0x03
        packet[1] = 0xF0.toByte()
        packet[2] = 0x00
        packet[3] = 0x01
        packet[4] = (function shr 8).toByte()
        packet[5] = function.toByte()
        packet[6] = (tail shr 8).toByte()
        packet[7] = tail.toByte()
        packet[8] = 0x00
        packet[9] = 0x00
        packet[10] = 0x5A
        packet[11] = 0xA5.toByte()
        // 12..15 are zero.
        packet[16] = (length shr 24).toByte()
        packet[17] = (length shr 16).toByte()
        packet[18] = (length shr 8).toByte()
        packet[19] = length.toByte()
        if (length > 0) packet[packet.size - 1] = status.toByte()
        return packet
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}
