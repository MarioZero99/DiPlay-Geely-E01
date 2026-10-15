package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether opening the app switches the car's hotspot on. The setting defaults to off, and the answer
 * is only ever yes for the one mode whose access point the app does not bring up itself.
 */
class CarHotspotAutoStartTest {
    @Test
    fun staysOffUntilTheDriverAsksForIt() {
        assertFalse(
            shouldWakeCarHotspotOnStartup(
                autoEnabled = false,
                mode = WirelessHotspotMode.MANUAL,
            ),
        )
    }

    @Test
    fun wakesTheCarsOwnHotspotWhenItIsAskedFor() {
        assertTrue(
            shouldWakeCarHotspotOnStartup(
                autoEnabled = true,
                mode = WirelessHotspotMode.MANUAL,
            ),
        )
    }

    /**
     * The mode that joins a network somebody else owns is the one mode that needs the car's hotspot
     * *off*, and it is the one mode whose access point DiPlay does not bring up.
     *
     * Pinned rather than left to the `== MANUAL` comparison that happens to produce it: the answer
     * here is not "this mode has its own access point", it is "waking one would take the radio this
     * mode is about to use".
     */
    @Test
    fun doesNotWakeTheCarHotspotForTheModeThatJoinsSomeoneElsesNetwork() {
        assertFalse(
            shouldWakeCarHotspotOnStartup(
                autoEnabled = true,
                mode = WirelessHotspotMode.EXISTING_WIFI,
            ),
        )
    }

    @Test
    fun leavesTheModesThatBringUpTheirOwnAccessPointAlone() {
        // Wi-Fi Direct and the local-only hotspot are started when a session starts, so waking one at
        // app start would either do nothing or start an access point the driver is not using yet.
        assertFalse(
            shouldWakeCarHotspotOnStartup(
                autoEnabled = true,
                mode = WirelessHotspotMode.WIFI_P2P,
            ),
        )
        assertFalse(
            shouldWakeCarHotspotOnStartup(
                autoEnabled = true,
                mode = WirelessHotspotMode.LOCAL_ONLY_HOTSPOT,
            ),
        )
    }
}
