package com.shilapi.xcertplay.instrument.fly

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.shilapi.xcertplay.DiagLog
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.nio.ByteBuffer

/**
 * Decodes the phone's picture and encodes it again as Baseline, because that is the only profile
 * the factory ever pushes to this cluster (`mp.java` pins `profile = 1`).
 *
 * Everything here runs on the fly thread. Every dequeue uses a zero timeout: when the codecs are
 * busy the frame is dropped and counted, never waited for, because the centre screen must not slow
 * down for the cluster. Any failure closes both codecs and reports `false` from then on; the layer
 * stays blank rather than risking the main picture.
 */
class ClusterFlyReencoderMediaCodec(
    config: ByteArray,
    private val onEncoded: (ByteArray) -> Unit,
) : ClusterFlyReencoder {

    private val decoder: MediaCodec? = createDecoder(config)
    private var encoder: MediaCodec? = null
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var encoderColor = 0
    private var frameWidth = 0
    private var frameHeight = 0
    private var semiPlanarSource = true
    private var presentationTimeUs = 0L
    private var failed = false
    private val info = MediaCodec.BufferInfo()

    override fun submit(nalu: ByteArray, nowMillis: Long): Boolean {
        if (failed) return false
        val decoder = decoder ?: return false
        return try {
            feedDecoder(decoder, nalu)
        } catch (error: Exception) {
            fail(error)
            false
        }
    }

    override fun close() {
        failed = true
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
    }

    private fun createDecoder(config: ByteArray): MediaCodec? = try {
        val (sps, pps) = AvcConfig.parameterSets(config) ?: return null
        val format = MediaFormat.createVideoFormat(Mime, NominalWidth, NominalHeight).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(startCode(sps)))
            setByteBuffer("csd-1", ByteBuffer.wrap(startCode(pps)))
        }
        MediaCodec.createDecoderByType(Mime).also {
            it.configure(format, null, null, 0)
            it.start()
        }
    } catch (error: Exception) {
        DiagLog.w(TAG, "cluster decoder unavailable", error)
        null
    }

    private fun feedDecoder(decoder: MediaCodec, nalu: ByteArray): Boolean {
        val input = decoder.dequeueInputBuffer(0)
        if (input < 0) return false
        val buffer = decoder.getInputBuffer(input)
        if (buffer == null) {
            decoder.queueInputBuffer(input, 0, 0, presentationTimeUs, 0)
            return false
        }
        buffer.clear()
        buffer.put(nalu)
        decoder.queueInputBuffer(input, 0, nalu.size, presentationTimeUs, 0)
        presentationTimeUs += InputStepUs
        drainDecoder(decoder)
        drainEncoder()
        return true
    }

    private fun drainDecoder(decoder: MediaCodec) {
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    frameWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    frameHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                }

                index >= 0 -> {
                    try {
                        encodeFrame(decoder, index)
                    } finally {
                        decoder.releaseOutputBuffer(index, false)
                    }
                }

                else -> return
            }
        }
    }

    private fun encodeFrame(decoder: MediaCodec, index: Int) {
        val image = try {
            decoder.getOutputImage(index)
        } catch (_: Exception) {
            null
        } ?: return

        val encoder = encoder ?: createEncoder(image) ?: return
        val input = encoder.dequeueInputBuffer(0)
        if (input < 0) return
        val target = encoder.getInputBuffer(input)
        if (target == null || !copyImage(image, target)) {
            encoder.queueInputBuffer(input, 0, 0, presentationTimeUs, 0)
            return
        }
        encoder.queueInputBuffer(input, 0, encoderWidth * encoderHeight * 3 / 2, presentationTimeUs, 0)
        presentationTimeUs += InputStepUs
    }

    private fun createEncoder(image: Image): MediaCodec? {
        val width = if (frameWidth > 0) frameWidth else image.width
        val height = if (frameHeight > 0) frameHeight else image.height
        encoderWidth = (width / 16) * 16
        encoderHeight = (height / 2) * 2
        if (encoderWidth <= 0 || encoderHeight <= 0) return null

        val planes = image.planes
        semiPlanarSource = planes.size < 3 || planes[1].pixelStride == 2
        encoderColor = if (semiPlanarSource) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        } else {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        }

        return try {
            val format = MediaFormat.createVideoFormat(Mime, encoderWidth, encoderHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, encoderColor)
                setInteger(MediaFormat.KEY_BIT_RATE, BitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, FrameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KeyframeSeconds)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, maxBaselineLevel())
            }
            MediaCodec.createEncoderByType(Mime).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
        } catch (error: Exception) {
            DiagLog.w(TAG, "cluster encoder unavailable size=${encoderWidth}x$encoderHeight", error)
            null
        }
    }

    /**
     * Copies the decoded planes into the encoder's buffer.
     *
     * The encoder is configured to the source's own layout — semi-planar when U and V are already
     * interleaved, planar otherwise — so this is a row-by-row copy and never a colour conversion.
     * A width that the encoder padded to 16 is filled with zeroes; the encoder treats it as the
     * right-hand edge.
     */
    private fun copyImage(image: Image, target: ByteBuffer): Boolean {
        val planes = image.planes
        if (planes.isEmpty()) return false
        target.clear()

        if (!copyPlane(planes[0], target, image.width, image.height, encoderWidth, encoderHeight, 0)) {
            return false
        }
        val chromaHeight = encoderHeight / 2
        val lumaSize = encoderWidth * encoderHeight
        if (semiPlanarSource) {
            return copyPlane(planes[1], target, image.width, chromaHeight * 2, encoderWidth, chromaHeight, lumaSize)
        }
        if (planes.size < 3) return false
        val chromaWidth = encoderWidth / 2
        if (!copyPlane(planes[1], target, image.width / 2, image.height / 2, chromaWidth, chromaHeight, lumaSize)) {
            return false
        }
        return copyPlane(
            planes[2], target, image.width / 2, image.height / 2, chromaWidth, chromaHeight,
            lumaSize + chromaWidth * chromaHeight,
        )
    }

    /** Copies [sourceWidth] bytes of each of [rows] rows, padding the rest of the row with zeroes. */
    private fun copyPlane(
        plane: Image.Plane,
        target: ByteBuffer,
        sourceWidth: Int,
        sourceRows: Int,
        targetStride: Int,
        rows: Int,
        offset: Int,
    ): Boolean {
        val source = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        for (row in 0 until rows) {
            target.position(offset + row * targetStride)
            if (row < sourceRows) {
                val from = row * rowStride
                val count = minOf(sourceWidth, targetStride, source.capacity() - from)
                if (count > 0) {
                    source.limit(from + count).position(from)
                    target.put(source)
                    if (count < targetStride) repeat(targetStride - count) { target.put(0) }
                } else {
                    repeat(targetStride) { target.put(0) }
                }
            } else {
                repeat(targetStride) { target.put(0) }
            }
        }
        return true
    }

    private fun drainEncoder() {
        val encoder = encoder ?: return
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val out = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(out)
                        onEncoded(out)
                    }
                    encoder.releaseOutputBuffer(index, false)
                }

                else -> return
            }
        }
    }

    private fun maxBaselineLevel(): Int {
        val levels = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder && Mime in it.supportedTypes }
                .flatMap { it.getCapabilitiesForType(Mime).profileLevels.asSequence() }
                .filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }
                .map { it.level }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
        return levels.maxOrNull() ?: MediaCodecInfo.CodecProfileLevel.AVCLevel31
    }

    private fun fail(error: Exception) {
        if (failed) return
        DiagLog.w(TAG, "cluster reencoder failed", error)
        close()
    }

    private fun startCode(nal: ByteArray): ByteArray = byteArrayOf(0, 0, 0, 1) + nal

    private companion object {
        const val TAG = "xcertplay-fly"
        const val Mime = MediaFormat.MIMETYPE_VIDEO_AVC

        /** Only used to configure the decoder; the real size comes back from the SPS. */
        const val NominalWidth = 1920
        const val NominalHeight = 1080

        const val BitRate = 2_000_000
        const val FrameRate = 5
        const val KeyframeSeconds = 1

        /** 20 ms per frame: faster than the encoder's 5 fps, so no frame is refused for being early. */
        const val InputStepUs = 20_000L
    }
}

