package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The re-encoder needs the phone's SPS and PPS, and they arrive in one of two formats. */
class AvcConfigTest {

    @Test
    fun `an avcC record yields its SPS and PPS`() {
        val config = byteArrayOf(
            1, 66, 0, 0x1F, 0xFF.toByte(), 0xE1.toByte(),
            0, 3, 0x67, 66, 0x11,
            1,
            0, 2, 0x68, 0x22,
        )

        val (sps, pps) = AvcConfig.parameterSets(config)!!

        assertArrayEquals(byteArrayOf(0x67, 66, 0x11), sps)
        assertArrayEquals(byteArrayOf(0x68, 0x22), pps)
    }

    @Test
    fun `an Annex-B unit yields its SPS and PPS`() {
        val unit = byteArrayOf(
            0, 0, 0, 1, 0x67, 66, 0x11,
            0, 0, 0, 1, 0x68, 0x22,
            0, 0, 0, 1, 0x65, 0x00,
        )

        val (sps, pps) = AvcConfig.parameterSets(unit)!!

        assertArrayEquals(byteArrayOf(0x67, 66, 0x11), sps)
        assertArrayEquals(byteArrayOf(0x68, 0x22), pps)
    }

    @Test
    fun `a unit without both parameter sets is refused`() {
        assertNull(AvcConfig.parameterSets(byteArrayOf(0, 0, 0, 1, 0x67, 66, 0x11)))
    }
}
