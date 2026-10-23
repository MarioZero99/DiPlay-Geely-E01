package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessEnvironmentReconcilerTest {
    @Test
    fun manualAutoHotspotWantsTheHotspotOn() {
        assertEquals(
            WirelessRadioTarget.HOTSPOT_ON,
            wirelessRadioTarget(WirelessHotspotMode.MANUAL, autoHotspot = true),
        )
    }

    @Test
    fun manualWithoutTheSwitchLeavesTheRadioAlone() {
        assertEquals(
            WirelessRadioTarget.LEAVE_ALONE,
            wirelessRadioTarget(WirelessHotspotMode.MANUAL, autoHotspot = false),
        )
    }

    @Test
    fun existingWifiWantsStationOn() {
        assertEquals(
            WirelessRadioTarget.STATION_ON,
            wirelessRadioTarget(WirelessHotspotMode.EXISTING_WIFI, autoHotspot = true),
        )
    }

    @Test
    fun aLiveSessionBlocksEveryReason() {
        assertTrue(sessionBlocksRadioChange(sessionActive = true))
        assertFalse(sessionBlocksRadioChange(sessionActive = false))
    }

    @Test
    fun cooldownBlocksResumeAndNotAModeSwitch() {
        assertTrue(
            cooldownBlocks(
                reason = WirelessSyncReason.RESUME,
                bypassCooldown = false,
                coolingDown = true,
            ),
        )
        assertFalse(
            cooldownBlocks(
                reason = WirelessSyncReason.MODE_SWITCH,
                bypassCooldown = false,
                coolingDown = true,
            ),
        )
        assertFalse(
            cooldownBlocks(
                reason = WirelessSyncReason.RESUME,
                bypassCooldown = true,
                coolingDown = true,
            ),
        )
    }

    @Test
    fun hotspotAlreadyOnDoesNotNeedAnotherCommand() {
        assertTrue(
            radioAlreadyMatches(
                WirelessRadioTarget.HOTSPOT_ON,
                hotspotFullyOn = true,
                hotspotFullyOff = false,
                stationOn = false,
            ),
        )
    }

    @Test
    fun stationModeWaitsUntilTheHotspotIsFullyOff() {
        assertFalse(
            radioAlreadyMatches(
                WirelessRadioTarget.STATION_ON,
                hotspotFullyOn = false,
                hotspotFullyOff = false,
                stationOn = true,
            ),
        )
        assertTrue(
            radioAlreadyMatches(
                WirelessRadioTarget.STATION_ON,
                hotspotFullyOn = false,
                hotspotFullyOff = true,
                stationOn = true,
            ),
        )
    }

    @Test
    fun aNewerGenerationRetiresThePreviousRequest() {
        val first = 1
        val second = 2
        assertTrue(syncRequestStillCurrent(first, currentGeneration = first))
        assertFalse(syncRequestStillCurrent(first, currentGeneration = second))
    }
}
