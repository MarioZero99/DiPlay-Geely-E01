package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The transport the driver chose, and the difference between "chose wireless" and "never chose".
 *
 * The key is the one already there — `wireless_enabled` — so the five readers of
 * [AirPlayPersistence.loadWirelessEnabled] (the attempt journal, the launch auto-connect, the
 * diagnostic header) keep the same answer they had. What is new is the ability to say "nobody has
 * chosen yet": the home page needs that to ask instead of starting a wireless attempt behind the
 * driver's back, and a boolean that defaults to `true` cannot express it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class ConnectionModePersistenceTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `loadConnectionMode returns null when user has never chosen`() {
        // AirPlayPersistence.PREFS is private; this is its own file ("xcertplay_airplay"), not
        // "diplay_preferences" — that one belongs to the notification-permission prompt.
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()

        assertNull(
            "初次未选择时必须返回 null，不得假定为有线或无线",
            AirPlayPersistence.loadConnectionMode(context),
        )
        assertTrue(
            "向后兼容的 loadWirelessEnabled 未选择时仍默认 true",
            AirPlayPersistence.loadWirelessEnabled(context),
        )
    }

    @Test
    fun `saving wireless enabled updates both connection mode and wireless enabled`() {
        AirPlayPersistence.saveWirelessEnabled(context, false)
        assertEquals(false, AirPlayPersistence.loadConnectionMode(context))
        assertFalse(AirPlayPersistence.loadWirelessEnabled(context))

        AirPlayPersistence.saveWirelessEnabled(context, true)
        assertEquals(true, AirPlayPersistence.loadConnectionMode(context))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(context))
    }
}
