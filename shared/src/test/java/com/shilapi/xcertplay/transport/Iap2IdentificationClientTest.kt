package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
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

    @Test
    fun identificationInformationRemovesStopMessagesAndExternalAccessorySessions() {
        val config = Iap2IdentificationConfig(
            name = "xcertplay",
            modelIdentifier = "xcertplay",
            manufacturer = "xcertplay",
            serialNumber = "xcertplay",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
        )

        val frame = Iap2IdentificationClient.identificationInformation(config)
        val parameters = Iap2ParameterList.parse(frame.payload).asList()

        val sentMessages = u16Values(parameters.single { it.id == 6 }.payload)
        val receivedMessages = u16Values(parameters.single { it.id == 7 }.payload)

        // P7: Verify removed Stop messages
        assertFalse(0x5002 in sentMessages) // StopNowPlayingUpdates
        assertFalse(0x5203 in sentMessages) // StopRouteGuidanceUpdates
        assertFalse(0xae02 in sentMessages) // StopPowerUpdates
        assertFalse(0x4159 in sentMessages) // StopCommunicationsUpdates
        assertFalse(0x4156 in sentMessages) // StopCallStateUpdates

        // P10: the two EA session messages are gone. Named as the catalog names them
        // (Iap2Endpoint.externalAccessory_openEASession / closeEASession) — they are not Wi-Fi
        // credential messages, and a reader who takes them for those will look for the wrong thing
        // to re-add.
        assertFalse(0xea00 in receivedMessages) // externalAccessory_openEASession
        assertFalse(0xea01 in receivedMessages) // externalAccessory_closeEASession

        // Ensure essential message IDs are still present
        assertTrue(0x4301 in sentMessages) // CarPlayStartSession
        assertTrue(0xaa01 in sentMessages) // AuthenticationCertificate
        assertTrue(0x4300 in receivedMessages) // CarPlayAvailability
        assertTrue(0xaa00 in receivedMessages) // RequestAuthenticationCertificate
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
    ) = Iap2IdentificationConfig(
        name = "xcertplay",
        modelIdentifier = "xcertplay",
        manufacturer = "xcertplay",
        serialNumber = "xcertplay",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 3,
        locationInformationEnabled = locationInformationEnabled,
        vehicleStatusEnabled = vehicleStatusEnabled,
    )

    private fun u16Values(bytes: ByteArray): List<Int> =
        List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or
                (bytes[index * 2 + 1].toInt() and 0xff)
        }
}
