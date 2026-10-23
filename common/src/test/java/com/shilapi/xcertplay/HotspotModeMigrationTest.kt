package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.transport.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HotspotModeMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Test fun oldLocalSelectionMigratesWithoutLosingCarHotspotDetails() {
        prefs.edit().putString("wireless_hotspot_mode", "LOCAL_ONLY_HOTSPOT").apply()
        AirPlayPersistence.saveManualHotspotSsid(context, "Test car")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "test-password")
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("MANUAL", prefs.getString("wireless_hotspot_mode", null))
        assertEquals("Test car", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("test-password", AirPlayPersistence.loadManualHotspotPassphrase(context))
    }

    @Test fun freshInstallUsesBuiltInHotspot() {
        prefs.edit().clear().apply()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    /**
     * An install that offered Wi-Fi Direct leaves `WIFI_P2P` in storage. The mode is gone from the
     * enum, so the read has to answer with one that exists and write that back — otherwise every
     * read is the migration again, and the connection page is deciding from a mode no code answers to.
     *
     * Pinned at API 33, where this build used to keep the stored value: the fallback is the mode being
     * gone, not the platform being old, so a head unit new enough to have offered it migrates too.
     */
    @Test fun aStoredWifiDirectSelectionReadsBackAsTheCarHotspot() {
        prefs.edit().putString("wireless_hotspot_mode", "WIFI_P2P").apply()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("MANUAL", prefs.getString("wireless_hotspot_mode", null))
    }

    @Test @Config(sdk = [28]) fun olderAndroidDoesNotFallBackToRemovedLocalMode() {
        prefs.edit().putString("wireless_hotspot_mode", "LOCAL_ONLY_HOTSPOT").apply()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    /**
     * Same-network Wi-Fi is an official mode, so an official package retains an EXISTING_WIFI selection
     * without demoting it to the car hotspot.
     */
    @Test fun anOfficialPackageKeepsASameNetworkSelection() {
        ExperimentalFeatures.override(false)
        try {
            prefs.edit().putString("wireless_hotspot_mode", "EXISTING_WIFI").apply()
            assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(context))
            assertEquals("EXISTING_WIFI", prefs.getString("wireless_hotspot_mode", null))
        } finally {
            ExperimentalFeatures.resetOverride()
        }
    }

    @Test fun aLocalPackageKeepsTheSameNetworkSelection() {
        ExperimentalFeatures.override(true)
        try {
            AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
            assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(context))
        } finally {
            ExperimentalFeatures.resetOverride()
        }
    }
}
