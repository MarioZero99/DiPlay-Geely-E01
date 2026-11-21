package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlyYuvTest {
    @Test fun aNeutralPixelIsWhite() {
        val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
        val yuv = byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte())
        val out = ByteArray(8)
        assertTrue(FlyYuv.toRgb565(yuv, yuv.size, meta, out))
        assertEquals(0xFF, out[0].toInt() and 0xFF)
        assertEquals(0xFF, out[1].toInt() and 0xFF)
    }

    @Test fun planarAndSemiplanarPlaceRedThenGray() {
        val y = ByteArray(8) { 81.toByte() }
        val planar = y + byteArrayOf(90.toByte(), 128.toByte(), 240.toByte(), 128.toByte())
        val semi = y + byteArrayOf(90.toByte(), 240.toByte(), 128.toByte(), 128.toByte())
        val planarMeta = frameMeta(4, 2, 4, 2, FlyYuv.PLANAR)!!
        val semiMeta = frameMeta(4, 2, 4, 2, FlyYuv.SEMIPLANAR)!!
        val planarOut = ByteArray(16)
        val semiOut = ByteArray(16)
        assertTrue(FlyYuv.toRgb565(planar, planar.size, planarMeta, planarOut))
        assertTrue(FlyYuv.toRgb565(semi, semi.size, semiMeta, semiOut))
        assertEquals(0x00, planarOut[0].toInt() and 0xFF)
        assertEquals(0xF8, planarOut[1].toInt() and 0xFF)
        assertEquals(0x69, planarOut[4].toInt() and 0xFF)
        assertEquals(0x4A, planarOut[5].toInt() and 0xFF)
        assertTrue(planarOut.contentEquals(semiOut))
    }

    @Test fun aShortBufferOrOddSizeIsRejected() {
        val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
        assertFalse(FlyYuv.toRgb565(ByteArray(3), 3, meta, ByteArray(8)))
        assertNull(frameMeta(3, 2, 3, 2, FlyYuv.PLANAR))
        assertNull(frameMeta(1920, 720, 1920, 720, 0))
        assertFalse(FlyYuv.supported(0))
    }

    @Test fun aRejectedOutputFormatIsNamed() {
        assertEquals("fly.display color 7", formatNotice(1920, 720, 1920, 720, 7))
        assertEquals(
            "fly.display format 1920x1080 stride=1920 slice=1088 color=19",
            formatNotice(1920, 1080, 1920, 1088, FlyYuv.PLANAR),
        )
    }
}
