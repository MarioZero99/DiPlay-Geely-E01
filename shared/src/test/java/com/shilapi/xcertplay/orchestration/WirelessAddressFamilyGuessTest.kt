package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessAddressFamilyGuessTest {
    @Test
    fun `one connect moves once and then reports instead of moving again`() {
        val guess = WirelessAddressFamilyGuess()
        assertNull("a fresh connect starts from the default order", guess.family())

        assertEquals("IPv6", guess.onUnusable("IPv6"))
        assertEquals("IPv6", guess.family())

        assertNull("the second finding in one connect is a failure, not a move", guess.onUnusable("IPv4"))
        assertEquals("IPv6", guess.family())
    }

    @Test
    fun `the next connect moves on its own answer, not on the last one's when cleared`() {
        val guess = WirelessAddressFamilyGuess()
        guess.onUnusable("IPv6")

        guess.clear()
        assertNull(guess.family())

        assertEquals("IPv4", guess.onUnusable("IPv4"))
    }

    @Test
    fun `active session remembers working family in memory`() {
        val guess = WirelessAddressFamilyGuess()
        guess.onSessionActive("IPv6")

        assertEquals("IPv6", guess.family())

        // A subsequent retry or move after a new attempt allows moving
        guess.onNewAttempt()
        assertEquals("IPv6", guess.family())
        assertEquals("IPv4", guess.onUnusable("IPv4"))
        assertEquals("IPv4", guess.family())
    }

    @Test
    fun `new attempt preserves remembered family but resets per-attempt move lock`() {
        val guess = WirelessAddressFamilyGuess()
        guess.onUnusable("IPv6")
        assertEquals("IPv6", guess.family())
        assertNull(guess.onUnusable("IPv4"))

        guess.onNewAttempt()
        // Remembered family is still IPv6
        assertEquals("IPv6", guess.family())
        // But moving is allowed again for this new attempt
        assertEquals("IPv4", guess.onUnusable("IPv4"))
        assertEquals("IPv4", guess.family())
    }

    @Test
    fun `preferences are isolated per wireless hotspot mode`() {
        val guess = WirelessAddressFamilyGuess()
        guess.onSessionActive("IPv6", WirelessHotspotMode.EXISTING_WIFI)
        guess.onSessionActive("IPv4", WirelessHotspotMode.MANUAL)

        assertEquals("IPv6", guess.family(WirelessHotspotMode.EXISTING_WIFI))
        assertEquals("IPv4", guess.family(WirelessHotspotMode.MANUAL))
        assertNull(guess.family(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT))
    }
}
