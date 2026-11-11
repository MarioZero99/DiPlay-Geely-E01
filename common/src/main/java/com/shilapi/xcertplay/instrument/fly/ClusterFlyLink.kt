package com.shilapi.xcertplay.instrument.fly

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
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
    /**
     * Where this process listens. The cluster dials [BIND_HOST]; that address is added for the
     * session when it is missing, and a wildcard socket is what then receives the dial.
     */
    private val bindHost: String = LISTEN_HOST,
    private val rtpHost: String = RTP_HOST,
    private val rtpPort: Int = RTP_PORT,
    private val log: (String) -> Unit = {},
    private val clusterAddress: FlyClusterAddress = FlyClusterAddress(),
    private val portReport: (Exception, Int) -> List<String> = { error, listened ->
        FlyPortHolder.report(error, listened)
    },
) {

    /** Called for each 24-byte ask; what it returns is written back on the same connection. */
    @Volatile
    var onPacket: ((ByteArray) -> List<ByteArray>)? = null

    /** Writes back an answer that was produced outside an ask, such as a remembered open. */
    @Volatile
    private var replyWriter: ((ByteArray) -> Unit)? = null

    fun reply(packets: List<ByteArray>) {
        val writer = replyWriter ?: return
        packets.forEach(writer)
    }

    @Volatile
    private var running = false

    /** Bumped on every start and every close, so a restart does not keep the old listen thread. */
    private val listenGeneration = AtomicInteger()

    @Volatile
    private var client: Socket? = null

    @Volatile
    private var server: ServerSocket? = null

    /**
     * Rebuilt by every [start]. A finished link cannot be reopened with the old writer: its socket
     * is closed for good, and the UDP side has to be new for [send] to work again.
     */
    private var rtp: RtpWriter? = null

    /**
     * Starts listening, or does nothing if already listening.
     *
     * A port already taken is not fatal and not worth spinning on: the layer stays waiting for a
     * video, and the retry is slow enough to leave the CPU alone. The first failure, and every
     * tenth after it, names the exception. When the port is taken, the same lines name the holder.
     */
    fun start() {
        if (running) return
        running = true
        val generation = listenGeneration.incrementAndGet()
        thread(isDaemon = true, name = "cluster-fly-listen") {
            if (running && listenGeneration.get() == generation) {
                clusterAddress.prepare().forEach(log)
            }
            acceptLoop(generation)
        }
    }

    /** Pushes one access unit. False means the frame was dropped, which is a counter, not a throw. */
    fun send(accessUnit: ByteArray): Boolean = if (running) rtp?.send(accessUnit) ?: false else false

    fun close() {
        running = false
        listenGeneration.incrementAndGet()
        try {
            client?.close()
        } catch (_: IOException) {
        }
        client = null
        // Closing the server socket is what releases a thread parked in accept().
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        rtp?.close()
        rtp = null
        clusterAddress.release()
    }

    private fun acceptLoop(generation: Int) {
        var failures = 0
        while (running && listenGeneration.get() == generation) {
            var listening: ServerSocket? = null
            var writer: RtpWriter? = null
            try {
                // TCP listens on every interface. The cluster dials 10.255.255.101 once that
                // address is local; binding the socket to the address itself fails when it is not.
                // RTP uses an ephemeral port. The route, after the address is added, selects it
                // as the source toward 10.255.255.106.
                val udp = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
                writer = RtpWriter(udp, rtpHost, rtpPort)
                rtp = writer
                listening = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindHost, port))
                }
                server = listening
                log(ClusterFlyLog.tcp(TcpEvent.LISTEN))
                failures = 0
                while (running) {
                    val socket = listening.accept()
                    log(ClusterFlyLog.tcp(TcpEvent.ACCEPT))
                    serve(socket)
                }
            } catch (error: Exception) {
                if (!running || listenGeneration.get() != generation) return
                failures++
                if (failures == 1 || failures % 10 == 0) {
                    bindFailureLines(error, port, portReport).forEach(log)
                }
                try {
                    Thread.sleep(RETRY_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
            } finally {
                // Close only this attempt. A restart may already own the fields.
                try {
                    listening?.close()
                } catch (_: IOException) {
                }
                if (server === listening) server = null
                if (rtp === writer) rtp = null
                writer?.close()
            }
        }
    }

    private fun serve(socket: Socket) {
        client = socket
        val writeLock = Any()
        try {
            // The read timeout is only a way to notice `close()`; the heartbeat has a timer of its
            // own, because the factory sends one every three seconds whether the cluster is chatty
            // or not.
            socket.soTimeout = HEARTBEAT_MILLIS.toInt()
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val write: (ByteArray) -> Unit = { bytes ->
                synchronized(writeLock) {
                    output.write(bytes)
                    output.flush()
                }
            }
            replyWriter = write

            thread(isDaemon = true, name = "cluster-fly-heartbeat") {
                while (running && !socket.isClosed) {
                    try {
                        Thread.sleep(HEARTBEAT_MILLIS)
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                    if (!running || socket.isClosed) return@thread
                    try {
                        write(ClusterFlyProtocol.heartbeat())
                        log(ClusterFlyLog.reply(FlyReply.HEARTBEAT))
                    } catch (_: IOException) {
                        return@thread
                    }
                }
            }

            val readBuffer = ByteArray(1024)
            val packet = ByteArray(ASK_SIZE)
            var filled = 0
            while (running) {
                val read = try {
                    input.read(readBuffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (read < 0) break
                for (index in 0 until read) {
                    packet[filled++] = readBuffer[index]
                    if (filled == ASK_SIZE) {
                        val replies = onPacket?.invoke(packet.copyOf()).orEmpty()
                        for (reply in replies) write(reply)
                        filled = 0
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            client = null
            replyWriter = null
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
    private class RtpWriter(private val socket: DatagramSocket, host: String, private val port: Int) {
        private val address: InetAddress? = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
        private var sequence = 0
        private var timestamp = 0
        private val ssrc = java.util.Random().nextInt()

        /** Wall clock of the previous access unit: the timestamp follows arrival, not a fixed fps. */
        private var lastUnitAtMs = 0L

        /** Reused for every packet; grows only when one needs more room than the last. */
        private var packet = ByteArray(RTP_MTU + 16)

        private val fuHeader = ByteArray(2)

        fun send(accessUnit: ByteArray): Boolean {
            val nalus = nals(accessUnit)
            if (nalus.isEmpty()) return false
            val nowMs = System.nanoTime() / 1_000_000L
            if (lastUnitAtMs != 0L) {
                // 90 kHz, measured from the frame before: a 30 fps phone must not play at 25 fps
                // just because the step was written down as a constant.
                val delta = (nowMs - lastUnitAtMs).coerceIn(0L, MAX_STEP_MILLIS)
                timestamp += (delta * 90).toInt()
            }
            lastUnitAtMs = nowMs
            var sent = false
            for ((index, nal) in nalus.withIndex()) {
                sent = sendNal(accessUnit, nal.first, nal.last + 1, marker = index == nalus.lastIndex) || sent
            }
            return sent
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        private fun sendNal(unit: ByteArray, start: Int, end: Int, marker: Boolean): Boolean {
            val size = end - start
            if (size <= 0) return false
            return if (size + RTP_HEADER_SIZE <= RTP_MTU) {
                sendPacket(unit, start, size, marker, fragmentHeader = null)
            } else {
                sendFragments(unit, start, end, marker)
            }
        }

        private fun sendFragments(unit: ByteArray, start: Int, end: Int, marker: Boolean): Boolean {
            val nalHeader = unit[start].toInt() and 0xFF
            val indicator = ((nalHeader and 0xE0) or FU_A_TYPE).toByte()
            val chunkSize = RTP_MTU - RTP_HEADER_SIZE - 2
            var offset = start + 1
            var sent = true
            while (offset < end) {
                val size = minOf(chunkSize, end - offset)
                val first = offset == start + 1
                val last = offset + size == end
                fuHeader[0] = indicator
                fuHeader[1] =
                    ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or (nalHeader and 0x1F)).toByte()
                sent = sendPacket(unit, offset, size, last && marker, fuHeader) && sent
                offset += size
            }
            return sent
        }

        private fun sendPacket(
            unit: ByteArray,
            offset: Int,
            length: Int,
            marker: Boolean,
            fragmentHeader: ByteArray?,
        ): Boolean {
            val header = fragmentHeader?.size ?: 0
            val needed = RTP_HEADER_SIZE + header + length
            if (packet.size < needed) packet = ByteArray(needed)
            writeHeader(marker)
            var at = RTP_HEADER_SIZE
            if (fragmentHeader != null) {
                System.arraycopy(fragmentHeader, 0, packet, at, header)
                at += header
            }
            System.arraycopy(unit, offset, packet, at, length)
            val target = address ?: return false
            return try {
                socket.send(DatagramPacket(packet, needed, target, port))
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun writeHeader(marker: Boolean) {
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

        /** Ranges of the NAL units inside one access unit, start codes excluded, with no copy. */
        private fun nals(unit: ByteArray): List<IntRange> {
            if (unit.isEmpty()) return emptyList()
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
            if (starts.isEmpty()) return listOf(0 until unit.size)
            return starts.mapIndexedNotNull { index, (_, nalStart) ->
                val end = if (index + 1 < starts.size) starts[index + 1].first else unit.size
                if (end > nalStart) nalStart until end else null
            }
        }
    }

    companion object {
        /** Where this process listens. The cluster dials [BIND_HOST], not this wildcard. */
        const val LISTEN_HOST = "0.0.0.0"

        /** The address the cluster dials. Added for the session when no interface has it. */
        const val BIND_HOST = "10.255.255.101"

        private const val DEFAULT_PORT = 1028
        private const val RTP_HOST = "10.255.255.106"
        const val RTP_PORT = 9026
        const val ASK_SIZE = 24
        const val HEARTBEAT_MILLIS = 3_000L
        const val RETRY_MILLIS = 3_000L

        const val RTP_HEADER_SIZE = 12
        const val RTP_MTU = 1_400
        const val PAYLOAD_TYPE = 96
        const val FU_A_TYPE = 28

        /** The furthest the timestamp may move for one unit: a stall must not jump minutes ahead. */
        const val MAX_STEP_MILLIS = 200L
    }
}

/** The busy line, then the holder lines when the port is taken. */
internal fun bindFailureLines(
    error: Exception,
    port: Int,
    report: (Exception, Int) -> List<String>,
): List<String> = listOf(ClusterFlyLog.tcpBusy(error)) + if (port > 0) report(error, port) else emptyList()
