package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Test

class CarPlayBonjourTest {
    @Test fun discoveryDiagnosticsKeepOutcomeWithoutPhoneIdentifiers() {
        val endpoint = CarPlayBonjourEndpoint("Private phone", "192.168.43.25", 7000, "AA:BB:CC:DD:EE:FF")
        assertEquals("control resolved family=IPv4 port=7000", CarPlayBonjourEvent.Resolved(endpoint).diagnosticSummary())
        assertEquals("control probe attempts=2 status=200 error=none",
            CarPlayBonjourEvent.Probed(endpoint, 2, "HTTP/1.1 200 Private phone", null).diagnosticSummary())
        val failed = CarPlayBonjourEvent.Probed(endpoint, 3, null, java.io.IOException("Private phone 192.168.43.25"))
            .diagnosticSummary()
        assertEquals("control probe attempts=3 status=none error=IOException", failed)
        assertFalse(failed.contains("Private phone"))
    }
    private val config = AirPlayConfig(
        deviceName = "xcertplay",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        model = "LIVI",
    )
    private val identity = AirPlayIdentity(
        privateKey = ByteArray(32),
        publicKey = byteArrayOf(0x01, 0x23, 0xab.toByte()),
        pairingId = "pairing-1",
    )

    /**
     * The Bonjour record and `/info` have to describe the same receiver.
     *
     * The phone reads the record before it connects and the plist after, so a record advertising
     * different feature bits is a receiver contradicting itself. Both come from
     * [AirPlayInfoPlist.features] for that reason; this pins the two surfaces against each other
     * rather than against a literal, so a hard-coded value on either side fails here.
     */
    @Test
    fun airPlayTxtRecordsAdvertiseTheSameFeaturesAsInfo() {
        assertEquals(
            linkedMapOf(
                "deviceid" to "02:00:00:00:00:02",
                "features" to "0x5653aee2,0x61",
                "flags" to "0x4",
                "model" to "LIVI",
                "srcvers" to "366.0",
                "protovers" to "1.1",
                "pi" to "pairing-1",
                "pk" to "0123ab",
            ),
            CarPlayBonjourProtocol.airPlayTxtRecords(config, identity),
        )
        assertEquals(
            CarPlayBonjourProtocol.featuresTxt(AirPlayInfoPlist.build(config)["features"] as Long),
            CarPlayBonjourProtocol.airPlayTxtRecords(config, identity)["features"],
        )
    }

    @Test
    fun featureBitsAreSpelledLowThenHigh() {
        assertEquals("0xffffffff", CarPlayBonjourProtocol.featuresTxt(0xffffffffL))
        assertEquals("0x1,0x1", CarPlayBonjourProtocol.featuresTxt(0x1_00000001L))
    }

    /**
     * The address the record is published under arrives scoped, because that is the only form JmDNS can
     * join a link-local multicast group with. Parsing it back to an unscoped address would leave the
     * responder advertising from an interface it cannot name.
     */
    @Test
    fun aScopedLinkLocalAdvertisedHostKeepsItsInterface() {
        val scoped = advertisedHostAddress("fe80::1234%3")
        assertEquals(3, (scoped as Inet6Address).scopeId)
        // Not the exact spelling: how many groups are written out is the platform's business.
        assertTrue(scoped.hostAddress!!.endsWith("%3"))
        assertEquals(InetAddress.getByName("192.168.49.1"), advertisedHostAddress("192.168.49.1"))
        // A global address is not something the phone can dial back on the AP.
        assertThrows(IllegalArgumentException::class.java) { advertisedHostAddress("2001:db8::1") }
    }

    @Test
    fun connectProbeRequestMatchesExactRequestLineAndHeaders() {
        assertEquals(
            "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
                "Host: [fe80::1]:7000\r\n" +
                "User-Agent: AirPlay/366.0\r\n" +
                "AirPlay-Receiver-Device-ID: 020000000002\r\n" +
                "Connection: close\r\n" +
                "\r\n",
            CarPlayBonjourProtocol.connectProbeRequest(
                host = "fe80::1%wlan0",
                port = 7000,
                sourceVersion = "366.0",
                deviceId = "02:00:00:00:00:02",
            ),
        )
    }
}
