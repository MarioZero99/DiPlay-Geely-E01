package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The station radio's bring-up, as a rule rather than as a platform call.
 *
 * What is pinned here is when the route gives up: a refusal on the first turn is not the answer,
 * because the access point it is following may still be going down, and the answer the platform
 * gives to the request is not the state of the radio. What is *not* here is the Android call itself
 * — that is [ExistingWifiManager]'s, and Robolectric cannot be made to refuse it.
 */
class StationBringUpTest {
    @Test
    fun aRadioThatIsAlreadyOnIsNotAsked() {
        var asked = 0

        val ready = bringUpStation(
            isEnabled = { true },
            requestEnable = { asked++; true },
            timeoutMillis = 5_000,
            pollMillis = 250,
            now = { 0 },
            sleep = {},
        )

        assertTrue(ready)
        assertEquals("nothing to ask for", 0, asked)
    }

    /**
     * The case that matters: the first request is refused because the access point is still going
     * down, the second one takes. Treating the first answer as final would fail a route that was
     * about to work.
     */
    @Test
    fun aRadioThatComesUpOnALaterTurnIsNotAFailure() {
        var enabled = false
        var asked = 0
        var clock = 0L

        val ready = bringUpStation(
            isEnabled = { enabled },
            requestEnable = {
                asked++
                if (asked == 2) enabled = true
                asked == 2
            },
            timeoutMillis = 5_000,
            pollMillis = 250,
            now = { clock },
            sleep = { clock += it },
        )

        assertTrue(ready)
        assertEquals(2, asked)
        assertEquals("and it waited between the two", 250L, clock)
    }

    @Test
    fun aRadioThatNeverComesUpGivesUpAtTheDeadline() {
        var clock = 0L
        var asked = 0

        val ready = bringUpStation(
            isEnabled = { false },
            requestEnable = { asked++; false },
            timeoutMillis = 1_000,
            pollMillis = 250,
            now = { clock },
            sleep = { clock += it },
        )

        assertFalse(ready)
        assertTrue("it kept trying until the deadline", clock >= 1_000)
        assertEquals("asked at 0, 250, 500, 750 and 1000", 5, asked)
    }
}
