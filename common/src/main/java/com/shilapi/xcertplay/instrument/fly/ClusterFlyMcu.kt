package com.shilapi.xcertplay.instrument.fly

import android.os.IBinder
import android.os.Parcel

/**
 * The factory's "navigation is in front" message.
 *
 * Extraservice sends opcode 1887 through RpcManager.sendMessage. That method always uses control
 * byte 0; 67 lives in the first byte of the 25-byte body. The class itself is not on this app's
 * classpath, so this talks to the RpcService binder with the same parcel the proxy writes.
 */
object ClusterFlyMcu {
    const val SERVICE = "RpcService"
    const val DESCRIPTOR = "com.neusoft.alfus.os.IRpcService"
    const val TRANSACTION_SEND = 5
    const val CONTROL: Byte = 0
    const val OPCODE = 1887
    const val OPCODE_1885 = 1885
    const val PAYLOAD_LENGTH = 25
    private const val MARKER: Byte = 67
    private const val MARKER_1885: Byte = 74

    fun payload(front: Boolean): ByteArray {
        val bytes = ByteArray(PAYLOAD_LENGTH)
        bytes[0] = MARKER
        bytes[1] = if (front) 1 else 0
        return bytes
    }

    /** 74 in the first byte; 1 opens the map layer, 2 covers it. */
    fun payload1885(open: Boolean): ByteArray {
        val bytes = ByteArray(PAYLOAD_LENGTH)
        bytes[0] = MARKER_1885
        bytes[1] = if (open) 1 else 2
        return bytes
    }

    fun send(front: Boolean, binder: IBinder? = lookup()): Boolean =
        send(OPCODE, payload(front), binder)

    fun send1885(open: Boolean, binder: IBinder? = lookup()): Boolean =
        send(OPCODE_1885, payload1885(open), binder)

    private fun send(opcode: Int, payload: ByteArray, binder: IBinder?): Boolean {
        if (binder == null) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeByte(CONTROL)
            data.writeInt(opcode)
            data.writeByteArray(payload)
            if (!binder.transact(TRANSACTION_SEND, data, reply, 0)) return false
            reply.readException()
            reply.readInt() != 0
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Same lookup E01PrivilegedBridge uses, aimed at RpcService. */
    fun lookup(): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        getService.invoke(null, SERVICE) as? IBinder
    } catch (_: Throwable) {
        null
    }
}
