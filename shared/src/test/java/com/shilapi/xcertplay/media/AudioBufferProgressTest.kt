package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioBufferProgressTest {
    @Test fun musicRebuffersOnlyAfterHardwareAndIncomingQueuesDrain() {
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        assertFalse(buffer.shouldRebuffer("media", true, true, true, 999))
        assertFalse(buffer.shouldRebuffer("media", true, true, false, 1000))
        assertFalse(buffer.shouldRebuffer("media", true, false, true, 1000))
        assertTrue(buffer.shouldRebuffer("media", true, true, true, 1000))
        assertFalse(buffer.shouldRebuffer("media", false, true, true, 1000))
        buffer.written(400)
        assertEquals(400, buffer.queuedBytes(1000))
        assertFalse(buffer.shouldRebuffer("media", true, true, true, 1000))
    }

    @Test fun doesNotAddRebufferDelayToCallsOrSpeech() {
        val buffer = AudioBufferProgress(2)
        for (type in listOf("telephony", "speechrecognition", "default", "alert")) {
            assertFalse(buffer.shouldRebuffer(type, true, true, true, 0))
        }
    }

    @Test fun unsignedPlaybackHeadWrapKeepsQueuedAudio() {
        val buffer = AudioBufferProgress(2)
        repeat(8) { buffer.written(1_073_741_824) }
        buffer.written(40)
        assertEquals(42, buffer.queuedBytes(-1))
        assertEquals(40, buffer.queuedBytes(0))
        assertEquals(0, buffer.queuedBytes(20))
    }

    @Test fun aPlatformWithoutUnderrunCountsOnlyCountsADrainOnce() {
        assertEquals(DrainAction.COUNT, drainAction(drained = true, platformReportsUnderruns = false, alreadyCounted = false))
        assertEquals(DrainAction.NONE, drainAction(drained = true, platformReportsUnderruns = false, alreadyCounted = true))
        assertEquals(DrainAction.NONE, drainAction(drained = false, platformReportsUnderruns = false, alreadyCounted = true))
    }

    @Test fun aPlatformThatReportsUnderrunsPausesWhenMusicDrains() {
        assertEquals(DrainAction.PAUSE_AND_REBUFFER, drainAction(drained = true, platformReportsUnderruns = true, alreadyCounted = false))
        assertEquals(DrainAction.NONE, drainAction(drained = false, platformReportsUnderruns = true, alreadyCounted = false))
    }
}
