package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class Iap2IdentificationClientTest {

    /**
     * v0.4.1's bytes, which this car accepted on 2026-10-14 (wired) and 2026-10-16 (wireless).
     *
     * Pinned as a whole list rather than by absence: the 0.4.2 regression came from deleting entries
     * one by one while parameter 10 kept naming the protocol those entries served, and a test that
     * only checked what was gone could not see it.
     */
    @Test
    fun verifiedProfileCarriesTheV041MessageSet() {
        val parameters = parametersOf(config())

        assertEquals(
            listOf(
                0xaa01, // AuthenticationCertificate
                0xaa03, // AuthenticationResponse
                0x5000, 0x5002, // NowPlaying start/stop
                0x5200, 0x5203, // RouteGuidance start/stop
                0xae00, 0xae02, // Power start/stop
                0x4157, 0x4159, // Communications start/stop
                0x4154, 0x4156, // CallState start/stop
                0xae03, // PowerSourceUpdate
                0x4301, // CarPlayStartSession
            ),
            u16Values(parameters.single { it.id == 6 }.payload),
        )
        assertEquals(
            listOf(
                0xaa00, 0xaa02, 0xaa04, 0xaa05,
                0xea00, 0xea01, // externalAccessory_openEASession / closeEASession
                0x5001, 0x5201, 0x5202, 0xae01, 0x4158, 0x4155, 0x4300,
            ),
            u16Values(parameters.single { it.id == 7 }.payload),
        )
        assertNotNull(
            "v0.4.1 names its EA protocol in parameter 10",
            parameters.firstOrNull { it.id == 10 },
        )
    }

    /**
     * The shape 0.4.2 was reaching for, finished: no EA plane anywhere.
     *
     * Dropping 0xea00/0xea01 from parameter 7 while parameter 10 still named the protocol is what
     * made the phone answer 0x1d03 with parameters 0x0006 and 0x000a; this profile removes both
     * halves, and the constructor refuses to build it any other way.
     */
    @Test
    fun compliantProfileDropsTheEaPlaneOnBothSides() {
        val parameters = parametersOf(config(profile = Iap2IdentificationProfile.PROTOCOL_COMPLIANT))
        val sent = u16Values(parameters.single { it.id == 6 }.payload)
        val received = u16Values(parameters.single { it.id == 7 }.payload)

        assertNull("no protocol in parameter 10", parameters.firstOrNull { it.id == 10 })
        assertFalse(0xea00 in received)
        assertFalse(0xea01 in received)

        // The subscription pairs stay: a Start with no Stop is a subscription that never closes.
        assertTrue(0x5002 in sent)
        assertTrue(0x5203 in sent)
        assertTrue(0xae02 in sent)
        assertTrue(0x4159 in sent)
        assertTrue(0x4156 in sent)

        // CarPlay itself is not affected by dropping the EA plane.
        assertTrue(0x4301 in sent)
        assertTrue(0x4300 in received)
    }

    /** Whatever the profile, parameters 7 and 10 agree on whether the EA plane exists. */
    @Test
    fun everyProfileAgreesOnTheEaPlane() {
        for (profile in Iap2IdentificationProfile.values()) {
            for (wireless in listOf(null, Iap2WirelessIdentification("00:11:22:33:44:55", "DiPlay"))) {
                val parameters = parametersOf(config(profile = profile, wireless = wireless))
                val received = u16Values(parameters.single { it.id == 7 }.payload)
                val declaresProtocol = parameters.any { it.id == 10 }
                assertEquals(
                    "$profile wireless=${wireless != null}: parameter 10 and the EA session pair disagree",
                    declaresProtocol,
                    0xea00 in received,
                )
                assertEquals(
                    "$profile wireless=${wireless != null}: EA session messages are not a pair",
                    0xea00 in received,
                    0xea01 in received,
                )
            }
        }
    }

    /**
     * The link layer and the identification layer answer the same question, so they must agree.
     *
     * They are not the same mechanism: 0x1d03 is about parameters 6/7/10, and the synchronization
     * frame's session list is negotiated before identification ever starts. But both are this
     * accessory saying whether it offers the EA plane, and 0.4.2 said yes in one and no in the
     * other. This test is what makes that a failure instead of a field diagnosis.
     */
    @Test
    fun theLinkSessionAnswerMatchesTheIdentificationAnswer() {
        for (profile in Iap2IdentificationProfile.values()) {
            val parameters = parametersOf(config(profile = profile))
            assertEquals(
                "$profile: the link and parameters 6/7/10 answer differently",
                parameters.any { it.id == 10 },
                profile.declaresOptionalLinkSessions,
            )
        }
    }

    @Test
    fun messageSetRejectsAnEaProtocolWithoutSessionMessages() {
        assertThrows(IllegalArgumentException::class.java) {
            Iap2IdentificationMessages(
                sentByAccessory = intArrayOf(0x4301),
                receivedFromPhone = intArrayOf(0x4300),
                externalAccessoryProtocols = listOf("com.shilapi.xcertplay"),
            )
        }
    }

    @Test
    fun messageSetRejectsAnUnpairedSubscription() {
        assertThrows(IllegalArgumentException::class.java) {
            Iap2IdentificationMessages(
                sentByAccessory = intArrayOf(0x5000, 0x4301),
                receivedFromPhone = intArrayOf(0x4300),
                externalAccessoryProtocols = emptyList(),
            )
        }
    }

    @Test
    fun identifySucceedsWhenImmediatelyAccepted() {
        val incomingFrames = ArrayDeque<Iap2Frame>(
            listOf(
                Iap2Frame(Iap2IdentificationClient.START_IDENTIFICATION, byteArrayOf()),
                Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_ACCEPTED, byteArrayOf()),
            ),
        )
        val sentFrames = mutableListOf<Iap2Frame>()
        val fakeSession = object : Iap2Session(null) {
            override fun awaitReady(timeoutMillis: Long): Boolean = true
            override fun recv(timeoutMillis: Long): Iap2Frame? = incomingFrames.pollFirst()
            override fun send(frame: Iap2Frame, timeoutMillis: Long) {
                sentFrames.add(frame)
            }
        }

        val client = Iap2IdentificationClient(fakeSession)
        val config = testConfig()
        client.identify(config)

        assertEquals(1, sentFrames.size)
        assertEquals(Iap2IdentificationClient.IDENTIFICATION_INFORMATION, sentFrames[0].messageId)
    }

    @Test
    fun identifyDegradesAndRetriesOn0x1d03WhenExtensionsEnabled() {
        val incomingFrames = ArrayDeque<Iap2Frame>(
            listOf(
                Iap2Frame(Iap2IdentificationClient.START_IDENTIFICATION, byteArrayOf()),
                Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_REJECTED, byteArrayOf()),
                Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_ACCEPTED, byteArrayOf()),
            ),
        )
        val sentFrames = mutableListOf<Iap2Frame>()
        val fakeSession = object : Iap2Session(null) {
            override fun awaitReady(timeoutMillis: Long): Boolean = true
            override fun recv(timeoutMillis: Long): Iap2Frame? = incomingFrames.pollFirst()
            override fun send(frame: Iap2Frame, timeoutMillis: Long) {
                sentFrames.add(frame)
            }
        }

        val client = Iap2IdentificationClient(fakeSession)
        val configWithExtensions = testConfig(
            locationInformationEnabled = true,
            vehicleStatusEnabled = true,
        )
        client.identify(configWithExtensions)

        assertEquals(2, sentFrames.size)

        // First frame had EV and Location enabled
        val firstParams = Iap2ParameterList.parse(sentFrames[0].payload).asList()
        val firstSent = u16Values(firstParams.single { it.id == 6 }.payload)
        assertTrue(Iap2LocationMessages.LOCATION_INFORMATION in firstSent)
        assertTrue(Iap2VehicleStatus.VEHICLE_STATUS_UPDATE in firstSent)
        assertNotNull(firstParams.firstOrNull { it.id == 20 }) // VehicleInformation
        assertNotNull(firstParams.firstOrNull { it.id == 22 }) // LocationInformationComponent

        // Second frame had EV and Location stripped
        val secondParams = Iap2ParameterList.parse(sentFrames[1].payload).asList()
        val secondSent = u16Values(secondParams.single { it.id == 6 }.payload)
        assertFalse(Iap2LocationMessages.LOCATION_INFORMATION in secondSent)
        assertFalse(Iap2VehicleStatus.VEHICLE_STATUS_UPDATE in secondSent)
        assertNull(secondParams.firstOrNull { it.id == 20 })
        assertNull(secondParams.firstOrNull { it.id == 22 })
    }

    @Test
    fun identifyFailsWhenRejectedWithoutExtensions() {
        val incomingFrames = ArrayDeque<Iap2Frame>(
            listOf(
                Iap2Frame(Iap2IdentificationClient.START_IDENTIFICATION, byteArrayOf()),
                Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_REJECTED, byteArrayOf()),
            ),
        )
        val sentFrames = mutableListOf<Iap2Frame>()
        val fakeSession = object : Iap2Session(null) {
            override fun awaitReady(timeoutMillis: Long): Boolean = true
            override fun recv(timeoutMillis: Long): Iap2Frame? = incomingFrames.pollFirst()
            override fun send(frame: Iap2Frame, timeoutMillis: Long) {
                sentFrames.add(frame)
            }
        }

        val client = Iap2IdentificationClient(fakeSession)
        val baseConfig = testConfig(
            locationInformationEnabled = false,
            vehicleStatusEnabled = false,
        )

        assertThrows(Iap2IdentificationException.Rejected::class.java) {
            client.identify(baseConfig)
        }
        assertEquals(1, sentFrames.size)
    }

    private fun testConfig(
        locationInformationEnabled: Boolean = false,
        vehicleStatusEnabled: Boolean = false,
        profile: Iap2IdentificationProfile = Iap2IdentificationProfile.VERIFIED_V041,
    ) = config(
        locationInformationEnabled = locationInformationEnabled,
        vehicleStatusEnabled = vehicleStatusEnabled,
        profile = profile,
    )

    private fun config(
        locationInformationEnabled: Boolean = false,
        vehicleStatusEnabled: Boolean = false,
        profile: Iap2IdentificationProfile = Iap2IdentificationProfile.VERIFIED_V041,
        wireless: Iap2WirelessIdentification? = null,
    ) = Iap2IdentificationConfig(
        name = "xcertplay",
        modelIdentifier = "xcertplay",
        manufacturer = "xcertplay",
        serialNumber = "xcertplay",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 3,
        wireless = wireless,
        locationInformationEnabled = locationInformationEnabled,
        vehicleStatusEnabled = vehicleStatusEnabled,
        profile = profile,
    )

    private fun parametersOf(config: Iap2IdentificationConfig): List<Iap2Parameter> =
        Iap2ParameterList.parse(Iap2IdentificationClient.identificationInformation(config).payload).asList()

    private fun u16Values(bytes: ByteArray): List<Int> =
        List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or
                (bytes[index * 2 + 1].toInt() and 0xff)
        }
}
