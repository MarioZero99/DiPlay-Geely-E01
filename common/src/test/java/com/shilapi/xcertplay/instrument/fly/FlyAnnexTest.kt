package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlyAnnexTest {

    @Test fun parameterSetsComeOutWithoutStartCodes() {
        val sps = byteArrayOf(0x67, 100.toByte(), 0.toByte(), 31.toByte())
        val pps = byteArrayOf(0x68, (-84).toByte())
        val config = avcC(100, sps, pps)
        val sets = FlyAnnex.parameterSets(config)
        assertNotNull(sets)
        assertArrayEquals(sps, sets!!.sps)
        assertArrayEquals(pps, sets.pps)
    }

    @Test fun anIdrIsRecognizedAndAPredictedFrameIsNot() {
        assertTrue(FlyAnnex.isIdr(byteArrayOf(0, 0, 0, 1, 0x65, 0)))
        assertFalse(FlyAnnex.isIdr(byteArrayOf(0, 0, 0, 1, 0x41, 0)))
        assertTrue(FlyAnnex.isParameterSet(byteArrayOf(0, 0, 1, 0x67, 0)))
    }

    private fun avcC(profile: Int, sps: ByteArray, pps: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(1)
        out.write(profile)
        out.write(0)
        out.write(31)
        out.write(0xFF)
        out.write(0xE1)
        out.write(sps.size shr 8)
        out.write(sps.size)
        out.write(sps)
        out.write(1)
        out.write(pps.size shr 8)
        out.write(pps.size)
        out.write(pps)
        return out.toByteArray()
    }
}
