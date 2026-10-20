package com.shilapi.xcertplay.media

/** Tracks the unsigned AudioTrack head across wrap without flushing already queued sound. */
internal class AudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    fun written(bytes: Int) { writtenBytes += bytes }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    fun shouldRebuffer(audioType: String, playing: Boolean, underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean, rawHead: Int): Boolean =
        audioType == "media" && playing && underrunSinceStart && compressedQueueEmpty && queuedBytes(rawHead) == 0L
}

internal enum class DrainAction { NONE, COUNT, PAUSE_AND_REBUFFER }

/**
 * What to do once music has drained.
 *
 * API 24 and above can see [AudioTrack.getUnderrunCount], and a drain there pauses so the start
 * threshold can fill again. Below that the platform has no underrun count. Pausing there makes the
 * gap longer by the whole start threshold, so the drain is only counted.
 */
internal fun drainAction(drained: Boolean, platformReportsUnderruns: Boolean, alreadyCounted: Boolean): DrainAction {
    if (!drained) return DrainAction.NONE
    if (!platformReportsUnderruns) return if (alreadyCounted) DrainAction.NONE else DrainAction.COUNT
    return DrainAction.PAUSE_AND_REBUFFER
}
