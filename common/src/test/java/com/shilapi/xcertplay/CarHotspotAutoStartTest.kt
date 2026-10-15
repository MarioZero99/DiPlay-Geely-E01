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
