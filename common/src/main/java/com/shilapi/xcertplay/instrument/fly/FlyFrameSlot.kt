package com.shilapi.xcertplay.instrument.fly

import java.nio.ByteBuffer

internal class FlyFrameSlot(
    private val intervalMs: Long = 166L,
) {
    private val lock = Any()
    private var pending: ByteArray? = null
    private var pendingLength = 0
    private var pendingMeta: FlyFrameMeta? = null
    private var held = false
    private var lastOfferMs = Long.MIN_VALUE

    fun offer(nowMs: Long, src: ByteBuffer, meta: FlyFrameMeta): Boolean {
        val length = src.remaining()
        if (length !in 1..FlyYuv.MAX_BYTES || !FlyYuv.supported(meta.color)) return false
        synchronized(lock) {
            if (held) return false
            if (lastOfferMs != Long.MIN_VALUE && nowMs - lastOfferMs < intervalMs) return false
            val dest = pending?.takeIf { it.size >= length } ?: ByteArray(length).also { pending = it }
            src.duplicate().get(dest, 0, length)
            pendingLength = length
            pendingMeta = meta
            lastOfferMs = nowMs
            held = true
        }
        return true
    }

    fun take(): Triple<ByteArray, Int, FlyFrameMeta>? {
        synchronized(lock) {
            if (!held) return null
            val buf = pending ?: return null
            val meta = pendingMeta ?: return null
            return Triple(buf, pendingLength, meta)
        }
    }

    fun release() {
        synchronized(lock) { held = false }
    }
}
