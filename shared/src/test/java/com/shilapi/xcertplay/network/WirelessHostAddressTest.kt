package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WirelessHostAddressTest {
    /**
     * The address we hand the iPhone has to be one it can answer on, and a link-local one is not.
     *
     * An access point's link-local route lives in a per-network policy table with no matching `ip rule`,
     * so a socket without a network mark cannot reply to a link-local peer: the framework drops the
     * SYN-ACK and the phone gives up. The IPv4 subnet route lives in the shared `local_network` table, so
     * replies work with no network selection. Upstream measured exactly this (b4db0ad7): the link-local
     * endpoint timed out after 30 s, the IPv4 one completed TCP on the first attempt.
     */
    @Test fun manualApPublishesTheIpv4AddressAndKeepsLinkLocalAsTheFallback() {
        assertEquals(ip("192.168.43.1"), wirelessHostAddress(listOf(ip("fe80::1234"), ip("192.168.43.1")), 7))
    }

    @Test fun linkLocalIsStillUsedWhenTheAccessPointHasNoIpv4() {
        val result = wirelessHostAddress(listOf(ip("fe80::1234")), 7) as Inet6Address
        assertTrue(result.isLinkLocalAddress)
        assertEquals(7, result.scopeId)
    }

    @Test fun replacesScopeFromAnotherInterface() {
        val wrongScope = Inet6Address.getByAddress(null, ip("fe80::1234").address, 3)
        assertEquals(8, (wirelessHostAddress(listOf(wrongScope), 8) as Inet6Address).scopeId)
    }

    @Test fun fallsBackToIpv4WithoutUsableLinkLocal() {
        val ipv4 = ip("192.168.43.1")
        assertEquals(ipv4, wirelessHostAddress(listOf(ip("::1"), ip("2001:db8::1"), ipv4), 7))
        assertEquals(ipv4, wirelessHostAddress(listOf(ip("fe80::1234"), ipv4), 0))
        assertNull(wirelessHostAddress(listOf(ip("0.0.0.0"), ip("127.0.0.1"), ip("224.0.0.251")), 7))
    }

    private fun ip(value: String) = InetAddress.getByName(value)
}
