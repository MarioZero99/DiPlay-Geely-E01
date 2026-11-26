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

    /** Navigation mode, from `AutoNaviRpc.setMMI_NavigationMod_indicator`. */
    const val OPCODE_1900 = 1900
    /** IPKServer navigation status (`CAN_DataPackage`), `[6, 1]` active / `[6, 0]` inactive. */
    const val OPCODE_1899 = 1899
    const val PAYLOAD_LENGTH = 25
    private const val MARKER: Byte = 67
    private const val MARKER_1885: Byte = 74
    private const val MARKER_1900: Byte = 1

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

    /** 1 in the first byte; 1 turns navigation mode on, 0 turns it off. */
    fun payload1900(on: Boolean): ByteArray {
        val bytes = ByteArray(PAYLOAD_LENGTH)
        bytes[0] = MARKER_1900
        bytes[1] = if (on) 1 else 0
        return bytes
    }

    /** 6 in the first byte (IPKServer NavigationStatus); 1 active, 0 inactive. */
    fun payload1899(active: Boolean): ByteArray = byteArrayOf(6, if (active) 1 else 0)

    /**
     * TBT guidance frames sent by IPKServer on opcode 1899 right after NavigationStatus, so the
     * cluster MCU sees the exact same active-navigation state as Amap.
     */
    fun guideFrames1899(active: Boolean): List<ByteArray> = if (active) {
        listOf(
            byteArrayOf(7, 3),
            byteArrayOf(8, 0),
            byteArrayOf(9, 0, 0),
            byteArrayOf(10, 1),
            byteArrayOf(11, 0, 0),
            byteArrayOf(12, 0, 1),
            byteArrayOf(15, 0),
        )
    } else {
        listOf(
            byteArrayOf(7, 0),
            byteArrayOf(15, 0),
        )
    }

    fun send(front: Boolean, binder: IBinder? = lookup()): Boolean =
        send(OPCODE, payload(front), binder)

    fun send1885(open: Boolean, binder: IBinder? = lookup()): Boolean =
        send(OPCODE_1885, payload1885(open), binder)

    fun send1900(on: Boolean, binder: IBinder? = lookup()): Boolean =
        send(OPCODE_1900, payload1900(on), binder)

    fun send1899(active: Boolean, binder: IBinder? = lookup()): Boolean =
        send(OPCODE_1899, payload1899(active), binder)

    fun send1899Guide(active: Boolean, binder: IBinder? = lookup()): Boolean =
        guideFrames1899(active).all { frame -> send(OPCODE_1899, frame, binder) }

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
