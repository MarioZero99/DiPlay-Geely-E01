package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.network.WirelessStartupPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window the address-family flip is decided in. What matters is that it closes on the phone never
 * opening the port, and that it does not close on anything else — a phone that did open the port must
 * not have the family flipped out from under it.
 */
class FirstTcpWatchdogTest {
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private var timeouts = 0
    private var now = 0L

    private fun watchdog() = FirstTcpWatchdog(
        schedule = { delay, body -> scheduled += delay to body },
        onTimeout = { timeouts++ },
        nowNanos = { now },
    )

    /** Runs whatever the watchdog asked to be run later, as the scheduler eventually would. */
    private fun runScheduled() {
        val pending = scheduled.toList()
        scheduled.clear()
        pending.forEach { (_, body) -> body() }
    }

    @Test
    fun nothingOnTheAirPlayPortEndsTheWaitWhenTheWindowCloses() {
        val watchdog = watchdog()
        watchdog.startSessionSent()
        assertEquals(listOf(WirelessStartupPolicy.FIRST_TCP_MILLIS), scheduled.map { (delay, _) -> delay })
        assertEquals(0, timeouts)

        runScheduled()

        assertEquals(1, timeouts)
    }

    @Test
    fun anAcceptInsideTheWindowStopsTheTimeout() {
        val watchdog = watchdog()
        watchdog.startSessionSent()
        now = 1_000_000_000L

        assertTrue(watchdog.accepted())
        runScheduled()

        assertEquals(0, timeouts)
    }

    /** Bonjour can get the phone to the port before 0x4301 goes out; that still answers the question. */
    @Test
    fun anAcceptThatArrivesBeforeStartSessionIsNotForgotten() {
        val watchdog = watchdog()
        assertTrue(watchdog.accepted())

        watchdog.startSessionSent()

        assertTrue(scheduled.isEmpty())
        runScheduled()
        assertEquals(0, timeouts)
    }

    /** The phone may ask more than once, and a second arm must not add a second window. */
    @Test
    fun aSecondStartSessionDoesNotScheduleASecondWindow() {
        val watchdog = watchdog()
        watchdog.startSessionSent()
        watchdog.startSessionSent()

        assertEquals(1, scheduled.size)
    }

    @Test
    fun existingWifiTimeoutSchedulesShortWindowAndExpires() {
        val watchdog = FirstTcpWatchdog(
            schedule = { delay, body -> scheduled += delay to body },
            onTimeout = { timeouts++ },
            nowNanos = { now },
            timeoutMillis = WirelessStartupPolicy.FIRST_TCP_EXISTING_WIFI_MILLIS,
        )
        watchdog.startSessionSent()
        assertEquals(listOf(WirelessStartupPolicy.FIRST_TCP_EXISTING_WIFI_MILLIS), scheduled.map { (delay, _) -> delay })
        assertEquals(0, timeouts)

        runScheduled()
        assertEquals(1, timeouts)
    }

    @Test
    fun existingWifiColdTimeoutSchedulesTenSecondWindowAndExpires() {
        val watchdog = FirstTcpWatchdog(
            schedule = { delay, body -> scheduled += delay to body },
            onTimeout = { timeouts++ },
            nowNanos = { now },
            timeoutMillis = WirelessStartupPolicy.FIRST_TCP_EXISTING_WIFI_COLD_MILLIS,
        )
        watchdog.startSessionSent()
        assertEquals(listOf(WirelessStartupPolicy.FIRST_TCP_EXISTING_WIFI_COLD_MILLIS), scheduled.map { (delay, _) -> delay })
        assertEquals(0, timeouts)

        runScheduled()
        assertEquals(1, timeouts)
    }
}
