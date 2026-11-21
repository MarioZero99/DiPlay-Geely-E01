package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class FlyPainterTest {
    private val slot = FlyFrameSlot()
    private val logs = mutableListOf<String>()
    private var now = 0L
    private var measured = 10L

    private fun painter(draw: (ByteArray, Int, Int) -> Boolean = { _, _, _ -> true }) = FlyPainter(
        slot = slot,
        rgb = ByteArray(8),
        draw = draw,
        log = logs::add,
        nowMs = { now },
        measure = { block -> block(); measured },
    )

    @Test fun aPostedFrameIsLoggedOnceAndTheSlotIsFree() {
        val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
        val yuv = byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte())
        assertTrue(slot.offer(0, ByteBuffer.wrap(yuv), meta))
        assertTrue(painter().paintOne())
        assertEquals(listOf("fly.display frame"), logs)
        assertTrue(slot.offer(166, ByteBuffer.wrap(yuv), meta))
    }

    @Test fun aRejectedCanvasDoesNotCountAsAFrameAndStillReleases() {
        val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
        val yuv = byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte())
        assertTrue(slot.offer(0, ByteBuffer.wrap(yuv), meta))
        assertFalse(painter { _, _, _ -> false }.paintOne())
        assertFalse(logs.contains("fly.display frame"))
        assertTrue(slot.offer(166, ByteBuffer.wrap(yuv), meta))
    }

    @Test fun aSlowPaintIsLoggedAtMostEveryFiveSeconds() {
        measured = 200
        val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
        val yuv = byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte())
        val painter = painter()
        assertTrue(slot.offer(0, ByteBuffer.wrap(yuv), meta))
        painter.paintOne()
        slot.release()
        now = 1_000
        assertTrue(slot.offer(166, ByteBuffer.wrap(yuv), meta))
        painter.paintOne()
        assertEquals(1, logs.count { it.startsWith("fly.display paint slow ") })
        slot.release()
        now = 5_000
        assertTrue(slot.offer(332, ByteBuffer.wrap(yuv), meta))
        painter.paintOne()
        assertEquals(2, logs.count { it.startsWith("fly.display paint slow ") })
    }

    @Test fun decoderFailureNamesTheMethodWithoutADeviceName() {
        val error = IllegalStateException("codec is not in the Executing state")
        error.stackTrace = arrayOf(StackTraceElement("FlyClusterDecoder", "drain", "FlyClusterDecoder.kt", 140))
        val line = flyDecoderFailure(error)
        assertEquals(
            "fly.display decoder failed IllegalStateException codec is not in the Executing state at FlyClusterDecoder.kt:140",
            line,
        )
        assertFalse(line.contains("name="))
        assertEquals(line, com.shilapi.xcertplay.DiagnosticRedactor.redact(line))
    }
}
