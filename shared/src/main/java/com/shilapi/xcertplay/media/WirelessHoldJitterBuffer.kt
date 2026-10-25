package com.shilapi.xcertplay.media

import kotlin.math.ceil

/**
 * Manages packet-count gating for wireless media audio streams.
 *
 * For wireless media streams:
 * - Cold start / track reset: gates until ~1000ms of AAC packets accumulate (47 packets at 48kHz, 44 at 44.1kHz).
 * - After underrun: escalates gate until ~2200ms of AAC packets accumulate (104 packets at 48kHz, 95 at 44.1kHz).
 *
 * For wired connections or non-media audio types (telephony, prompts):
 * - Always gates to 0 packets (immediate draining without delay).
 */
class WirelessHoldJitterBuffer(
    val isWireless: Boolean,
    val audioType: String,
    val sampleRate: Int = 48000,
    val initialHoldMs: Int = 1000,
    val escalatedHoldMs: Int = 2200,
) {
    var hasUnderrun: Boolean = false
        private set

    /** Target packets to hold before opening the drain gate. */
    fun targetHoldPackets(): Int {
        if (!isWireless || audioType != "media") return 0
        val ms = if (hasUnderrun) escalatedHoldMs else initialHoldMs
        val packetsPerSec = sampleRate.toDouble() / 1024.0
        return ceil(ms * packetsPerSec / 1000.0).toInt()
    }

    /** Called when an underrun / starvation brake occurs on this stream. */
    fun onUnderrunDetected() {
        if (isWireless && audioType == "media") {
            hasUnderrun = true
        }
    }

    /** Called when the audio renderer is completely released / destroyed. */
    fun onStreamDestroyed() {
        hasUnderrun = false
    }

    /** True if the compressed queue has accumulated enough packets to open the gate. */
    fun isReadyToDrain(currentQueuePackets: Int): Boolean =
        currentQueuePackets >= targetHoldPackets()
}
