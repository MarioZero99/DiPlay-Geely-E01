package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.cos

/**
 * Audio smoothing utility for 16-bit PCM Little Endian streams.
 *
 * Provides frame-aligned cosine fade-in and fade-out to prevent pop noise at boundaries,
 * signed 16-bit integer safety without overflow, and frame-aligned silence creation.
 */
object PcmSmoother {
    const val FADE_MS = 15

    fun fadeFrameCount(sampleRate: Int): Int = sampleRate * FADE_MS / 1000

    fun fadeByteCount(sampleRate: Int, channels: Int): Int =
        fadeFrameCount(sampleRate) * (if (channels >= 2) 4 else 2)

    /**
     * Applies cosine fade-in from 0 to 1 across frames in [pcm].
     * Both channels in stereo frames use the exact same factor.
     */
    fun applyFadeIn(pcm: ByteArray, offset: Int, validLength: Int, sampleRate: Int, channels: Int) {
        val frameBytes = if (channels >= 2) 4 else 2
        val actualFrames = validLength / frameBytes
        val fadeFrames = minOf(actualFrames, fadeFrameCount(sampleRate))
        if (fadeFrames <= 0) return

        val chCount = minOf(channels, 2)
        for (i in 0 until fadeFrames) {
            val factor = 0.5 * (1.0 - cos(PI * (i + 1).toDouble() / fadeFrames.toDouble()))
            val frameOffset = offset + i * frameBytes
            for (ch in 0 until chCount) {
                val pos = frameOffset + ch * 2
                val low = pcm[pos].toInt() and 0xFF
                val high = pcm[pos + 1].toInt()
                val sample = (high shl 8) or low
                val scaled = (sample * factor).toInt().coerceIn(-32768, 32767)
                pcm[pos] = (scaled and 0xFF).toByte()
                pcm[pos + 1] = ((scaled shr 8) and 0xFF).toByte()
            }
        }
    }

    /**
     * Applies cosine fade-out from 1 down to 0 across frames in [pcm].
     * Both channels in stereo frames use the exact same factor.
     * The final frame is guaranteed to be scaled to 0.
     */
    fun applyFadeOut(pcm: ByteArray, offset: Int, validLength: Int, sampleRate: Int, channels: Int) {
        val frameBytes = if (channels >= 2) 4 else 2
        val actualFrames = validLength / frameBytes
        val fadeFrames = minOf(actualFrames, fadeFrameCount(sampleRate))
        if (fadeFrames <= 0) return

        val chCount = minOf(channels, 2)
        for (i in 0 until fadeFrames) {
            val factor = if (i == fadeFrames - 1) 0.0 else {
                0.5 * (1.0 + cos(PI * (i + 1).toDouble() / fadeFrames.toDouble()))
            }
            val frameOffset = offset + i * frameBytes
            for (ch in 0 until chCount) {
                val pos = frameOffset + ch * 2
                val low = pcm[pos].toInt() and 0xFF
                val high = pcm[pos + 1].toInt()
                val sample = (high shl 8) or low
                val scaled = (sample * factor).toInt().coerceIn(-32768, 32767)
                pcm[pos] = (scaled and 0xFF).toByte()
                pcm[pos + 1] = ((scaled shr 8) and 0xFF).toByte()
            }
        }
    }

    /**
     * Creates a frame-aligned, zero-filled PCM byte buffer.
     */
    fun createSilence(sampleRate: Int, channels: Int, durationMs: Int = 40): ByteArray {
        val frameBytes = if (channels >= 2) 4 else 2
        val frames = sampleRate * durationMs / 1000
        return ByteArray(frames * frameBytes)
    }
}
