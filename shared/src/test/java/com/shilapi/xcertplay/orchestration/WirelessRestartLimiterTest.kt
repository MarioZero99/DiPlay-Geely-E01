package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessRestartLimiterTest {

    @Test
    fun exponentialBackoffIncrementsWithAttempts() {
        val limiter = WirelessRestartLimiter(maxAttempts = 5, baseBackoffMillis = 1000L, maxBackoffMillis = 30_000L)

        assertEquals(1000L, limiter.nextBackoffOrNull())
        assertEquals(2000L, limiter.nextBackoffOrNull())
        assertEquals(4000L, limiter.nextBackoffOrNull())
        assertEquals(8000L, limiter.nextBackoffOrNull())
        assertEquals(16000L, limiter.nextBackoffOrNull())
    }

    @Test
    fun exhaustsBudgetAfterMaxAttempts() {
        val limiter = WirelessRestartLimiter(maxAttempts = 3, baseBackoffMillis = 500L)

        assertEquals(500L, limiter.nextBackoffOrNull())
        assertEquals(1000L, limiter.nextBackoffOrNull())
        assertEquals(2000L, limiter.nextBackoffOrNull())
        assertNull("Attempt 4 exceeds budget of 3", limiter.nextBackoffOrNull())
        assertNull("Attempt 5 remains exhausted", limiter.nextBackoffOrNull())
        assertEquals(5, limiter.currentAttempts)
    }

    @Test
    fun capsAtMaxBackoffMillis() {
        val limiter = WirelessRestartLimiter(maxAttempts = 5, baseBackoffMillis = 10_000L, maxBackoffMillis = 25_000L)

        assertEquals(10_000L, limiter.nextBackoffOrNull())
        assertEquals(20_000L, limiter.nextBackoffOrNull())
        assertEquals(25_000L, limiter.nextBackoffOrNull()) // capped at 25000
        assertEquals(25_000L, limiter.nextBackoffOrNull()) // capped at 25000
    }

    @Test
    fun resetClearsAttemptsAndRestoresBudget() {
        val limiter = WirelessRestartLimiter(maxAttempts = 2, baseBackoffMillis = 1000L)

        assertEquals(1000L, limiter.nextBackoffOrNull())
        assertEquals(2000L, limiter.nextBackoffOrNull())
        assertNull(limiter.nextBackoffOrNull())

        limiter.reset()
        assertEquals(0, limiter.currentAttempts)
        assertEquals(1000L, limiter.nextBackoffOrNull())
    }
}
