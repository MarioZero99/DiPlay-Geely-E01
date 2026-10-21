package com.shilapi.xcertplay.network

/**
 * The timings a wireless bring-up is allowed, in one place.
 *
 * Upstream keeps its failure taxonomy here too. This tree does not: a wireless attempt that fails
 * reports [com.shilapi.xcertplay.orchestration.CarPlayFailureReason], which is what the CarPlay screen
 * and the retry ladder read, and a second classifier beside it would be a second answer to the same
 * question.
 */
internal object WirelessStartupPolicy {
    /** How long a group may take to hand over an address before the attempt is declared stuck. */
    const val HOTSPOT_READY_MILLIS = 15_000L

    /** How often an interface is re-read while waiting for one. */
    const val INTERFACE_POLL_MILLIS = 250L

    /**
     * How long the phone gets to open the AirPlay port after it was told where to find us.
     *
     * Thirty seconds on car-hosted access point: the phone has to associate with the Wi-Fi AP,
     * obtain an IP through DHCP, and complete routing before reaching the port.
     */
    const val FIRST_TCP_MILLIS = 30_000L

    /**
     * On existing Wi-Fi (Same LAN), both phone and head unit are already on the same local network.
     * The phone reaches the port within 1-2 seconds if reachable; five seconds is enough to confirm
     * an unreachable route and flip to the alternate family without keeping the driver waiting.
     */
    const val FIRST_TCP_EXISTING_WIFI_MILLIS = 5_000L
}
