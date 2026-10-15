package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMediaKeyMapperTest {
    @Test
    fun aBoundKeyOnItsFirstPressBecomesTheCarPlayCommand() {
        assertEquals(
            CarPlayMediaButton.NEXT,
            CarPlayMediaKeyMapper.mediaIndex(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN, 0),
        )
        assertEquals(
            CarPlayMediaButton.PREVIOUS,
            CarPlayMediaKeyMapper.mediaIndex(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.ACTION_DOWN, 0),
        )
    }

    @Test
    fun anUnboundKeyIsAnsweredWithNothing() {
        assertNull(CarPlayMediaKeyMapper.mediaIndex(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 0))
        assertNull(CarPlayMediaKeyMapper.mediaIndex(KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.ACTION_DOWN, 0))
        assertNull(CarPlayMediaKeyMapper.mediaIndex(200085, KeyEvent.ACTION_DOWN, 0))
        assertFalse(CarPlayMediaKeyMapper.handles(200085))
    }

    @Test
    fun theReleaseAndTheRepeatCarryNoSecondCommand() {
        assertNull(CarPlayMediaKeyMapper.mediaIndex(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP, 0))
        assertNull(CarPlayMediaKeyMapper.mediaIndex(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN, 1))
    }

    @Test
    fun whatCountsAsOursIsWhatTheDriverBound() {
        val rebound = SteeringWheelKeyMappings.DEFAULT
            .withKeyCodes(SteeringWheelCommand.NEXT_TRACK, listOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))

        assertTrue(CarPlayMediaKeyMapper.handles(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, rebound))
        assertEquals(
            CarPlayMediaButton.NEXT,
            CarPlayMediaKeyMapper.mediaIndex(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.ACTION_DOWN, 0, rebound),
        )
        assertFalse(CarPlayMediaKeyMapper.handles(KeyEvent.KEYCODE_MEDIA_NEXT, rebound))
    }
}
