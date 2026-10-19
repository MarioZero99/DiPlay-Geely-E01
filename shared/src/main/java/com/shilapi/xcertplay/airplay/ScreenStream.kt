package com.shilapi.xcertplay.airplay

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(private val key: ByteArray, private val onDiagnostic: (String) -> Unit = {}) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private val headerBuffer = ByteArray(HEADER_LEN)

    /** Reused only up to [MAX_REUSED_BODY_BYTES]. Larger frames get a one-shot array. */
    @Volatile private var bodyBuffer = ByteArray(0)
    private val frameCipher = AirPlayAead(
        forEncryption = false,
        onFallback = { onDiagnostic("video aead fell back to BouncyCastle: ${it.javaClass.simpleName}") },
    )
    private val frameNonce = ByteArray(12)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
        // The media sink keeps a recovery handler that captures this stream, so the instance
        // outlives the connection. Drop the buffer rather than carry it into the next session.
        bodyBuffer = ByteArray(0)
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            configureSocket(accepted)
            socket = accepted
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }


    private fun run(sock: Socket) {
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video", onDiagnostic)
        try {
            val input = sock.getInputStream()
            while (!closed.get()) {
                stats.reading()
                if (!readFully(input, headerBuffer, HEADER_LEN)) break
                val bodySize = readU32Le(headerBuffer, 0)
                if (bodySize > MAX_BODY) break
                val body = if (bodySize <= MAX_REUSED_BODY_BYTES) {
                    if (bodyBuffer.size < bodySize) bodyBuffer = ByteArray(bodySize)
                    bodyBuffer
                } else {
                    ByteArray(bodySize)
                }
                if (!readFully(input, body, bodySize)) break
                stats.received(HEADER_LEN + bodySize)
                onMessage(headerBuffer, body, bodySize, stats)
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(
        header: ByteArray,
        body: ByteArray,
        bodyLength: Int,
        stats: StreamReceiveStats,
    ) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                // decryptFrame returns a new array. bodyBuffer is filled again on the
                // next read, and VideoDecodeQueue keeps the returned bytes on the
                // decoder thread. A frame shorter than the tag is copied for the same
                // reason: handing back bodyBuffer would let the next frame overwrite it.
                val payload = if (bodyLength >= ScreenCodec.TAG_SIZE) {
                    val start = System.nanoTime()
                    ScreenCodec.decryptFrame(
                        key,
                        frameCounter.get(),
                        header,
                        body,
                        bodyLength,
                        frameCipher,
                        frameNonce,
                    ).also {
                        stats.decrypted(System.nanoTime() - start, bodyLength)
                        frameCounter.incrementAndGet()
                    }
                } else {
                    body.copyOf(bodyLength)
                }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=$bodyLength plain=${payload.size} " +
                        "aead=${frameCipher.path} head=${payload.hexPrefix(16)}",
                    )
                }
                listener.onFrame(ScreenCodec.lengthPrefixedToAnnexB(payload))
            }
            OP_VIDEO_CONFIG -> {
                val exact = body.copyOf(bodyLength)
                val (codec, codecData) = ScreenCodec.detectConfig(exact)
                Log.i(TAG, "video codec config codec=$codec body=$bodyLength data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readFully(input: InputStream, output: ByteArray, length: Int): Boolean {
        if (length < 0 || length > output.size) return false
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
        const val VIDEO_RECEIVE_BUFFER_BYTES = 1024 * 1024

        fun configureSocket(sock: Socket) {
            runCatching { sock.tcpNoDelay = true }
            runCatching { sock.receiveBufferSize = VIDEO_RECEIVE_BUFFER_BYTES }
        }

        /**
         * Ceiling for the reused body buffer. Real frames are far smaller; above this the frame
         * is allocated once and dropped, so a single oversized frame cannot pin its length for
         * the life of the stream.
         */
        const val MAX_REUSED_BODY_BYTES = 1024 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /**
     * Decrypts one video frame into a new array.
     *
     * [body] may be a reused receive buffer longer than [bodyLength], and the caller
     * overwrites it on the next frame. [lengthPrefixedToAnnexB] mutates the returned
     * array, and the decode queue keeps that array on another thread. Do not decrypt
     * into [body] or into any other buffer the caller will reuse.
     *
     * [header] is associated data in full. It must be the 128-byte screen header,
     * not a larger buffer with a stale tail.
     */
    internal fun decryptFrame(
        key: ByteArray,
        counter: Long,
        header: ByteArray,
        body: ByteArray,
        bodyLength: Int,
        cipher: AirPlayAead,
        nonce: ByteArray,
    ): ByteArray {
        if (bodyLength < TAG_SIZE) return body.copyOf(bodyLength.coerceAtLeast(0))
        AirPlayCrypto.nonce64Into(counter, nonce)
        val output = ByteArray(bodyLength - TAG_SIZE)
        val written = cipher.process(key, nonce, body, 0, bodyLength, header, output, 0)
        return if (written == output.size) output else output.copyOf(written)
    }

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        if (payload.size < 4 || payload.startsWithStartCode()) return payload

        var offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            offset += 4
            if (length <= 0 || offset + length > payload.size) return payload
            offset += length
        }
        if (offset != payload.size) return payload

        offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
