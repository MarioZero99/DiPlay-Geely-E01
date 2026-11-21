package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class FlyFrameSlotTest {
    private val meta = frameMeta(2, 2, 2, 2, FlyYuv.PLANAR)!!
    private val slot = FlyFrameSlot()

    @Test fun thePainterHoldsTheOnlyCopy() {
        assertTrue(slot.offer(0, bytes(1, 2, 3), meta))
        assertFalse(slot.offer(1_000, bytes(9, 9, 9), meta))
        val taken = slot.take()!!
        assertEquals(1, taken.first[0].toInt())
        slot.release()
        assertTrue(slot.offer(166, bytes(4, 5, 6), meta))
        val next = slot.take()!!
        assertSame(taken.first, next.first)
        assertEquals(4, next.first[0].toInt())
    }

    @Test fun aFrameInsideTheIntervalIsLeftInTheCodec() {
        assertTrue(slot.offer(0, bytes(1), meta))
        slot.release()
        assertFalse(slot.offer(165, bytes(2), meta))
    }

    @Test fun anOversizedOrUnsupportedFrameIsRefused() {
        assertFalse(slot.offer(0, ByteBuffer.allocate(FlyYuv.MAX_BYTES + 1), meta))
        val odd = FlyFrameMeta(2, 2, 2, 2, 0)
        assertFalse(slot.offer(0, bytes(1, 2), odd))
    }

    private fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })
}
