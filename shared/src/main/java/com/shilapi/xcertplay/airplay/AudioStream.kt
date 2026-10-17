package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        /**
         * [wire] is valid only until this method returns. [rtp], when present, is a
         * private copy that [onRtp] also receives; the caller will not overwrite it.
         */
        fun onPacket(
            wire: ByteArray,
            wireLength: Int,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false

    fun listen(listener: Listener): Pair<Int, Int> {
        val data = bindAnyPort()
        // Keep short Wi-Fi bursts in the kernel while decrypting or scheduling pauses
        // the receive thread. The platform may cap this request; log the actual size.
        val originalBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
        if (originalBufferBytes < AUDIO_RECEIVE_BUFFER_BYTES) {
            runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
        }
        onDiagnostic("Audio UDP receive buffer type=$streamType original=$originalBufferBytes requested=$AUDIO_RECEIVE_BUFFER_BYTES actual=${runCatching { data.receiveBufferSize }.getOrDefault(0)}")
        val control = bindAnyPort()
        dataSocket = data
        controlSocket = control
        dataThread = Thread({ runData(data, listener) }, "airplay-audio-rx").apply {
            isDaemon = true
            start()
        }
        controlThread = Thread({ runControl(control) }, "airplay-rtcp-rx").apply {
            isDaemon = true
            start()
        }
        return data.localPort to control.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val buffer = ByteArray(DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        val aad = ByteArray(RTP_HEADER_LEN - 4)
        val nonce = ByteArray(12)
        val plaintext = ByteArray(DATAGRAM_BYTES)
        val cipher = AirPlayAead(
            forEncryption = false,
            onFallback = { onDiagnostic("audio aead fell back to BouncyCastle: ${it.javaClass.simpleName}") },
        )
        try {
            while (!closed.get()) {
                // receive leaves length at the previous datagram. Without this reset
                // the next read would truncate to that shorter size.
                packet.length = buffer.size
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                val length = packet.length
                stats.received(
                    size = length,
                    sequence = if (length >= RTP_HEADER_LEN) {
                        ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                    } else null,
                    timestamp = if (length >= RTP_HEADER_LEN) readU32Be(buffer, 4) else null,
                )
                val packetNumber = receivedPackets.incrementAndGet()
                if (length < RTP_HEADER_LEN + TAIL_LEN) {
                    if (packetNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType short packet bytes=$length",
                        )
                    }
                    listener.onPacket(
                        buffer,
                        length,
                        null,
                        null,
                        IOException("audio packet shorter than RTP header plus tail"),
                    )
                    stats.processed()
                    continue
                }

                buffer.copyInto(aad, 0, 4, RTP_HEADER_LEN)
                val sealedEnd = length - NONCE_LEN
                val sealedLength = sealedEnd - RTP_HEADER_LEN
                buffer.copyInto(nonce, 4, sealedEnd, length)
                val sample = readU32Be(buffer, 4)

                val plainLength = try {
                    cipher.process(
                        key,
                        nonce,
                        buffer,
                        RTP_HEADER_LEN,
                        sealedLength,
                        aad,
                        plaintext,
                        0,
                    )
                } catch (error: Exception) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    if (failureNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType first decrypt failure " +
                                "wire=${buffer.toHexString(length)}",
                            error,
                        )
                    }
                    listener.onPacket(buffer, length, null, sample, error)
                    stats.processed()
                    continue
                }
                // onRtp queues this copy on the decoder thread. The reused plaintext
                // buffer would be overwritten by the next datagram.
                val rtp = ByteArray(RTP_HEADER_LEN + plainLength)
                buffer.copyInto(rtp, 0, 0, RTP_HEADER_LEN)
                plaintext.copyInto(rtp, RTP_HEADER_LEN, 0, plainLength)
                val decryptedNumber = decryptedPackets.incrementAndGet()
                if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                            "wireBytes=$length payloadBytes=$plainLength " +
                            "payloadHead=${plaintext.toHexString(minOf(plainLength, 16))}",
                    )
                } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType decrypted=$decryptedNumber " +
                            "authFailures=${authenticationFailures.get()}",
                    )
                }
                listener.onPacket(buffer, length, rtp, sample, null)
                if (!started) {
                    started = true
                    listener.onStarted(sample)
                }
                listener.onRtp(rtp, sample)
                stats.processed()
            }
        } finally { stats.flush(ended = true) }
    }

    private fun runControl(socket: DatagramSocket) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun bindAnyPort(): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        return socket
    }

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun ByteArray.toHexString(length: Int): String {
        val count = minOf(length, size)
        val chars = CharArray(count * 2)
        for (index in 0 until count) {
            val value = this[index].toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(chars)
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100
        val HEX = "0123456789abcdef".toCharArray()
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        val pcm = PCM_FORMAT[bits]
        return when {
            isOpus -> AudioFormat(AudioCodecKind.OPUS, 48_000, 1, payloadType, audioType)
            isAacLc -> AudioFormat(
                AudioCodecKind.AAC_LC,
                if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                2,
                payloadType,
                audioType,
            )
            pcm != null -> AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )
}
