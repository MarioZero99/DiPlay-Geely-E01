package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import java.nio.ByteBuffer

class MediaCodecSupportTest {
    @Test fun truncatedAccessUnitIsRejectedInsteadOfSubmittingItsValidPrefix() {
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0, 0, 0, 9, 0x41)).size)
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0)).size)
    }

    @Test fun findsRandomAccessAfterParameterSetsButNeverOnInterframes() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 10)
        assertTrue(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x65, 20), VideoCodec.H264))
        assertFalse(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x41, 20), VideoCodec.H264))
        for (type in 16..21) assertTrue(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1, 20), VideoCodec.H265))
        assertFalse(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, 2, 1, 20), VideoCodec.H265))
    }

    @Test
    fun lengthPrefixedNalUnitsBecomeOneAnnexBBuffer() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val lengthPrefixed =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            MediaCodecSupport.toAnnexB(lengthPrefixed),
        )
    }

    @Test
    fun hevcCodecSpecificDataBuildsAnnexBParameterSets() {
        val vps = byteArrayOf(0x40, 0x01)
        val sps = byteArrayOf(0x42, 0x01, 0x02)
        val pps = byteArrayOf(0x44, 0x01)
        val record = hevcRecord(
            vps,
            sps,
            pps,
        )

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + vps +
                byteArrayOf(0, 0, 0, 1) + sps +
                byteArrayOf(0, 0, 0, 1) + pps,
            MediaCodecSupport.hevcCodecSpecificData(record),
        )
    }

    @Test
    fun malformedHevcCodecSpecificDataIsRejected() {
        val truncated = hevcRecord(byteArrayOf(0x40, 0x01), byteArrayOf())
            .copyOfRange(0, 25)

        assertEquals(0, MediaCodecSupport.hevcCodecSpecificData(truncated).size)
    }

    private fun hevcRecord(vararg parameterSets: ByteArray): ByteArray {
        var size = 23
        parameterSets.forEach { size += 5 + it.size }
        val record = ByteArray(size)
        record[0] = 1
        record[21] = 3
        record[22] = parameterSets.size.toByte()
        var cursor = 23
        parameterSets.forEachIndexed { index, parameterSet ->
            record[cursor++] = (32 + index).toByte()
            record[cursor++] = 0
            record[cursor++] = 1
            record[cursor++] = (parameterSet.size ushr 8).toByte()
            record[cursor++] = parameterSet.size.toByte()
            parameterSet.copyInto(record, cursor)
            cursor += parameterSet.size
        }
        return record
    }

    @Test fun writeAnnexBDirectlyPopulatesByteBufferWithoutIntermediateArray() {
        val first = byteArrayOf(0x65, 0x01)
        val second = byteArrayOf(0x41, 0x02, 0x03)
        val avcc = byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second
        val buffer = ByteBuffer.allocateDirect(1024)
        val written = MediaCodecSupport.writeAnnexB(avcc, buffer)
        assertEquals(4 + first.size + 4 + second.size, written)
        buffer.flip()
        val expected = byteArrayOf(0, 0, 0, 1) + first + byteArrayOf(0, 0, 0, 1) + second
        val actual = ByteArray(written)
        buffer.get(actual)
        assertArrayEquals(expected, actual)
    }

    @Test fun isRandomAccessDetectsIdrDirectlyOnLengthPrefixedBytes() {
        val idrAvcc = byteArrayOf(0, 0, 0, 2, 0x65, 1)
        val nonIdrAvcc = byteArrayOf(0, 0, 0, 2, 0x41, 1)
        assertTrue(MediaCodecSupport.isRandomAccessLengthPrefixed(idrAvcc, VideoCodec.H264))
        assertFalse(MediaCodecSupport.isRandomAccessLengthPrefixed(nonIdrAvcc, VideoCodec.H264))

        for (type in 16..21) {
            val irapHevc = byteArrayOf(0, 0, 0, 3, (type shl 1).toByte(), 1, 20)
            assertTrue(MediaCodecSupport.isRandomAccessLengthPrefixed(irapHevc, VideoCodec.H265))
        }
        val nonIrapHevc = byteArrayOf(0, 0, 0, 3, 2, 1, 20)
        assertFalse(MediaCodecSupport.isRandomAccessLengthPrefixed(nonIrapHevc, VideoCodec.H265))
    }
}

