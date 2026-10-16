package com.shilapi.xcertplay.network

import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Waiting for the address family the last run learned it wanted, and choosing the next one to try.
 *
 * An access point is reported ready before its addresses are all configured, so the family a run has
 * decided to publish may simply not be there yet — [awaitCandidates] is the bounded wait for it. And
 * when a run publishes an address the phone never answers on, the family it should publish instead is
 * [nextFamily]'s answer, which the caller remembers for the next attempt.
 */
internal class HotspotAddressRecovery(
    private val sample: () -> List<InetAddress>,
    private val cancelled: () -> Boolean,
    private val pause: (Long) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    fun awaitCandidates(
        initial: List<InetAddress>,
        preferredFamily: String?,
        timeoutMillis: Long = WirelessStartupPolicy.HOTSPOT_READY_MILLIS,
    ): List<InetAddress> {
        require(timeoutMillis >= 0)
        val started = nowMillis()
        val deadline = started + timeoutMillis
        var candidates = initial
        if (preferredFamily == HotspotAddressPolicy.FAMILY_IPV6 && !candidates.any(::scopedLinkLocal)) {
            log("wireless address waiting preference=IPv6 timeoutMs=$timeoutMillis")
        }
        while (true) {
            checkActive()
            if (preferredFamily != HotspotAddressPolicy.FAMILY_IPV6) return candidates
            if (candidates.any(::scopedLinkLocal)) {
                log("wireless address preference=IPv6 ready elapsedMs=${nowMillis() - started}")
                return candidates.filter { it !is Inet6Address || !it.isLinkLocalAddress || scopedLinkLocal(it) }
            }
            val remaining = deadline - nowMillis()
            if (remaining <= 0) {
                log("wireless address preference=IPv6 unavailable elapsedMs=${nowMillis() - started}; using available family")
                return candidates
            }
            pause(minOf(WirelessStartupPolicy.INTERFACE_POLL_MILLIS, remaining))
            checkActive()
            candidates = sample()
            checkActive()
        }
    }

    /** Re-sample at timeout; a missing alternate at group creation is not a single-family AP. */
    fun nextFamily(published: InetAddress): String? {
        if (cancelled()) return null
        val candidates = sample().filter { it !is Inet6Address || scopedLinkLocal(it) }
        if (cancelled()) return null
        if (HotspotAddressPolicy.alternateFamilyCandidate(candidates, published) == null) return null
        return HotspotAddressPolicy.flipFamily(
            if (published is Inet6Address) HotspotAddressPolicy.FAMILY_IPV6 else HotspotAddressPolicy.FAMILY_IPV4,
        )
    }

    private fun checkActive() {
        if (cancelled()) throw InterruptedIOException("Hotspot address recovery cancelled")
    }

    private fun scopedLinkLocal(address: InetAddress): Boolean =
        address is Inet6Address && address.isLinkLocalAddress && address.scopeId != 0
}
