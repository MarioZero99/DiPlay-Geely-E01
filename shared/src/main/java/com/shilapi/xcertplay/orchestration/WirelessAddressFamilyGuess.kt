package com.shilapi.xcertplay.orchestration

/**
 * What one connect has learned about which address family to publish — and nothing beyond it.
 *
 * The first-TCP watchdog's finding, "the phone never answered on the address we published", is a fact
 * about *this* connect. Kept past the connect it turns into a claim about the head unit, and a claim
 * that is never re-examined is how a network that works on IPv4 comes to be published on IPv6 for
 * every attempt that follows, including the ones after an app restart. So the move lives here, in
 * memory, for exactly as long as the connect does:
 *
 * - **One move per connect.** With both families tried, the attempt after that is the first one again
 *   — a coin being flipped rather than a failure being read.
 * - **Cleared, the next connect starts from the front of the order again** — that is what a driver
 *   asking for a new connection, and a session that actually arrived, both mean.
 *
 * Nothing here is written to storage. The retry ladder holds this one object, which is all the reach a
 * per-connect answer needs; a value that outlives the process is a value nobody re-derives.
 */
class WirelessAddressFamilyGuess {
    private var moved: String? = null

    /** The family to publish this connect, or null to let the backends' own order decide. */
    val family: String?
        get() = moved

    /**
     * The published family was not reachable: [next] is what to publish instead, or null when this
     * connect has already moved once and the caller has to report the failure rather than move again.
     */
    fun onUnusable(next: String): String? {
        if (moved != null) return null
        moved = next
        return next
    }

    /** A session arrived, or a new connect is starting: the guess has nothing left to say. */
    fun clear() {
        moved = null
    }
}
