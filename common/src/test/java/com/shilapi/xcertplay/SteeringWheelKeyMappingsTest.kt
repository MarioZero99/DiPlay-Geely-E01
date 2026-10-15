package com.shilapi.xcertplay

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wheel is ours only where the driver bound it.
 *
 * Taken from the official GeekPlay build for this head unit: two commands, a binding table, and a key
 * code range that is Android's own. The point of the table is the negative case — every code that is
 * not in it goes back to the head unit, which is what the car's own menu key needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SteeringWheelKeyMappingsTest {
    private val mappings = SteeringWheelKeyMappings.DEFAULT

    @Test
    fun theDefaultsBindTheTwoTracksAndNothingElse() {
        assertEquals(SteeringWheelCommand.NEXT_TRACK, mappings.commandFor(87, KeyEvent.ACTION_DOWN, 0))
        assertEquals(SteeringWheelCommand.PREVIOUS_TRACK, mappings.commandFor(88, KeyEvent.ACTION_DOWN, 0))
        assertEquals(SteeringWheelCommand.PREVIOUS_TRACK, mappings.commandFor(89, KeyEvent.ACTION_DOWN, 0))
        assertEquals(listOf(87), mappings.keyCodesFor(SteeringWheelCommand.NEXT_TRACK))
        assertEquals(listOf(88, 89), mappings.keyCodesFor(SteeringWheelCommand.PREVIOUS_TRACK))
    }

    /**
     * The regression this table exists for: the play/pause family and the voice key are not bound, so
     * they are not DiPlay's to take. Before this, the capture test was "can this code become a CarPlay
     * action at all", which answered yes for all of these — including the vendor codes this head unit
     * puts on keys DiPlay never meant to claim.
     */
    @Test
    fun everyKeyTheDriverDidNotBindIsLeftToTheHeadUnit() {
        val neverOurs = listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_VOICE_ASSIST,
            CarPlayMediaButtonBydPlayPause, CarPlayMediaButtonBydVoice, CarPlayMediaButtonBydVoiceLong,
            EcartPlayPause, EcartNext, EcartPrevious, EcartVoice,
        )

        neverOurs.forEach { assertFalse("key $it must not be captured", mappings.handles(it)) }
    }

    @Test
    fun onlyAFirstPressCarriesACommand() {
        assertEquals(SteeringWheelCommand.NEXT_TRACK, mappings.commandFor(87, KeyEvent.ACTION_DOWN, 0))
        assertNull(mappings.commandFor(87, KeyEvent.ACTION_UP, 0))
        assertNull(mappings.commandFor(87, KeyEvent.ACTION_DOWN, 1))
    }

    @Test
    fun onlyAndroidKeyCodesCanBeBound() {
        assertTrue(SteeringWheelKeyMappings.isValidKeyCode(1))
        assertTrue(SteeringWheelKeyMappings.isValidKeyCode(1000))
        assertFalse(SteeringWheelKeyMappings.isValidKeyCode(0))
        assertFalse(SteeringWheelKeyMappings.isValidKeyCode(-1))
        assertFalse(SteeringWheelKeyMappings.isValidKeyCode(1001))
        assertFalse(SteeringWheelKeyMappings.isValidKeyCode(EcartPrevious))

        assertFalse(SteeringWheelKeyMappings.from(mapOf(EcartPrevious to SteeringWheelCommand.PREVIOUS_TRACK)).handles(EcartPrevious))
    }

    /**
     * The whole ECARX family is out of reach, including the car's own menu key: not by default, and not
     * by hand either. That is the outermost guard on this head unit -- a wheel key DiPlay has no
     * business answering cannot be claimed even by a mistake in the stored table.
     */
    @Test
    fun noVendorCodeCanEnterTheTable() {
        val vendorFamily = listOf(EcartNext, EcartPrevious, EcartPlayPause, EcartVoice, EcartMenu)

        vendorFamily.forEach { assertFalse(SteeringWheelKeyMappings.isValidKeyCode(it)) }
    }

    @Test
    fun rebindingMovesTheCommandOffItsOldCodes() {
        val rebound = mappings.withKeyCodes(SteeringWheelCommand.NEXT_TRACK, listOf(90, KeyEvent.KEYCODE_MEDIA_NEXT))

        assertEquals(listOf(87, 90), rebound.keyCodesFor(SteeringWheelCommand.NEXT_TRACK))
        assertEquals(listOf(88, 89), rebound.keyCodesFor(SteeringWheelCommand.PREVIOUS_TRACK))
    }

    @Test
    fun rebindingIgnoresCodesOutsideTheAndroidRange() {
        val rebound = mappings.withKeyCodes(SteeringWheelCommand.NEXT_TRACK, listOf(90, EcartNext))

        assertEquals(listOf(90), rebound.keyCodesFor(SteeringWheelCommand.NEXT_TRACK))
        assertFalse(rebound.handles(EcartNext))
    }

    @Test
    fun theMappingsSurviveARoundTrip() {
        val rebound = mappings.withKeyCodes(SteeringWheelCommand.NEXT_TRACK, listOf(90))

        assertEquals(rebound, SteeringWheelKeyMappingsCodec.decode(SteeringWheelKeyMappingsCodec.encode(rebound)))
    }

    @Test
    fun aBlobFromAnotherVersionIsIgnoredRatherThanRead() {
        assertNull(SteeringWheelKeyMappingsCodec.decode("""{"version":2,"bindings":[]}"""))
    }

    @Test
    fun unreadableStoredTextDecodesToNothing() {
        assertNull(SteeringWheelKeyMappingsCodec.decode("not json at all"))
        assertNull(SteeringWheelKeyMappingsCodec.decode("""{"bindings":[]}"""))
    }

    @Test
    fun anEntryNamingACommandThatDoesNotExistIsDropped() {
        val decoded = SteeringWheelKeyMappingsCodec.decode(
            """{"version":1,"bindings":[{"keyCode":90,"command":"NOPE"},{"keyCode":87,"command":"NEXT_TRACK"}]}""",
        )

        assertEquals(listOf(87), decoded?.keyCodesFor(SteeringWheelCommand.NEXT_TRACK))
    }

    private companion object {
        // BYD's wheel codes, which this head unit never sends but the shared table still knew.
        const val CarPlayMediaButtonBydPlayPause = 353
        const val CarPlayMediaButtonBydVoice = 304
        const val CarPlayMediaButtonBydVoiceLong = 312

        // ECARX's vendor codes for the Geely wheel.
        const val EcartNext = 200087
        const val EcartPrevious = 200088
        const val EcartPlayPause = 200085
        const val EcartVoice = 200231
        const val EcartMenu = 200082
    }
}
