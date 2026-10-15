package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.WirelessHotspotMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A "the hotspot details are not saved yet" flag belongs to the car-hotspot mode. The two wireless
 * modes are two independent routes, and one route's half-finished state is not the other's door.
 */
class HotspotSetupGateTest {
    @Test
    fun aPendingHotspotSetupOnlyBlocksTheCarHotspotMode() {
        assertTrue(hotspotSetupBlocksConnect(WirelessHotspotMode.MANUAL, setupPending = true))
        assertFalse(hotspotSetupBlocksConnect(WirelessHotspotMode.EXISTING_WIFI, setupPending = true))
        assertFalse(hotspotSetupBlocksConnect(WirelessHotspotMode.EXISTING_WIFI, setupPending = false))
        assertFalse(hotspotSetupBlocksConnect(WirelessHotspotMode.MANUAL, setupPending = false))
    }
}
