package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The switch that lets the app turn the car's hotspot on as it opens. It is off until the driver turns
 * it on, because switching it spends their Wi-Fi — and on this head unit their ADB over it — on a
 * session they have not asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AutoCarHotspotSettingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Test fun aFreshInstallLeavesItOff() {
        prefs.edit().clear().apply()
        assertFalse(AirPlayPersistence.loadAutoCarHotspot(context))
    }

    @Test fun whatTheDriverChoseIsWhatComesBack() {
        AirPlayPersistence.saveAutoCarHotspot(context, true)
        assertTrue(AirPlayPersistence.loadAutoCarHotspot(context))
        AirPlayPersistence.saveAutoCarHotspot(context, false)
        assertFalse(AirPlayPersistence.loadAutoCarHotspot(context))
    }
}
