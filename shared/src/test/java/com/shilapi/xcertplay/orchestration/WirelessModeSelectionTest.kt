package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.WirelessHotspotMode
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one step between the mode the driver chose and the mode a session runs.
 *
 * Everything downstream reads the mode that comes out of here, so a mode replaced here is a mode that
 * was never implemented — and, because the stored setting is written by a different code path, one
 * whose setting still reads back correctly. That is the failure this pins: not a crash, and not
 * something a diagnostic line would show, but a run that behaves as though the choice was never made.
 */
class WirelessModeSelectionTest {
    private val api22 = Build.VERSION_CODES.LOLLIPOP_MR1

    @Test
    fun joiningAnExistingNetworkIsNeverReplacedOnAnAndroid5HeadUnit() {
        assertEquals(
            WirelessHotspotMode.EXISTING_WIFI,
            effectiveHotspotMode(WirelessHotspotMode.EXISTING_WIFI, api22),
        )
    }

    @Test
    fun joiningAnExistingNetworkSurvivesEveryPlatformThisProjectCanRunOn() {
        for (sdkInt in listOf(21, 22, 23, 24, 26, 28, 29, 31, 34)) {
            assertEquals(
                "sdk $sdkInt",
                WirelessHotspotMode.EXISTING_WIFI,
                effectiveHotspotMode(WirelessHotspotMode.EXISTING_WIFI, sdkInt),
            )
        }
    }

    /**
     * The floor is Android 5, not this project's Android 4.4: reading one network at a time out of
     * `ConnectivityManager` is API 21, and below it the mode falls back rather than throwing at the
     * driver. The settings page does not offer it there either, so this is the carried-over setting.
     */
    @Test
    fun joiningAnExistingNetworkFallsBackBelowAndroid5() {
        assertEquals(WirelessHotspotMode.MANUAL, effectiveHotspotMode(WirelessHotspotMode.EXISTING_WIFI, 19))
        assertEquals(WirelessHotspotMode.MANUAL, effectiveHotspotMode(WirelessHotspotMode.EXISTING_WIFI, 20))
    }

    @Test
    fun theCarsOwnHotspotIsAlwaysTheModeTheDriverChose() {
        for (sdkInt in listOf(19, 22, 26, 29, 34)) {
            assertEquals(
                "sdk $sdkInt",
                WirelessHotspotMode.MANUAL,
                effectiveHotspotMode(WirelessHotspotMode.MANUAL, sdkInt),
            )
        }
    }

    @Test
    fun theLocalOnlyHotspotFallsBackToTheCarHotspotBelowAndroid8() {
        assertEquals(WirelessHotspotMode.MANUAL, effectiveHotspotMode(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, api22))
        assertEquals(
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT,
            effectiveHotspotMode(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, 26),
        )
    }
}
