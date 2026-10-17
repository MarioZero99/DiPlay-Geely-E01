package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The address of a manual access point, in the order [HotspotAddressPolicy] fixes.
 *
 * The scoped link-local address is re-scoped here rather than at the caller because the framework
 * reports it with the scope of whichever interface it was read from.
 */
internal fun wirelessHostAddress(addresses: List<InetAddress>, interfaceIndex: Int): InetAddress? {
    val selected = HotspotAddressPolicy.select(addresses) ?: return null
    if (selected is Inet6Address && interfaceIndex > 0) {
        return Inet6Address.getByAddress(null, selected.address, interfaceIndex)
    }
    return selected
}

/**
 * Every address on a network we merely joined, in the order AirPlay should offer them.
 *
 * The router's subnet address comes first, then the scoped link-local IPv6 the [wirelessHostAddress]
 * path would have chosen. Both are the interfaces' own addresses — the IPv4 address a router hands
 * out over DHCP and the link-local one are both reachable from the phone on the same network, and
 * which of the two it will dial is the phone's choice, not ours.
 */
internal fun existingWifiHostAddresses(
    addresses: List<InetAddress>,
    interfaceIndex: Int,
): List<InetAddress> {
    val ipv4 = addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }
    val ipv6 = if (interfaceIndex > 0) {
        val linkLocal = addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }
        if (linkLocal != null) {
            Inet6Address.getByAddress(null, linkLocal.address, interfaceIndex)
        } else {
            addresses.filterIsInstance<Inet6Address>().firstOrNull {
                !it.isLoopbackAddress && !it.isMulticastAddress && !it.isAnyLocalAddress
            }
        }
    } else {
        addresses.filterIsInstance<Inet6Address>().firstOrNull {
            !it.isLinkLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress && !it.isAnyLocalAddress
        }
    }
    return listOfNotNull(ipv4, ipv6)
}
