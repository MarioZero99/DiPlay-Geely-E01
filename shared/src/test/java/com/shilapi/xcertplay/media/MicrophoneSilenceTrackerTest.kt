package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #102: a telephony uplink on VOICE_COMMUNICATION that captures nothing but zeroes must be
 * recognised as a broken source, so later telephony uplinks use MIC instead of repeating silence.
 */
class MicrophoneSilenceTrackerTest {

    private fun trackerOf(silentFrames: Long, audioFrames: Long = 0): MicrophoneSilenceTracker {
        val tracker = MicrophoneSilenceTracker()
        repeat(silentFrames.toInt()) { tracker.frame(true) }
        repeat(audioFrames.toInt()) { tracker.frame(false) }
        return tracker
    }

    @Test
    fun belowThresholdIsNotBroken() {
        val tracker = trackerOf(MicrophoneSilenceTracker.BROKEN_SILENCE_FRAMES - 1)
        assertFalse(tracker.isVoiceCommunicationBroken("telephony", usedVoiceCommunication = true))
    }

    @Test
    fun allSilentTelephonyOnVoiceCommunicationIsBroken() {
        val tracker = trackerOf(MicrophoneSilenceTracker.BROKEN_SILENCE_FRAMES)
        assertTrue(tracker.neverReceivedAudio())
        assertTrue(tracker.isVoiceCommunicationBroken("telephony", usedVoiceCommunication = true))
    }

    @Test
    fun anyCapturedAudioPreventsBrokenEvenAfterLongSilence() {
        val tracker = trackerOf(MicrophoneSilenceTracker.BROKEN_SILENCE_FRAMES + 500, audioFrames = 1)
        assertFalse(tracker.neverReceivedAudio())
        assertFalse(tracker.isVoiceCommunicationBroken("telephony", usedVoiceCommunication = true))
    }

    @Test
    fun nonTelephonyOrNonVoiceCommunicationSourceNeverCountsAsBroken() {
        val tracker = trackerOf(MicrophoneSilenceTracker.BROKEN_SILENCE_FRAMES * 2)
        assertFalse(tracker.isVoiceCommunicationBroken("speechrecognition", usedVoiceCommunication = true))
        assertFalse(tracker.isVoiceCommunicationBroken("telephony", usedVoiceCommunication = false))
    }

    @Test
    fun silentFrameCountAccumulates() {
        val tracker = trackerOf(3, audioFrames = 2)
        assertEquals(3L, tracker.silentFrameCount)
    }
}
