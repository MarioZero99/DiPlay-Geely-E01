package com.shilapi.xcertplay.orchestration

import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounds the frequency and total attempts of full wireless session restarts, preventing
 * restart storms and resource exhaustion when network interfaces flap (L3).
 */
internal class WirelessRestartLimiter(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val baseBackoffMillis: Long = DEFAULT_BASE_BACKOFF_MILLIS,
    val maxBackoffMillis: Long = DEFAULT_MAX_BACKOFF_MILLIS,
) {
    private val attempts = AtomicInteger(0)

    /**
     * Records an attempt and calculates the exponential backoff delay before restarting.
     * Returns null if the maximum restart budget is exhausted (tripping the circuit breaker).
     */
    fun nextBackoffOrNull(): Long? {
        val current = attempts.incrementAndGet()
        if (current > maxAttempts) return null
        val shift = (current - 1).coerceAtMost(30)
        val exp = 1L shl shift
        return (baseBackoffMillis * exp).coerceAtMost(maxBackoffMillis)
    }

    /**
     * Resets the attempt counter to zero, e.g. after a successful wireless session handoff.
     */
    fun reset() {
        attempts.set(0)
    }

    val currentAttempts: Int
        get() = attempts.get()

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_BASE_BACKOFF_MILLIS = 1000L
        const val DEFAULT_MAX_BACKOFF_MILLIS = 30_000L
    }
}
