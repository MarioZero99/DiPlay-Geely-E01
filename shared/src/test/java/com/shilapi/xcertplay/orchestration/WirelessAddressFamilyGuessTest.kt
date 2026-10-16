package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessAddressFamilyGuessTest {
    @Test
    fun `one connect moves once and then reports instead of moving again`() {
        val guess = WirelessAddressFamilyGuess()
        assertNull("a fresh connect starts from the default order", guess.family)

        assertEquals("IPv6", guess.onUnusable("IPv6"))
        assertEquals("IPv6", guess.family)

        assertNull("the second finding in one connect is a failure, not a move", guess.onUnusable("IPv4"))
        assertEquals("IPv6", guess.family)
    }

    @Test
    fun `the next connect moves on its own answer, not on the last one's`() {
        val guess = WirelessAddressFamilyGuess()
        guess.onUnusable("IPv6")

        guess.clear()
        assertNull(guess.family)

        assertEquals("IPv4", guess.onUnusable("IPv4"))
    }
}
