package com.shilapi.xcertplay.transport

import java.util.concurrent.atomic.AtomicReference

enum class DisconnectOrigin {
    UNKNOWN,
    USB_PHYSICAL_DETACH,
    NCM_WRITE_EXHAUSTED,
    NCM_READ_FAULT,
    NCM_QUEUE_OVERFLOW,
    NCM_NTB_CORRUPT,
    TUN_CLOSED,
    TUN_IO_ERROR,
    TCP_TIMEOUT,
    TCP_PEER_RESET,
    IAP2_CONTROL_DROP,
}

data class FaultReport(
    val origin: DisconnectOrigin,
    val message: String,
    val timestampMillis: Long,
)

class FirstFaultRecorder {
    private val fault = AtomicReference<FaultReport?>(null)

    val origin: DisconnectOrigin
        get() = fault.get()?.origin ?: DisconnectOrigin.UNKNOWN

    val message: String?
        get() = fault.get()?.message

    val timestampMillis: Long
        get() = fault.get()?.timestampMillis ?: 0L

    fun record(origin: DisconnectOrigin, message: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val report = FaultReport(origin, message, nowMillis)
        return fault.compareAndSet(null, report)
    }

    fun reset() {
        fault.set(null)
    }
}

class WiredLivenessStats {
    @Volatile private var lastInboundTimeMs: Long = 0L
    @Volatile private var lastOutboundTimeMs: Long = 0L
    @Volatile private var inboundPackets: Long = 0L
    @Volatile private var inboundBytes: Long = 0L
    @Volatile private var outboundPackets: Long = 0L
    @Volatile private var outboundBytes: Long = 0L

    fun noteInbound(bytes: Int, nowMillis: Long) {
        lastInboundTimeMs = nowMillis
        inboundPackets += 1
        inboundBytes += bytes
    }

    fun noteOutbound(bytes: Int, nowMillis: Long) {
        lastOutboundTimeMs = nowMillis
        outboundPackets += 1
        outboundBytes += bytes
    }

    fun snapshot(nowMillis: Long): Snapshot {
        val lastIn = lastInboundTimeMs
        val lastOut = lastOutboundTimeMs
        return Snapshot(
            inboundPackets = inboundPackets,
            inboundBytes = inboundBytes,
            outboundPackets = outboundPackets,
            outboundBytes = outboundBytes,
            sinceLastInboundMs = if (lastIn == 0L) -1L else (nowMillis - lastIn).coerceAtLeast(0L),
            sinceLastOutboundMs = if (lastOut == 0L) -1L else (nowMillis - lastOut).coerceAtLeast(0L),
        )
    }

    data class Snapshot(
        val inboundPackets: Long,
        val inboundBytes: Long,
        val outboundPackets: Long,
        val outboundBytes: Long,
        val sinceLastInboundMs: Long,
        val sinceLastOutboundMs: Long,
    ) {
        fun summary(): String =
            "rx=${inboundPackets}pkts/${inboundBytes}B(gap ${if (sinceLastInboundMs < 0) "none" else "${sinceLastInboundMs}ms"}) " +
                "tx=${outboundPackets}pkts/${outboundBytes}B(gap ${if (sinceLastOutboundMs < 0) "none" else "${sinceLastOutboundMs}ms"})"
    }
}