/** Pulls SPS and PPS out of either an avcC record or an Annex-B unit. */
internal object AvcConfig {

    fun parameterSets(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
        if (ClusterFlyPush.isConfig(bytes)) {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(bytes)
            return if (sps.isNotEmpty() && pps.isNotEmpty()) sps to pps else null
        }
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var i = 0
        while (i < bytes.size) {
            val header = startCodeEnd(bytes, i)
            if (header == null) {
                i++
                continue
            }
            if (header >= bytes.size) break
            val type = bytes[header].toInt() and 0x1F
            if (type == 7 || type == 8) {
                val end = nextStartCode(bytes, header + 1)
                val nal = bytes.copyOfRange(header, end)
                if (type == 7) sps = nal else pps = nal
            }
            i = header + 1
        }
        val s = sps
        val p = pps
        return if (s != null && p != null) s to p else null
    }

    private fun startCodeEnd(bytes: ByteArray, at: Int): Int? = when {
        at + 4 <= bytes.size &&
            bytes[at].toInt() == 0 && bytes[at + 1].toInt() == 0 &&
            bytes[at + 2].toInt() == 0 && bytes[at + 3].toInt() == 1 -> at + 4
        at + 3 <= bytes.size &&
            bytes[at].toInt() == 0 && bytes[at + 1].toInt() == 0 && bytes[at + 2].toInt() == 1 -> at + 3
        else -> null
    }

    private fun nextStartCode(bytes: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 <= bytes.size) {
            if (startCodeEnd(bytes, i) != null) return i
            i++
        }
        return bytes.size
    }
}
