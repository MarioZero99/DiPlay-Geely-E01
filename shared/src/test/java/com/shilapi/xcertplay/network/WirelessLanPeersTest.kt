package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The one fact `tcpAccepted=0` cannot carry: whether anything other than the router was on the network.
 *
 * The table these tests feed in is the kernel's, and the rules under test are the ones that turn it
 * into a count — what a row has to look like to be a host, what the router's own row is doing there,
 * and what the baseline a session pins is for. Whether a real head unit's kernel fills that table the
 * way an IPv4 neighbour table is supposed to is the one thing only the car can say.
 */
class WirelessLanPeersTest {
    @Test
    fun aCompleteEntryOnOurInterfaceOtherThanTheRouterIsCounted() {
        val table = table(row("192.168.1.7"))

        assertEquals(setOf("192.168.1.7"), parseArpPeers(table, INTERFACE, GATEWAY))
    }

    @Test
    fun anEntryOnAnotherInterfaceIsNotCounted() {
        val table = table(row("192.168.1.7", device = "wlan1"))

        assertEquals(emptySet<String>(), parseArpPeers(table, INTERFACE, GATEWAY))
    }

    @Test
    fun anEntryWhoseFlagsLackTheCompleteBitIsNotCounted() {
        val table = table(row("192.168.1.7", flags = "0x0"))

        assertEquals(emptySet<String>(), parseArpPeers(table, INTERFACE, GATEWAY))
    }

    @Test
    fun theRoutersOwnEntryIsNotCounted() {
        val table = table(row(GATEWAY), row("192.168.1.7"))

        assertEquals(setOf("192.168.1.7"), parseArpPeers(table, INTERFACE, GATEWAY))
    }

    @Test
    fun anAllZeroHardwareAddressIsNotCounted() {
        val table = table(row("192.168.1.7", hardware = "00:00:00:00:00:00"))

        assertEquals(emptySet<String>(), parseArpPeers(table, INTERFACE, GATEWAY))
    }

    @Test
    fun aHeaderLineABlankLineAndATruncatedRowAreSkippedWithoutThrowing() {
        // A truncated row is what a row cut off by a short read looks like, and it is not a host.
        val table = HEADER + "\n" + "\n" + "192.168.1.9 0x1 0x2 aa:bb\n" + row("192.168.1.7")

        assertEquals(setOf("192.168.1.7"), parseArpPeers(table, INTERFACE, GATEWAY))
    }

    @Test
    fun lanPeersNewCountsOnlyAddressesThatWereNotThereWhenPinRan() {
        var table = table(row("192.168.1.7"))
        val peers = WirelessLanPeers(INTERFACE, GATEWAY) { _, _ -> table }
        peers.pin()

        table = table(row("192.168.1.7"), row("192.168.1.8"))

        assertEquals("lanPeers=2 lanPeersNew=1", peers.snapshot())
    }

    @Test
    fun aReadThatThrowsReportsAnUnavailableFieldAndBoundsWhatItReads() {
        val peers = WirelessLanPeers(INTERFACE, GATEWAY) { path, limit ->
            assertEquals("/proc/net/arp", path)
            assertEquals(16 * 1024, limit)
            throw SecurityException("the network's own addresses must never reach the report")
        }
        peers.pin()

        val result = peers.snapshot()

        assertEquals("lanPeers=unavailable failureClass=SecurityException", result)
        assertFalse(result.contains("addresses") || result.contains("network"))
    }

    @Test
    fun anEmptyTableIsReportedAsNoPeersAtAll() {
        val peers = WirelessLanPeers(INTERFACE, GATEWAY) { _, _ -> HEADER + "\n" }
        peers.pin()

        assertEquals("lanPeers=0 lanPeersNew=0", peers.snapshot())
    }

    @Test
    fun peersNewStaysUnknownUntilABaselineHasBeenPinned() {
        val peers = WirelessLanPeers(INTERFACE, GATEWAY) { _, _ -> table(row("192.168.1.7")) }

        assertEquals("lanPeers=1 lanPeersNew=unknown", peers.snapshot())
    }

    private fun table(vararg rows: String): String = (listOf(HEADER) + rows).joinToString("\n")

    private fun row(
        address: String,
        flags: String = "0x2",
        hardware: String = "aa:bb:cc:dd:ee:ff",
        device: String = INTERFACE,
    ): String = "$address 0x1 $flags $hardware * $device"

    private companion object {
        const val INTERFACE = "wlan0"
        const val GATEWAY = "192.168.1.1"
        const val HEADER = "IP address       HW type     Flags       HW address            Mask     Device"
    }
}
