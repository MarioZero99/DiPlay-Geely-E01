package com.shilapi.xcertplay

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wheel's voice key belongs to the head unit.
 *
 * DiPlay used to answer it and ask CarPlay for Siri. On this car the press brings up the head unit's
 * own voice assistant instead -- the head unit does that in its own layer, whatever DiPlay answers --
 * so the app was consuming a key it did not get the effect of. It is now left alone, like every other
 * wheel key the driver has not bound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayHostVoiceKeyTest {
    @Test
    fun theVoiceKeyIsLeftToTheHeadUnit() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).create().get()

        assertFalse(activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOICE_ASSIST, 0)))
        assertFalse(activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOICE_ASSIST, 0)))
    }
}
