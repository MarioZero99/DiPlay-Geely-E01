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
