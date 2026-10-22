package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.WirelessHotspotMode

/**
 * In-memory tracking of the address family to publish for wireless CarPlay connects.
 *
 * Persisted in process memory across activity recreations and connection attempts so that
 * an established or flipped family preference is not repeatedly forgotten and re-discovered.
 * Preferences are isolated per [WirelessHotspotMode] so that conclusions reached on one backend
 * (such as an existing Wi-Fi LAN preferring IPv6) do not bleed into others (such as manual APs).
 */
class WirelessAddressFamilyGuess {
    private val lock = Any()
    private val rememberedFamilies = mutableMapOf<WirelessHotspotMode, String>()
    private var movedInCurrentConnect: Boolean = false

    /** The family to publish for [mode], or null to let the mode's own default decide. */
    fun family(mode: WirelessHotspotMode = WirelessHotspotMode.EXISTING_WIFI): String? = synchronized(lock) {
        rememberedFamilies[mode]
    }

    /**
     * The published family was not reachable: [next] is what to publish instead, or null when this
     * connect has already moved once and the caller has to report the failure rather than move again.
     */
    fun onUnusable(next: String, mode: WirelessHotspotMode = WirelessHotspotMode.EXISTING_WIFI): String? = synchronized(lock) {
        if (movedInCurrentConnect) return null
        movedInCurrentConnect = true
        rememberedFamilies[mode] = next
        next
    }

    /** Records that an AirPlay session was successfully established using [family] on [mode]. */
    fun onSessionActive(family: String, mode: WirelessHotspotMode = WirelessHotspotMode.EXISTING_WIFI) = synchronized(lock) {
        rememberedFamilies[mode] = family
        movedInCurrentConnect = false
    }

    /** Allows a new attempt (e.g. manual retry) to move families again if needed, without forgetting preferences. */
    fun onNewAttempt() = synchronized(lock) {
        movedInCurrentConnect = false
    }

    /** Clears the in-memory state. */
    fun clear() = synchronized(lock) {
        rememberedFamilies.clear()
        movedInCurrentConnect = false
    }
}
