package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the addresses on a network we merely joined the session advertises.
 *
 * The phone dials what it is told, so the order is the answer: the address the router handed out over
 * DHCP comes first, because that is the one anything else on the network can reach without knowing
 * the interface. The link-local IPv6 address is kept as well — it is reachable from the same network
 * — but as the second choice rather than the first, which is the opposite of what the access-point
 * path does and the reason this is a separate function rather than a flag on that one.
 */
class ExistingWifiHostAddressesTest {
    private val interfaceIndex = 3

    @Test
    fun theDhcpAddressComesFirstAndTheScopedLinkLocalSecond() {
        val addresses = existingWifiHostAddresses(
            listOf(address("fe80::1234"), address("192.0.2.10")),
            interfaceIndex,
        )

        // The scope is part of the text form; the address itself is carried by the Inet6Address.
        assertEquals(
            listOf("192.0.2.10", "fe80:0:0:0:0:0:0:1234"),
            addresses.map { it.hostAddress!!.substringBefore('%') },
        )
        val secondary = addresses.last()
        assertTrue(secondary is Inet6Address && secondary.isLinkLocalAddress)
        assertEquals(interfaceIndex, (secondary as Inet6Address).scopeId)
    }

    @Test
    fun aNetworkWithOnlyALinkLocalAddressStillGivesOneScoped() {
        val addresses = existingWifiHostAddresses(listOf(address("fe80::1234")), interfaceIndex)

        assertEquals(1, addresses.size)
        assertEquals(interfaceIndex, (addresses.single() as Inet6Address).scopeId)
    }

    @Test
    fun aNetworkWithOnlyAnAddressTheRouterHandedOutGivesThatOne() {
        val addresses = existingWifiHostAddresses(listOf(address("192.0.2.10")), interfaceIndex)

        assertEquals(listOf("192.0.2.10"), addresses.map { it.hostAddress!! })
    }

    @Test
    fun addressesThatAreNotThisInterfaceOnThisNetworkAreNotOffered() {
        val addresses = existingWifiHostAddresses(
            listOf(
                address("127.0.0.1"),
                address("0.0.0.0"),
                address("224.0.0.1"),
                address("169.254.1.1"),
                address("192.0.2.10"),
            ),
            interfaceIndex,
        )

        assertEquals(listOf("192.0.2.10"), addresses.map { it.hostAddress!! })
    }

    @Test
    fun withoutAnInterfaceIndexTheLinkLocalAddressCannotBeScopedAndIsDropped() {
        assertEquals(
            emptyList<String>(),
            existingWifiHostAddresses(listOf(address("fe80::1234")), 0).map { it.hostAddress!! },
        )
    }

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)
}
