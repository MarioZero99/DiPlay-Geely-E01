package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which mode the page draws, and which mode the connection runs as, have to be one decision — that is
 * what "one source" means here. A page drawing the car hotspot while the connection ran as the same
 * network is the shape this whole change exists to remove.
 */
class WirelessModeDisplayTest {
    private val offered = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI)

    @Test
    fun theSavedModeIsWhatIsDrawnWhenNothingIsPending() {
        assertEquals(
            WirelessHotspotMode.EXISTING_WIFI,
            displayedHotspotMode(pendingSetup = false, saved = WirelessHotspotMode.EXISTING_WIFI, offered = offered),
        )
    }

    /** Choosing the car hotspot puts the page into setting it up, and that is what it draws. */
    @Test
    fun aPendingSetupIsDrawnAsTheCarHotspotMode() {
        assertEquals(
            WirelessHotspotMode.MANUAL,
            displayedHotspotMode(pendingSetup = true, saved = WirelessHotspotMode.MANUAL, offered = offered),
        )
    }

    /** A mode saved where it was offered can outlive being offered; the page keeps a way back. */
    @Test
    fun aSavedModeThatIsNoLongerOfferedFallsBackToTheFirstOneOffered() {
        assertEquals(
            WirelessHotspotMode.MANUAL,
            displayedHotspotMode(pendingSetup = false, saved = WirelessHotspotMode.WIFI_P2P, offered = offered),
        )
    }
}
