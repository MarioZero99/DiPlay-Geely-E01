package com.shilapi.xcertplay.instrument.fly

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface

/**
 * The software H.264 decoder that draws straight onto the cluster's surface.
 *
 * Only the fly thread touches this. The centre screen already holds the hardware decoder, so this
 * one is `OMX.google.h264.decoder`: it may fall behind, but it never makes the driver's picture
 * stutter. Output is released to the surface and never read back.
 */
internal class FlyClusterDecoder(
    surface: Surface,
    sps: ByteArray,
    pps: ByteArray,
    private val log: (String) -> Unit,
) {
    private val codec: MediaCodec? = create(surface, sps, pps)
    val ready: Boolean = codec != null
    private var awaitingIdr = true
    private var ptsUs = 0L
    private var inputWarned = false
    private var firstFrameLogged = false

    fun dropUntilIdr() {
        awaitingIdr = true
    }

    fun submit(nalu: ByteArray) {
        val codec = codec ?: return
        val idr = FlyAnnex.isIdr(nalu)
        if (awaitingIdr && !idr && !FlyAnnex.isParameterSet(nalu)) return
        if (idr) awaitingIdr = false
        val startedNs = System.nanoTime()
        queue(codec, nalu)
        drain(codec)
        val spentMs = (System.nanoTime() - startedNs) / 1_000_000L
        if (spentMs > SLOW_MS) {
            codec.flush()
            codec.start()
            awaitingIdr = true
            log("fly.display slow $spentMs")
        }
    }

    fun release() {
        val codec = codec ?: return
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
        }
        codec.release()
    }

    private fun queue(codec: MediaCodec, nalu: ByteArray) {
        var index = codec.dequeueInputBuffer(0)
        if (index < 0) {
            drain(codec)
            index = codec.dequeueInputBuffer(0)
        }
        if (index < 0) {
            awaitingIdr = true
            return
        }
        val buffer = codec.getInputBuffer(index) ?: run {
            awaitingIdr = true
            return
        }
        buffer.clear()
        if (buffer.remaining() < nalu.size) {
            // The codec sized its input buffer; an IDR that does not fit is dropped, not truncated.
            awaitingIdr = true
            if (!inputWarned) {
                inputWarned = true
                log("fly.display input short ${nalu.size}")
            }
            return
        }
        buffer.put(nalu)
        codec.queueInputBuffer(index, 0, nalu.size, ptsUs, 0)
        ptsUs += FRAME_US
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    codec.releaseOutputBuffer(index, true)
                    if (!firstFrameLogged) {
                        firstFrameLogged = true
                        log("fly.display frame")
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> return
            }
        }
    }

    private fun create(surface: Surface, sps: ByteArray, pps: ByteArray): MediaCodec? {
        val name = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { info ->
                !info.isEncoder && info.name == SOFTWARE &&
                    info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
            }?.name
        if (name == null) {
            log("fly.display decoder failed")
            return null
        }
        val codec = try {
            MediaCodec.createByCodecName(name)
        } catch (error: RuntimeException) {
            log("fly.display decoder failed ${error.javaClass.simpleName}")
            return null
        }
        return try {
            val format = MediaFormat.createVideoFormat(MIME, 1920, 720)
            // Android decoders read the parameter sets as Annex-B NAL units. The sink's working
            // main decoder hands them over the same way; the avcC record itself has no start codes.
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(START_CODE + sps))
            format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(START_CODE + pps))
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: RuntimeException) {
            // A half-built codec is still an OMX instance; on this head unit, leaking one per
            // attempt is not affordable.
            runCatching { codec.release() }
            log("fly.display decoder failed ${error.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val MIME = "video/avc"
        const val SOFTWARE = "OMX.google.h264.decoder"
        const val FRAME_US = 50_000L
        const val SLOW_MS = 150L
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
