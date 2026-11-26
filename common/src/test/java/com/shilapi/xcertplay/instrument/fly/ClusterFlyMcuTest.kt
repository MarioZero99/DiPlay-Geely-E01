package com.shilapi.xcertplay.instrument.fly

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterFlyMcuTest {

    @Test
    fun `front payload is 25 bytes with 67 then 1`() {
        val bytes = ClusterFlyMcu.payload(front = true)
        assertEquals(25, bytes.size)
        assertEquals(67.toByte(), bytes[0])
        assertEquals(1.toByte(), bytes[1])
        assertEquals(0, bytes.drop(2).count { it != 0.toByte() })
    }

    @Test
    fun `back payload is 25 bytes with 67 then 0`() {
        val bytes = ClusterFlyMcu.payload(front = false)
        assertEquals(67.toByte(), bytes[0])
        assertEquals(0.toByte(), bytes[1])
    }

    @Test
    fun `send writes control 0 opcode 1887 and the front payload on transaction 5`() {
        val binder = RecordingBinder(succeeds = true)
        val ok = ClusterFlyMcu.send(front = true, binder = binder)
        assertEquals(true, ok)
        assertEquals(5, binder.code)
        assertEquals(0.toByte(), binder.control)
        assertEquals(1887, binder.opcode)
        assertEquals(ClusterFlyMcu.payload(front = true).toList(), binder.payload.toList())
    }

    @Test
    fun `a missing service is a failed send`() {
        assertFalse(ClusterFlyMcu.send(front = true, binder = null))
    }

    @Test
    fun `the log line names the direction and the result`() {
        assertEquals("fly.mcu 1887 front true", ClusterFlyLog.mcu(front = true, ok = true))
        assertEquals("fly.mcu 1887 back false", ClusterFlyLog.mcu(front = false, ok = false))
    }

    @Test
    fun `open 1885 payload is 25 bytes with 74 then 1`() {
        val bytes = ClusterFlyMcu.payload1885(open = true)
        assertEquals(25, bytes.size)
        assertEquals(74.toByte(), bytes[0])
        assertEquals(1.toByte(), bytes[1])
        assertEquals(0, bytes.drop(2).count { it != 0.toByte() })
    }

    @Test
    fun `close 1885 payload is 25 bytes with 74 then 2`() {
        val bytes = ClusterFlyMcu.payload1885(open = false)
        assertEquals(25, bytes.size)
        assertEquals(74.toByte(), bytes[0])
        assertEquals(2.toByte(), bytes[1])
        assertEquals(0, bytes.drop(2).count { it != 0.toByte() })
    }

    @Test
    fun `send1885 writes opcode 1885 with control 0 on transaction 5`() {
        val binder = RecordingBinder(succeeds = true)
        val ok = ClusterFlyMcu.send1885(open = true, binder = binder)
        assertEquals(true, ok)
        assertEquals(5, binder.code)
        assertEquals(0.toByte(), binder.control)
        assertEquals(1885, binder.opcode)
        assertEquals(ClusterFlyMcu.payload1885(open = true).toList(), binder.payload.toList())
    }

    @Test
    fun `1885 log line names direction and binder result`() {
        assertEquals("fly.mcu 1885 left true", ClusterFlyLog.mcu1885(open = true, ok = true))
        assertEquals("fly.mcu 1885 right false", ClusterFlyLog.mcu1885(open = false, ok = false))
    }

    @Test
    fun `navigation mode on payload is 25 bytes with 1 then 1`() {
        val bytes = ClusterFlyMcu.payload1900(on = true)
        assertEquals(25, bytes.size)
        assertEquals(1.toByte(), bytes[0])
        assertEquals(1.toByte(), bytes[1])
        assertEquals(0, bytes.drop(2).count { it != 0.toByte() })
    }

    @Test
    fun `navigation mode off payload is 25 bytes with 1 then 0`() {
        val bytes = ClusterFlyMcu.payload1900(on = false)
        assertEquals(25, bytes.size)
        assertEquals(1.toByte(), bytes[0])
        assertEquals(0.toByte(), bytes[1])
        assertEquals(0, bytes.drop(2).count { it != 0.toByte() })
    }

    @Test
    fun `send1900 writes control 0 opcode 1900 and the mode payload on transaction 5`() {
        val binder = RecordingBinder(succeeds = true)
        val ok = ClusterFlyMcu.send1900(on = true, binder = binder)
        assertEquals(true, ok)
        assertEquals(5, binder.code)
        assertEquals(0.toByte(), binder.control)
        assertEquals(1900, binder.opcode)
        assertEquals(ClusterFlyMcu.payload1900(on = true).toList(), binder.payload.toList())
    }

    @Test
    fun `a missing service is a failed 1900 send`() {
        assertFalse(ClusterFlyMcu.send1900(on = true, binder = null))
    }

    @Test
    fun `the 1900 log line names the mode and the result`() {
        assertEquals("fly.mcu 1900 on true", ClusterFlyLog.mcu1900(on = true, ok = true))
        assertEquals("fly.mcu 1900 off false", ClusterFlyLog.mcu1900(on = false, ok = false))
    }

    @Test
    fun `the broadcast log line names the navigation state`() {
        assertEquals("fly.broadcast nav on sent", ClusterFlyLog.broadcast(on = true))
        assertEquals("fly.broadcast nav off sent", ClusterFlyLog.broadcast(on = false))
    }

    @Test
    fun `the gap log line is none before any mcu and the millis after one`() {
        assertEquals("fly.mcu gap none", ClusterFlyLog.mcuGap(null))
        assertEquals("fly.mcu gap 1000", ClusterFlyLog.mcuGap(1_000L))
    }

    @Test
    fun `1899 navigation status payload is 2 bytes with 6 then 1 or 0`() {
        assertEquals(listOf(6.toByte(), 1.toByte()), ClusterFlyMcu.payload1899(active = true).toList())
        assertEquals(listOf(6.toByte(), 0.toByte()), ClusterFlyMcu.payload1899(active = false).toList())
    }

    @Test
    fun `send1899 writes opcode 1899 with control 0 on transaction 5`() {
        val binder = RecordingBinder(succeeds = true)
        val ok = ClusterFlyMcu.send1899(active = true, binder = binder)
        assertEquals(true, ok)
        assertEquals(5, binder.code)
        assertEquals(0.toByte(), binder.control)
        assertEquals(1899, binder.opcode)
        assertEquals(listOf(6.toByte(), 1.toByte()), binder.payload.toList())
    }

    @Test
    fun `1899 log line names state and binder result`() {
        assertEquals("fly.mcu 1899 on true", ClusterFlyLog.mcu1899(active = true, ok = true))
        assertEquals("fly.mcu 1899 off false", ClusterFlyLog.mcu1899(active = false, ok = false))
    }

    @Test
    fun `send1899Guide sends IPKServer TBT guidance frames on opcode 1899`() {
        val binder = RecordingBinder(succeeds = true)
        assertEquals(true, ClusterFlyMcu.send1899Guide(active = true, binder = binder))
        assertEquals(1899, binder.opcode)
        assertEquals(listOf(15.toByte(), 0.toByte()), binder.payload.toList())
        assertEquals(7, ClusterFlyMcu.guideFrames1899(active = true).size)
        assertEquals(2, ClusterFlyMcu.guideFrames1899(active = false).size)
    }

    @Test
    fun `send1899Batch sends status and guidance reusing the same binder`() {
        val binder = RecordingBinder(succeeds = true)
        assertEquals(true, ClusterFlyMcu.send1899Batch(active = true, binder = binder))
        assertEquals(1899, binder.opcode)
        assertEquals(listOf(15.toByte(), 0.toByte()), binder.payload.toList())
    }

    private class RecordingBinder(private val succeeds: Boolean) : Binder() {
        var code: Int = -1
        var control: Byte = -1
        var opcode: Int = -1
        var payload: ByteArray = ByteArray(0)

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            this.code = code
            data.enforceInterface(ClusterFlyMcu.DESCRIPTOR)
            control = data.readByte()
            opcode = data.readInt()
            payload = data.createByteArray() ?: ByteArray(0)
            reply?.writeNoException()
            reply?.writeInt(if (succeeds) 1 else 0)
            return true
        }
    }
}
