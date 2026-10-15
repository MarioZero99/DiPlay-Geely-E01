package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

/**
 * Hardware media keys → CarPlay media HID presses (indices into [AirPlayHid]'s media report).
 *
 * Hardware play and pause keys both map to the toggle: BYD picks PLAY or PAUSE from its own idea of
 * the play state, and a wrong guess would make the button do nothing. Explicit play and pause
 * commands from media controllers use [PLAY] and [PAUSE].
 */
object CarPlayMediaButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    /** BYD's steering-wheel play/pause key; the firmware normally rewrites it to MEDIA_PLAY/PAUSE. */
    const val KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE = 353

    /** BYD's steering-wheel voice key: a short press, and the code the wheel sends for a long press. */
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE = 304
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG = 312

    /**
     * Geely's ECARX head units report steering-wheel media keys with a vendor prefix added to the
     * standard key code: 200000 for the media keys, 110000 and 210000 for the seek keys. A Geely
     * wheel arrives as one of these values rather than as 85/87/88/231, so the standard codes below
     * would never match it.
     *
     * ECARX spells the seek pair `KEYCODE_SEEK_REVIOUS` and `KEYCODE_R_SEEK_REVIOUS`; the names here
     * use the correct spelling.
     *
     * Nothing asks the head unit for these any more. This head unit translates them into the standard
     * codes before they reach an app (`200088` arrives as `KEYCODE_MEDIA_PREVIOUS`), so the wheel is
     * read through `SteeringWheelKeyMappings` -- and that table holds Android's codes only, which is
     * what keeps a vendor code like the car's own menu key out of DiPlay's hands.
     */
    const val KEYCODE_ECARX_MEDIA_PLAY_PAUSE = 200085
    const val KEYCODE_ECARX_MEDIA_NEXT = 200087
    const val KEYCODE_ECARX_MEDIA_PREVIOUS = 200088
    const val KEYCODE_ECARX_VOICE_ASSIST = 200231
    const val KEYCODE_ECARX_SEEK_NEXT = 110005
    const val KEYCODE_ECARX_SEEK_PREVIOUS = 110006
    const val KEYCODE_ECARX_R_SEEK_NEXT = 210005
    const val KEYCODE_ECARX_R_SEEK_PREVIOUS = 210006

    /**
     * Whether [keyCode] is a voice key that opens Siri. The BYD wheel sends each press as an
     * instant down/up pair, so a long press arrives as its own key rather than as a held one.
     */
    fun opensSiri(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
        keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE || keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG ||
        keyCode == KEYCODE_ECARX_VOICE_ASSIST

    /** The CarPlay press for [keyCode], or null when the key is not a media key CarPlay handles. */
    fun forKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KEYCODE_ECARX_MEDIA_NEXT,
        KEYCODE_ECARX_SEEK_NEXT,
        KEYCODE_ECARX_R_SEEK_NEXT -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KEYCODE_ECARX_MEDIA_PREVIOUS,
        KEYCODE_ECARX_SEEK_PREVIOUS,
        KEYCODE_ECARX_R_SEEK_PREVIOUS -> PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE,
        KEYCODE_ECARX_MEDIA_PLAY_PAUSE -> PLAY_PAUSE
        else -> null
    }
}
