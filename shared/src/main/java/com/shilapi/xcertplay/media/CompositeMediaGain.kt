package com.shilapi.xcertplay.media

import java.util.concurrent.ConcurrentHashMap

/**
 * Combines external audio focus gain with local navigation ducking.
 *
 * Uses [minOf] to avoid excessive attenuation (e.g. 0.2 focus * 0.2 duck = 0.04),
 * tracks concurrent active navigation streams via a key-set, and ensures focus gain of 0
 * remains strictly 0 (muted).
 */
class CompositeMediaGain(
    val duckFactor: Float = 0.2f
) {
    private val activeNavigationStreams = ConcurrentHashMap.newKeySet<Int>()
    private var externalFocusGain = 1.0f

    @Synchronized
    fun setFocusGain(gain: Float): Float {
        externalFocusGain = gain.coerceIn(0f, 1f)
        return effectiveGain()
    }

    @Synchronized
    fun onStreamStarted(type: Int, isNavigation: Boolean): Float {
        if (isNavigation) activeNavigationStreams.add(type)
        return effectiveGain()
    }

    @Synchronized
    fun onStreamStopped(type: Int): Float {
        activeNavigationStreams.remove(type)
        return effectiveGain()
    }

    fun effectiveGain(): Float {
        if (externalFocusGain == 0f) return 0f
        val duck = if (activeNavigationStreams.isNotEmpty()) duckFactor else 1.0f
        return minOf(externalFocusGain, duck)
    }

    /** One consistent read for a diagnostic line. */
    @Synchronized
    fun state(): MediaGainState =
        MediaGainState(effectiveGain(), externalFocusGain, activeNavigationStreams.size)
}

/** Effective music gain, the external focus gain, and how many navigation streams are open. */
data class MediaGainState(
    val effective: Float,
    val focus: Float,
    val navigationStreams: Int,
)
