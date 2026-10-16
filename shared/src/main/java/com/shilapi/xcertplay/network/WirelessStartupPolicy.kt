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
     * Thirty seconds, and it is a measurement rather than a preference: a phone that can reach the
     * published address opens the port within a second or two of CarPlay StartSession, and one that
     * cannot never opens it at all. Waiting longer buys nothing and costs the driver the whole wait.
     * This is the window the address-family flip is decided in, so its magnitude is the point.
     */
    const val FIRST_TCP_MILLIS = 30_000L
}
