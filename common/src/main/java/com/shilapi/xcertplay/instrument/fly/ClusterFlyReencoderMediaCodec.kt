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
 * The decoder is the software one (`OMX.google.h264.decoder`). The centre screen already owns
 * `OMX.MTK.VIDEO.DECODER.AVC`; a second hardware decoder on this MT6735 stalls or blanks that
 * picture. Encoding stays on whatever encoder can emit Baseline, which is a different block.
 *
 * Everything here runs on the fly thread, at background priority. Every dequeue uses a zero
 * timeout. The cluster is sent five pictures a second. Reference frames are still decoded so the
 * five are real pictures; when one access unit takes longer than the budget, later P-frames are
 * skipped until the next IDR instead of queueing work against the centre screen. Any failure
 * closes both codecs and reports `false` from then on; the layer stays blank rather than risking
 * the main picture.
 *
 * The decoder's output is read through `getOutputImage` when the codec offers it and through
 * `getOutputBuffer` with the reported stride otherwise: the MT6735 on this head unit usually hands
 * back a plain YUV buffer, and a null Image must not mean "no picture at all".
 */
class ClusterFlyReencoderMediaCodec(
    private val config: ByteArray,
    private val onEncoded: (ByteArray) -> Unit,
) : ClusterFlyReencoder {

    private val decoder: MediaCodec? = createDecoder(config)
    private var encoder: MediaCodec? = null
    private var encoderFailed = false

    /** False when the software decoder is not on this device. The layer stays blank. */
    val ready: Boolean
        get() = decoder != null
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var encoderColor = 0

    private var frameWidth = 0
    private var frameHeight = 0
    private var frameStride = 0
    private var frameSliceHeight = 0
    private var frameColor = 0
    private var semiPlanarSource = true

    private var decodeTimeUs = 0L
    private var encodeTimeUs = 0L
    private var lastEncodedAtMs = 0L
    private var frameNowMs = 0L

    /**
     * Set until the next IDR. Starts set: the frames before the first keyframe have nothing to
     * refer to, and decoding them would only spend the core.
     */
    @Volatile
    private var awaitingIdr = true

    private var failed = false
    private val info = MediaCodec.BufferInfo()

    override fun dropUntilIdr() {
        awaitingIdr = true
    }

    override fun submit(nalu: ByteArray, nowMillis: Long): Boolean {
        if (failed) return false
        val decoder = decoder ?: return false
        frameNowMs = nowMillis

        // A skipped P-frame is a hole in the reference chain. Wait for an IDR, and flush first so
        // the decoder does not spend a core painting the torn frames in between.
        if (awaitingIdr && !ClusterFlyPush.isKeyframe(nalu)) {
            if (ClusterFlyPush.isParameterSet(nalu)) {
                return try {
                    feedDecoder(decoder, nalu)
                } catch (error: Exception) {
                    fail(error)
                    false
                }
            }
            return false
        }
        if (awaitingIdr) {
            try {
                recover(decoder)
            } catch (error: Exception) {
                fail(error)
                return false
            }
            awaitingIdr = false
        }

        val startedNs = System.nanoTime()
        return try {
            val accepted = feedDecoder(decoder, nalu)
            val spentMs = (System.nanoTime() - startedNs) / 1_000_000L
            if (spentMs > SLOW_DECODE_MILLIS) {
                recover(decoder)
                awaitingIdr = true
            }
            accepted
        } catch (error: Exception) {
            fail(error)
            false
        }
    }

    /** Puts the decoder back at the parameter sets. The next submitted unit must be an IDR. */
    private fun recover(decoder: MediaCodec) {
        decoder.flush()
        decoder.start()
        feedDecoder(decoder, config)
    }

    override fun close() {
        failed = true
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
    }

    private fun createDecoder(config: ByteArray): MediaCodec? {
        val (sps, pps) = AvcConfig.parameterSets(config) ?: return null
        val format = MediaFormat.createVideoFormat(Mime, NominalWidth, NominalHeight).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(startCode(sps)))
            setByteBuffer("csd-1", ByteBuffer.wrap(startCode(pps)))
        }
        // Never createDecoderByType: on this head unit that returns the hardware decoder the
        // centre screen is already using.
        val decoder = try {
            MediaCodec.createByCodecName(SOFTWARE_DECODER)
        } catch (error: Exception) {
            DiagLog.w(TAG, "cluster decoder unavailable", error)
            return null
        }
        return try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            decoder
        } catch (error: Exception) {
            runCatching { decoder.release() }
            DiagLog.w(TAG, "cluster decoder unavailable", error)
            null
        }
    }

    private fun feedDecoder(decoder: MediaCodec, nalu: ByteArray): Boolean {
        val input = decoder.dequeueInputBuffer(0)
        if (input < 0) return false
        val buffer = decoder.getInputBuffer(input)
        if (buffer == null) {
            decoder.queueInputBuffer(input, 0, 0, decodeTimeUs, 0)
            return false
        }
        buffer.clear()
        buffer.put(nalu)
        decoder.queueInputBuffer(input, 0, nalu.size, decodeTimeUs, 0)
        // The decoder's clock is its own; only the encoder's presentation times reach the wire.
        decodeTimeUs += InputStepUs
        drainDecoder(decoder)
        drainEncoder()
        return true
    }

    private fun drainDecoder(decoder: MediaCodec) {
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> rememberOutputFormat(decoder.outputFormat)

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

    private fun rememberOutputFormat(format: MediaFormat) {
        frameWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        frameHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        frameStride = if (format.containsKey(MediaFormat.KEY_STRIDE)) {
            format.getInteger(MediaFormat.KEY_STRIDE)
        } else {
            0
        }
        frameSliceHeight = if (format.containsKey("slice-height")) {
            format.getInteger("slice-height")
        } else {
            0
        }
        frameColor = if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
            format.getInteger(MediaFormat.KEY_COLOR_FORMAT)
        } else {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        }
    }

    private fun encodeFrame(decoder: MediaCodec, index: Int) {
        // Five pictures a second is the whole of what the cluster is given. Decoding still happens
        // for the frames we keep; encoding them all would spend a second core for no visible gain.
        if (encoderFailed) return
        if (lastEncodedAtMs != 0L && frameNowMs - lastEncodedAtMs < ENCODE_INTERVAL_MILLIS) return

        val image = try {
            decoder.getOutputImage(index)
        } catch (_: Exception) {
            null
        }
        try {
            val encoder = this.encoder ?: createEncoder(image)?.also { this.encoder = it } ?: run {
                if (encoderWidth > 0) encoderFailed = true
                return
            }
            val input = encoder.dequeueInputBuffer(0)
            if (input < 0) return
            val target = encoder.getInputBuffer(input)
            if (target == null) return

            val copied = if (image != null) {
                copyImage(image, target)
            } else {
                copyBuffer(decoder, index, target)
            }
            if (!copied) {
                encoder.queueInputBuffer(input, 0, 0, encodeTimeUs, 0)
                return
            }
            encoder.queueInputBuffer(input, 0, encoderWidth * encoderHeight * 3 / 2, encodeTimeUs, 0)
            encodeTimeUs += EncodedStepUs
            lastEncodedAtMs = frameNowMs
        } finally {
            runCatching { image?.close() }
        }
    }

    private fun createEncoder(image: Image?): MediaCodec? {
        val width = if (frameWidth > 0) frameWidth else image?.width ?: return null
        val height = if (frameHeight > 0) frameHeight else image?.height ?: return null
        encoderWidth = (width / 16) * 16
        encoderHeight = (height / 2) * 2
        if (encoderWidth <= 0 || encoderHeight <= 0) return null

        semiPlanarSource = when {
            image != null -> image.planes.size < 3 || image.planes[1].pixelStride == 2
            frameColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> false
            else -> true
        }
        encoderColor = if (semiPlanarSource) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        } else {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        }

        val format = MediaFormat.createVideoFormat(Mime, encoderWidth, encoderHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, encoderColor)
            setInteger(MediaFormat.KEY_BIT_RATE, BitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KeyframeSeconds)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, maxBaselineLevel())
        }
        return openEncoder(format)
    }

    /** Hardware encoder first when it can do Baseline; the software encoder if that configure fails. */
    private fun openEncoder(format: MediaFormat): MediaCodec? {
        val names = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder && Mime in it.supportedTypes }
                .map { it.name }
                .sortedBy { if (it.contains("google", ignoreCase = true)) 1 else 0 }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
        for (name in names) {
            val codec = try {
                MediaCodec.createByCodecName(name)
            } catch (_: Exception) {
                continue
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val request = android.os.Bundle()
                request.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                codec.setParameters(request)
                return codec
            } catch (_: Exception) {
                runCatching { codec.release() }
            }
        }
        DiagLog.w(TAG, "cluster encoder unavailable size=${encoderWidth}x$encoderHeight")
        return null
    }

    /**
     * Copies the decoded planes through the Image API, one row at a time, padding the width the
     * encoder aligned to 16 with zeroes.
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

    /**
     * The same copy for a codec that will not hand out an Image: the buffer is one YUV frame whose
     * rows are [frameStride] wide and whose planes start every [frameSliceHeight] rows.
     */
    private fun copyBuffer(decoder: MediaCodec, index: Int, target: ByteBuffer): Boolean {
        val buffer = try {
            decoder.getOutputBuffer(index)
        } catch (_: Exception) {
            null
        } ?: return false

        val stride = if (frameStride > 0) frameStride else frameWidth
        val sliceHeight = if (frameSliceHeight > 0) frameSliceHeight else frameHeight
        if (stride <= 0 || sliceHeight <= 0) return false

        target.clear()
        val lumaSize = encoderWidth * encoderHeight
        val chromaHeight = encoderHeight / 2
        if (!copyRows(buffer, stride, sliceHeight, 0, target, 0, encoderWidth, encoderHeight, frameWidth)) {
            return false
        }
        if (semiPlanarSource) {
            return copyRows(
                buffer, stride, sliceHeight / 2, stride * sliceHeight,
                target, lumaSize, encoderWidth, chromaHeight, frameWidth,
            )
        }
        val chromaWidth = encoderWidth / 2
        val chromaStride = stride / 2
        val chromaRows = sliceHeight / 2
        if (!copyRows(
            buffer, chromaStride, chromaRows, stride * sliceHeight,
            target, lumaSize, chromaWidth, chromaHeight, frameWidth / 2,
        )) {
            return false
        }
        return copyRows(
            buffer, chromaStride, chromaRows, stride * sliceHeight + chromaStride * chromaRows,
            target, lumaSize + chromaWidth * chromaHeight, chromaWidth, chromaHeight, frameWidth / 2,
        )
    }

    private fun copyRows(
        source: ByteBuffer,
        sourceStride: Int,
        sourceRows: Int,
        sourceOffset: Int,
        target: ByteBuffer,
        targetOffset: Int,
        targetStride: Int,
        rows: Int,
        width: Int,
    ): Boolean {
        for (row in 0 until rows) {
            target.position(targetOffset + row * targetStride)
            val from = sourceOffset + row * sourceStride
            if (row < sourceRows && from < source.capacity()) {
                val count = minOf(width, targetStride, source.capacity() - from)
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
                        val annex = if (ClusterFlyPush.isConfig(out)) {
                            ClusterFlyPush.annexB(out)
                        } else {
                            ClusterFlyPush.withStartCode(out)
                        }
                        if (annex != null) onEncoded(annex)
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
        const val SOFTWARE_DECODER = "OMX.google.h264.decoder"

        /** This session's main screen. The SPS overrides it once the decoder reports a size. */
        const val NominalWidth = 1920
        const val NominalHeight = 720

        const val BitRate = 2_000_000
        const val FrameRate = 5
        const val KeyframeSeconds = 1

        /** One encoded picture per 200 ms. Decoding may run faster; the wire does not. */
        const val ENCODE_INTERVAL_MILLIS = 200L

        /**
         * Longer than this and the fly thread is already missing five pictures a second. Stop
         * until the next IDR rather than decoding a backlog on the cores the centre screen needs.
         */
        const val SLOW_DECODE_MILLIS = 150L

        /** 20 ms of decoder input; the encoder's own clock is 5 fps. */
        const val InputStepUs = 20_000L
        const val EncodedStepUs = 200_000L
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
