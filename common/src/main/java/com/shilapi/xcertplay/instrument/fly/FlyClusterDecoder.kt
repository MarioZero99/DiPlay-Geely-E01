package com.shilapi.xcertplay.instrument.fly

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * The software H.264 decoder that paints the cluster from its own output buffers.
 *
 * Only the fly thread submits to this. The centre screen already holds the hardware decoder, so this
 * one is `OMX.google.h264.decoder`: it may fall behind, but it never makes the driver's picture
 * stutter. On Android 5.1 the software decoder cannot own the HDMI surface, so it hands back YUV
 * buffers and a second, lower-priority thread copies the newest one onto the cluster every 166 ms.
 */
internal class FlyClusterDecoder(
    private val surface: Surface,
    private val sps: ByteArray,
    private val pps: ByteArray,
    private val log: (String) -> Unit,
) {
    private val slot = FlyFrameSlot()
    private val rgb = ByteArray(1920 * 720 * 2)
    private val rgbBuffer = ByteBuffer.wrap(rgb)
    private var bitmap: Bitmap? = null
    @Volatile private var painting = false
    private var paintThread: Thread? = null
    private var meta: FlyFrameMeta? = null
    private var colorLogged = false
    private var bufferLogged = false
    @Suppress("DEPRECATION")
    private var inputBuffers: Array<ByteBuffer> = emptyArray()
    @Suppress("DEPRECATION")
    private var outputBuffers: Array<ByteBuffer> = emptyArray()

    private val codec: MediaCodec? = create(surface, sps, pps)
    val ready: Boolean = codec != null
    private var awaitingIdr = true
    private var ptsUs = 0L
    private var inputWarned = false

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
            // A restart begins at the parameter sets, like the old re-encoder did: a CarPlay IDR
            // often arrives without SPS/PPS of its own.
            queue(codec, START_CODE + sps)
            queue(codec, START_CODE + pps)
            awaitingIdr = true
            log("fly.display slow $spentMs")
        }
    }

    fun release() {
        painting = false
        val codec = codec ?: return
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
        }
        codec.release()
    }

    private fun startPainter(surface: Surface) {
        val painter = FlyPainter(
            slot = slot,
            rgb = rgb,
            draw = { _, width, height ->
                val current = bitmap ?: Bitmap.createBitmap(1920, 720, Bitmap.Config.RGB_565).also { bitmap = it }
                drawClusterBitmap(surface, current, rgbBuffer, width, height)
            },
            log = log,
            nowMs = { System.nanoTime() / 1_000_000L },
            measure = { block ->
                val started = System.nanoTime()
                block()
                (System.nanoTime() - started) / 1_000_000L
            },
        )
        painting = true
        paintThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
            while (painting) {
                try {
                    if (!painter.paintOne()) Thread.sleep(16)
                } catch (error: RuntimeException) {
                    log("fly.display paint failed ${error.javaClass.simpleName}")
                    painting = false
                }
            }
        }, "cluster-fly-paint").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun queue(codec: MediaCodec, nalu: ByteArray) {
        var index = codec.dequeueInputBuffer(0)
        if (index < 0) {
            drain(codec)
            index = codec.dequeueInputBuffer(0)
        }
        if (index < 0 || index >= inputBuffers.size) {
            awaitingIdr = true
            return
        }
        val buffer = inputBuffers[index]
        buffer.clear()
        if (buffer.remaining() < nalu.size) {
            // The codec sized its input buffer; an IDR that does not fit is dropped, not truncated.
            // The slot itself is handed back empty — keeping it would starve every later unit.
            codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
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
                    try {
                        if (!bufferLogged) {
                            bufferLogged = true
                            log("fly.display buffer")
                        }
                        val current = meta
                        if (current != null && info.size > 0 && index < outputBuffers.size) {
                            val buffer = outputBuffers[index]
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            slot.offer(System.nanoTime() / 1_000_000L, buffer, current)
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readFormat(codec.outputFormat)
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    @Suppress("DEPRECATION")
                    outputBuffers = codec.outputBuffers
                }
                else -> return
            }
        }
    }

    private fun readFormat(format: MediaFormat) {
        val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
        val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
        val stride = if (format.containsKey(MediaFormat.KEY_STRIDE)) format.getInteger(MediaFormat.KEY_STRIDE) else 0
        val slice = if (format.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) format.getInteger(MediaFormat.KEY_SLICE_HEIGHT) else 0
        val color = if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) format.getInteger(MediaFormat.KEY_COLOR_FORMAT) else 0
        val parsed = frameMeta(width, height, stride, slice, color)
        if (parsed == null && !FlyYuv.supported(color) && !colorLogged) {
            colorLogged = true
            log("fly.display color $color")
        }
        meta = parsed
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
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            // Android decoders read the parameter sets as Annex-B NAL units. The sink's working
            // main decoder hands them over the same way; the avcC record itself has no start codes.
            format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
            codec.configure(format, null, null, 0)
            codec.start()
            @Suppress("DEPRECATION")
            inputBuffers = codec.inputBuffers
            @Suppress("DEPRECATION")
            outputBuffers = codec.outputBuffers
            startPainter(surface)
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

        /** Software decode takes one access unit at a time; yesterday's keyframes were under 50 KB. */
        const val MAX_INPUT_BYTES = 1 * 1024 * 1024
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}

internal fun drawClusterBitmap(
    surface: Surface,
    bitmap: Bitmap,
    rgb: ByteBuffer,
    width: Int,
    height: Int,
): Boolean {
    if (!surface.isValid || bitmap.width != width || bitmap.height != height) return false
    rgb.rewind()
    rgb.limit(width * height * 2)
    bitmap.copyPixelsFromBuffer(rgb)
    val canvas = surface.lockCanvas(null) ?: return false
    return try {
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        true
    } finally {
        surface.unlockCanvasAndPost(canvas)
    }
}
