package com.shilapi.xcertplay

import android.content.Context
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SteeringWheelMappingsPersistenceTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun aDriverWhoNeverTouchedItGetsTheDefaultWheel() {
        assertEquals(SteeringWheelKeyMappings.DEFAULT, AirPlayPersistence.loadSteeringWheelKeyMappings(context))
    }

    @Test
    fun whatTheDriverBoundIsWhatComesBack() {
        val rebound = SteeringWheelKeyMappings.DEFAULT
            .withKeyCodes(SteeringWheelCommand.NEXT_TRACK, listOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))

        AirPlayPersistence.saveSteeringWheelKeyMappings(context, rebound)

        assertEquals(rebound, AirPlayPersistence.loadSteeringWheelKeyMappings(context))
    }

    /** A blob this build cannot read leaves a wheel that works, not one whose keys do nothing. */
    @Test
    fun storedTextFromAnotherVersionFallsBackToTheDefault() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putString("steering_wheel_key_mappings", """{"version":99,"bindings":[]}""")
            .apply()

        assertEquals(SteeringWheelKeyMappings.DEFAULT, AirPlayPersistence.loadSteeringWheelKeyMappings(context))
    }

    @Test
    fun resettingPutsTheDefaultWheelBack() {
        AirPlayPersistence.saveSteeringWheelKeyMappings(
            context,
            SteeringWheelKeyMappings.DEFAULT.withKeyCodes(SteeringWheelCommand.NEXT_TRACK, listOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)),
        )

        AirPlayPersistence.resetSteeringWheelKeyMappings(context)

        assertEquals(SteeringWheelKeyMappings.DEFAULT, AirPlayPersistence.loadSteeringWheelKeyMappings(context))
    }
}
