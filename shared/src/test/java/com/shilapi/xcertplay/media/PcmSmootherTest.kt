package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSmootherTest {

    @Test
    fun `fade byte count is integer multiple of frame bytes for 48k and 44k`() {
        // 48 kHz stereo (frame = 4 bytes)
        val count48Stereo = PcmSmoother.fadeByteCount(48000, 2)
        assertEquals(0, count48Stereo % 4)
        assertEquals(48000 * 15 / 1000 * 4, count48Stereo) // 720 frames * 4 = 2880 bytes

        // 44.1 kHz stereo (frame = 4 bytes)
        val count44Stereo = PcmSmoother.fadeByteCount(44100, 2)
        assertEquals(0, count44Stereo % 4)
        assertEquals(44100 * 15 / 1000 * 4, count44Stereo) // 661 frames * 4 = 2644 bytes

        // 48 kHz mono (frame = 2 bytes)
        val count48Mono = PcmSmoother.fadeByteCount(48000, 1)
        assertEquals(0, count48Mono % 2)
        assertEquals(48000 * 15 / 1000 * 2, count48Mono) // 720 frames * 2 = 1440 bytes
    }

    @Test
    fun `fadeIn scales from near zero to unity across channels with same factor`() {
        val sampleRate = 48000
        val channels = 2
        val frames = 100
        val bytes = frames * 4
        val pcm = ByteArray(bytes)

        // Fill with full-scale positive amplitude: left = 0x4000 (16384), right = 0x6000 (24576)
        for (i in 0 until frames) {
            val offset = i * 4
            // Left: 16384 (0x4000)
            pcm[offset] = 0x00
            pcm[offset + 1] = 0x40
            // Right: 24576 (0x6000)
            pcm[offset + 2] = 0x00
            pcm[offset + 3] = 0x60
        }

        // Apply fade-in over 100 frames
        PcmSmoother.applyFadeIn(pcm, 0, bytes, sampleRate = 48000, channels = 2)

        // Read first frame
        val firstLeft = readS16(pcm, 0)
        val firstRight = readS16(pcm, 2)
        // First sample should be substantially attenuated (near 0)
        assertTrue("First left sample should be close to 0, was $firstLeft", firstLeft in 0..500)
        assertTrue("First right sample should be close to 0, was $firstRight", firstRight in 0..750)

        // Left and Right must scale by the exact same factor in frame 10
        val left10 = readS16(pcm, 10 * 4)
        val right10 = readS16(pcm, 10 * 4 + 2)
        val leftRatio = left10.toDouble() / 16384.0
        val rightRatio = right10.toDouble() / 24576.0
        assertEquals(leftRatio, rightRatio, 0.001)

        // Last sample (frame 99) should be near unity (full scale)
        val lastLeft = readS16(pcm, (frames - 1) * 4)
        val lastRight = readS16(pcm, (frames - 1) * 4 + 2)
        assertTrue("Last left sample should be close to 16384, was $lastLeft", lastLeft >= 16000)
        assertTrue("Last right sample should be close to 24576, was $lastRight", lastRight >= 24000)
    }

    @Test
    fun `fadeOut scales from unity to zero at the last frame`() {
        val sampleRate = 48000
        val channels = 2
        val frames = 100
        val bytes = frames * 4
        val pcm = ByteArray(bytes)

        // Fill with full-scale: left = 20000, right = 10000
        for (i in 0 until frames) {
            val offset = i * 4
            writeS16(pcm, offset, 20000)
            writeS16(pcm, offset + 2, 10000)
        }

        PcmSmoother.applyFadeOut(pcm, 0, bytes, sampleRate, channels)

        // First frame should be near unity
        val firstLeft = readS16(pcm, 0)
        val firstRight = readS16(pcm, 2)
        assertTrue("First left sample should be close to 20000, was $firstLeft", firstLeft >= 19500)
        assertTrue("First right sample should be close to 10000, was $firstRight", firstRight >= 9750)

        // Very last frame MUST be zero
        val lastLeft = readS16(pcm, (frames - 1) * 4)
        val lastRight = readS16(pcm, (frames - 1) * 4 + 2)
        assertEquals(0, lastLeft)
        assertEquals(0, lastRight)
    }

    @Test
    fun `fade handles signed negative extreme values without overflow`() {
        val sampleRate = 48000
        val channels = 2
        val frames = 50
        val bytes = frames * 4
        val pcm = ByteArray(bytes)

        // Test with negative extreme: -32768 (0x8000) and -10000
        for (i in 0 until frames) {
            val offset = i * 4
            writeS16(pcm, offset, -32768)
            writeS16(pcm, offset + 2, -10000)
        }

        PcmSmoother.applyFadeOut(pcm, 0, bytes, sampleRate, channels)

        // Check middle frame: must remain negative and scaled
        val midLeft = readS16(pcm, 25 * 4)
        val midRight = readS16(pcm, 25 * 4 + 2)
        assertTrue("Negative sample must stay negative, was $midLeft", midLeft < 0)
        assertTrue("Negative sample must stay negative, was $midRight", midRight < 0)
        assertTrue("Middle sample should be attenuated from -32768", midLeft > -32768 && midLeft < -10000)

        // Last frame must reach exactly 0 without sign inversion or overflow
        val lastLeft = readS16(pcm, (frames - 1) * 4)
        val lastRight = readS16(pcm, (frames - 1) * 4 + 2)
        assertEquals(0, lastLeft)
        assertEquals(0, lastRight)
    }

    @Test
    fun `validLength boundary does not touch bytes beyond validLength`() {
        val buffer = ByteArray(100) { 0x55.toByte() }
        val validLength = 40 // only 10 stereo frames

        PcmSmoother.applyFadeOut(buffer, 0, validLength, 48000, 2)

        // Bytes at and beyond validLength must remain unchanged (0x55)
        for (i in validLength until buffer.size) {
            assertEquals("Byte at $i should not be touched", 0x55.toByte(), buffer[i])
        }
    }

    @Test
    fun `createSilence produces frame-aligned zero buffer`() {
        val silence48 = PcmSmoother.createSilence(48000, 2, durationMs = 40)
        assertEquals(48000 * 40 / 1000 * 4, silence48.size) // 1920 frames * 4 = 7680 bytes
        assertTrue("All bytes must be zero", silence48.all { it == 0.toByte() })

        val silence44 = PcmSmoother.createSilence(44100, 2, durationMs = 40)
        assertEquals(44100 * 40 / 1000 * 4, silence44.size) // 1764 frames * 4 = 7056 bytes
        assertTrue("All bytes must be zero", silence44.all { it == 0.toByte() })
    }

    private fun readS16(pcm: ByteArray, offset: Int): Int {
        val low = pcm[offset].toInt() and 0xFF
        val high = pcm[offset + 1].toInt() // preserves sign
        return (high shl 8) or low
    }

    private fun writeS16(pcm: ByteArray, offset: Int, value: Int) {
        pcm[offset] = (value and 0xFF).toByte()
        pcm[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
