package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Which address of an access-point interface the iPhone is told to dial, and which one to try next.
 *
 * The IPv4 address comes first, and that order is the whole point of this file. An access point's
 * link-local route is installed in a per-network policy table with no matching `ip rule`, so a socket
 * without a network mark cannot reply to a link-local peer: the framework drops the SYN-ACK and the
 * phone times out. The IPv4 subnet route lives in the shared `local_network` table, so replies work
 * with no network selection at all.
 *
 * The scoped link-local address stays as the fallback for firmware whose access point has no IPv4,
 * and [alternateFamilyCandidate] is how a run that published one family offers the other one next.
 */
internal object HotspotAddressPolicy {
    const val FAMILY_IPV4 = "IPv4"
    const val FAMILY_IPV6 = "IPv6"

    fun parseFamily(raw: String?): String? = when (raw) {
        FAMILY_IPV4 -> FAMILY_IPV4
        FAMILY_IPV6 -> FAMILY_IPV6
        else -> null
    }

    fun flipFamily(family: String): String = if (family == FAMILY_IPV6) FAMILY_IPV4 else FAMILY_IPV6

    fun select(addresses: List<InetAddress>): InetAddress? {
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address)) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && linkLocal == null) {
                linkLocal = address
            }
        }
        return linkLocal
    }

    fun select(addresses: List<InetAddress>, preferredFamily: String?): InetAddress? {
        if (preferredFamily != FAMILY_IPV6) {
            return select(addresses)
        }
        for (address in addresses) {
            if (address is Inet6Address && address.isLinkLocalAddress) {
                return address
            }
        }
        return select(addresses)
    }

    fun alternateFamilyCandidate(addresses: List<InetAddress>, published: InetAddress): InetAddress? {
        val publishedIsIpv6 = published is Inet6Address
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address) && publishedIsIpv6) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && !publishedIsIpv6 && linkLocal == null) {
                linkLocal = address
            }
        }
        return if (publishedIsIpv6) null else linkLocal
    }

    private fun usable(address: InetAddress): Boolean =
        !address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isLinkLocalAddress &&
            !address.isMulticastAddress
}
