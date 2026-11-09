package com.shilapi.xcertplay.instrument.fly

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * The cluster's own socket, both halves of it.
 *
 * The cluster opens a TCP connection to this app's port 1028 and sends 24-byte asks; the answer goes
 * back on the same connection. The picture goes the other way, as RTP/AVP H.264 over UDP to the
 * player's fixed address. One thread accepts connections, one reads asks, and the RTP writer is
 * called from whichever thread handed over a frame — it holds only its own sequence numbers.
 */
class ClusterFlyLink(
    private val port: Int = DEFAULT_PORT,
    rtpHost: String = RTP_HOST,
    rtpPort: Int = RTP_PORT,
    private val log: (String) -> Unit = {},
) {

    /** Called for each 24-byte ask; what it returns is written back on the same connection. */
    @Volatile
    var onPacket: ((ByteArray) -> List<ByteArray>)? = null

    @Volatile
    private var running = false

    @Volatile
    private var client: Socket? = null

    private val rtp = RtpWriter(rtpHost, rtpPort)

    /**
     * Starts listening, or does nothing if already listening.
     *
     * A port already taken is not fatal and not worth spinning on: the layer stays waiting for a
     * video, and the retry is slow enough to leave the CPU alone. The log says `busy` on the first
     * failure and then once every ten.
     */
    fun start() {
        if (running) return
        running = true
        thread(isDaemon = true, name = "cluster-fly-listen") { acceptLoop() }
    }

    /** Pushes one access unit. False means the frame was dropped, which is a counter, not a throw. */
    fun send(accessUnit: ByteArray): Boolean = if (running) rtp.send(accessUnit) else false

    fun close() {
        running = false
        try {
            client?.close()
        } catch (_: IOException) {
        }
        client = null
        rtp.close()
    }

    private fun acceptLoop() {
        var failures = 0
        while (running) {
            var server: ServerSocket? = null
            try {
                server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                log(ClusterFlyLog.tcp(TcpEvent.LISTEN))
                failures = 0
                while (running) {
                    val socket = server.accept()
                    log(ClusterFlyLog.tcp(TcpEvent.ACCEPT))
                    serve(socket)
                }
            } catch (_: Exception) {
                if (!running) return
                failures++
                if (failures == 1 || failures % 10 == 0) log(ClusterFlyLog.tcp(TcpEvent.BUSY))
                try {
                    Thread.sleep(RETRY_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
            } finally {
                try {
                    server?.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        client = socket
        try {
            // A quiet connection still needs the heartbeat, so a read timeout is the timer.
            socket.soTimeout = HEARTBEAT_MILLIS.toInt()
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val readBuffer = ByteArray(1024)
            val packet = ByteArray(ASK_SIZE)
            var filled = 0
            while (running) {
                val read = try {
                    input.read(readBuffer)
                } catch (_: SocketTimeoutException) {
                    output.write(ClusterFlyProtocol.heartbeat())
                    output.flush()
                    log(ClusterFlyLog.reply(FlyReply.HEARTBEAT))
                    continue
                }
                if (read < 0) break
                for (index in 0 until read) {
                    packet[filled++] = readBuffer[index]
                    if (filled == ASK_SIZE) {
                        val replies = onPacket?.invoke(packet.copyOf()).orEmpty()
                        for (reply in replies) {
                            output.write(reply)
                            output.flush()
                        }
                        filled = 0
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            client = null
            try {
                socket.close()
            } catch (_: IOException) {
            }
            if (running) log(ClusterFlyLog.tcp(TcpEvent.CLOSED))
        }
    }

    /**
     * RTP/AVP with H.264 payload type 96: single-NAL packets for the small ones, FU-A for the rest.
     *
     * The timestamps are a fixed step per access unit rather than a wall clock: the cluster's player
     * is not synchronising us with anything, and a fixed step keeps the stream monotonic across the
     * bursts a busy head unit produces.
     */
    private class RtpWriter(host: String, private val port: Int) {
        private val socket = DatagramSocket()
        private val address: InetAddress? = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
        private var sequence = 0
        private var timestamp = 0
        private val ssrc = java.util.Random().nextInt()

        fun send(accessUnit: ByteArray): Boolean {
            val nalus = split(accessUnit)
            if (nalus.isEmpty()) return false
            var sent = false
            for ((index, nalu) in nalus.withIndex()) {
                sent = sendNalu(nalu, marker = index == nalus.lastIndex) || sent
            }
            timestamp += TIMESTAMP_STEP
            return sent
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        private fun sendNalu(nalu: ByteArray, marker: Boolean): Boolean {
            if (nalu.isEmpty()) return false
            return if (nalu.size + RTP_HEADER_SIZE <= RTP_MTU) {
                sendPacket(nalu, marker, fragmentHeader = null)
            } else {
                sendFragments(nalu, marker)
            }
        }

        private fun sendFragments(nalu: ByteArray, marker: Boolean): Boolean {
            val nalHeader = nalu[0].toInt() and 0xFF
            val indicator = ((nalHeader and 0xE0) or FU_A_TYPE).toByte()
            val chunkSize = RTP_MTU - RTP_HEADER_SIZE - 2
            var offset = 1
            var sent = true
            while (offset < nalu.size) {
                val size = minOf(chunkSize, nalu.size - offset)
                val start = offset == 1
                val end = offset + size == nalu.size
                val fu = byteArrayOf(
                    indicator,
                    ((if (start) 0x80 else 0) or (if (end) 0x40 else 0) or (nalHeader and 0x1F)).toByte(),
                )
                sent = sendPacket(nalu.copyOfRange(offset, offset + size), end && marker, fu) && sent
                offset += size
            }
            return sent
        }

        private fun sendPacket(payload: ByteArray, marker: Boolean, fragmentHeader: ByteArray?): Boolean {
            val header = fragmentHeader?.size ?: 0
            val packet = ByteArray(RTP_HEADER_SIZE + header + payload.size)
            writeHeader(packet, marker)
            var at = RTP_HEADER_SIZE
            if (fragmentHeader != null) {
                System.arraycopy(fragmentHeader, 0, packet, at, fragmentHeader.size)
                at += fragmentHeader.size
            }
            System.arraycopy(payload, 0, packet, at, payload.size)
            val target = address ?: return false
            return try {
                socket.send(DatagramPacket(packet, packet.size, target, port))
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun writeHeader(packet: ByteArray, marker: Boolean) {
            packet[0] = 0x80.toByte()
            packet[1] = (PAYLOAD_TYPE or (if (marker) 0x80 else 0)).toByte()
            packet[2] = (sequence shr 8).toByte()
            packet[3] = sequence.toByte()
            sequence = (sequence + 1) and 0xFFFF
            packet[4] = (timestamp shr 24).toByte()
            packet[5] = (timestamp shr 16).toByte()
            packet[6] = (timestamp shr 8).toByte()
            packet[7] = timestamp.toByte()
            packet[8] = (ssrc shr 24).toByte()
            packet[9] = (ssrc shr 16).toByte()
            packet[10] = (ssrc shr 8).toByte()
            packet[11] = ssrc.toByte()
        }

        /** One byte array in, one NAL per entry out, start codes removed. */
        private fun split(unit: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Pair<Int, Int>>()
            var i = 0
            while (i + 3 <= unit.size) {
                if (
                    i + 4 <= unit.size &&
                    unit[i].toInt() == 0 && unit[i + 1].toInt() == 0 &&
                    unit[i + 2].toInt() == 0 && unit[i + 3].toInt() == 1
                ) {
                    starts.add(i to i + 4)
                    i += 4
                    continue
                }
                if (unit[i].toInt() == 0 && unit[i + 1].toInt() == 0 && unit[i + 2].toInt() == 1) {
                    starts.add(i to i + 3)
                    i += 3
                    continue
                }
                i++
            }
            if (starts.isEmpty()) return listOf(unit)
            return starts.mapIndexedNotNull { index, (_, nalStart) ->
                val end = if (index + 1 < starts.size) starts[index + 1].first else unit.size
                if (end > nalStart) unit.copyOfRange(nalStart, end) else null
            }
        }
    }

    private companion object {
        const val DEFAULT_PORT = 1028
        const val RTP_HOST = "10.255.255.106"
        const val RTP_PORT = 9026
        const val ASK_SIZE = 24
        const val HEARTBEAT_MILLIS = 3_000L
        const val RETRY_MILLIS = 3_000L

        const val RTP_HEADER_SIZE = 12
        const val RTP_MTU = 1_400
        const val PAYLOAD_TYPE = 96
        const val FU_A_TYPE = 28

        /** 90 kHz / 25 pictures a second. */
        const val TIMESTAMP_STEP = 3_600
    }
}
