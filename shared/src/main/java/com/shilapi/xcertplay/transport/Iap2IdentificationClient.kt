package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.Iap2VehicleStatus.electricVehicleComponents
import java.io.IOException
import kotlin.math.min

/** The wireless transport identity advertised on the Bluetooth identification session. */
class Iap2WirelessIdentification(
    val bluetoothMac: String,
    val ssid: String,
) {
    private val macBytes: ByteArray

    init {
        require(MAC_ADDRESS.matches(bluetoothMac)) {
            "bluetoothMac must be six colon-separated hexadecimal bytes"
        }
        require(ssid.isNotBlank()) { "Wireless SSID must not be blank" }
        require('\u0000' !in ssid) { "Wireless SSID must not contain U+0000" }
        macBytes = ByteArray(6) { index ->
            bluetoothMac.substring(index * 3, index * 3 + 2).toInt(16).toByte()
        }
    }

    internal fun bluetoothMacBytes(): ByteArray = macBytes.copyOf()

    private companion object {
        private val MAC_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}

/**
 * Which identification message set parameters 6, 7 and 10 carry.
 *
 * The two sets differ only in whether this accessory offers the external-accessory plane. Both are
 * internally consistent, and that is the point: the car rejects the whole exchange with 0x1d03 when
 * parameter 10 names an EA protocol while parameter 7 does not carry the EA session messages that
 * open it. Keeping the two sets side by side lets the car pick the answer without a rebuild.
 */
enum class Iap2IdentificationProfile(
    /**
     * Whether the link's synchronization frame also declares the EA and file-transfer sessions
     * beside session 10.
     *
     * This is the link layer, not the identification layer: 0x1d03 is about parameters 6/7/10 and
     * says nothing about what the link negotiated. It is kept beside the profile because the two
     * describe the same decision — whether this accessory offers the EA plane — and a build that
     * answers yes in one place and no in the other is answering a question twice.
     */
    val declaresOptionalLinkSessions: Boolean,
) {
    /**
     * The bytes v0.4.1 shipped, accepted by this car on 2026-10-14 (wired) and 2026-10-16
     * (wireless). Names the accessory's own EA protocol and declares the matching EA session
     * messages; v0.4.1's wireless link also declared the optional sessions.
     *
     * Default: a profile the car has not accepted yet must not be what an unattended head unit
     * boots into.
     */
    VERIFIED_V041(declaresOptionalLinkSessions = true),

    /**
     * v0.4.1's subscription pairs without the EA plane: parameter 10 is absent, so parameter 7 must
     * not carry 0xea00/0xea01 either, and the link declares control session only. This is the shape
     * for an accessory that has no EA session handler — the honest one, but not yet proven on this
     * car.
     */
    PROTOCOL_COMPLIANT(declaresOptionalLinkSessions = false),
}

/**
 * Parameters 6, 7 and 10 as one value, so the three can be checked against each other.
 *
 * The rules are the ones the phone enforces when it answers 0x1d03:
 * - 0xea00/0xea01 are declared together or not at all.
 * - Parameter 10 declares an EA protocol exactly when parameter 7 declares those session messages.
 * - Each subscription's Start and Stop messages are declared together: a Start with no Stop is a
 *   subscription the accessory can open and never close.
 *
 * A profile that breaks any of them cannot be constructed, which is what turns a silent protocol
 * mismatch into a build-and-test failure.
 */
internal class Iap2IdentificationMessages(
    val sentByAccessory: IntArray,
    val receivedFromPhone: IntArray,
    val externalAccessoryProtocols: List<String>,
) {
    init {
        require(externalAccessoryProtocols.size <= 1) {
            "This profile declares at most one external-accessory protocol"
        }
        val opensExternalAccessorySession = EA_OPEN_SESSION in receivedFromPhone
        val closesExternalAccessorySession = EA_CLOSE_SESSION in receivedFromPhone
        require(opensExternalAccessorySession == closesExternalAccessorySession) {
            "EA session messages must be declared as a pair: " +
                "0xea00=$opensExternalAccessorySession, 0xea01=$closesExternalAccessorySession"
        }
        require(externalAccessoryProtocols.isNotEmpty() == opensExternalAccessorySession) {
            "Parameters 7 and 10 disagree: protocols=$externalAccessoryProtocols, " +
                "EA session messages declared=$opensExternalAccessorySession. " +
                "An EA protocol without its session messages is what 0x1d03 rejects."
        }
        for ((start, stop) in SUBSCRIPTION_PAIRS) {
            require((start in sentByAccessory) == (stop in sentByAccessory)) {
                "Subscription 0x${start.toString(16)}/0x${stop.toString(16)} must be declared as a pair"
            }
        }
        require(
            (START_LOCATION_INFORMATION in receivedFromPhone) ==
                (STOP_LOCATION_INFORMATION in receivedFromPhone),
        ) {
            "Location subscription 0xfffa/0xfffc must be declared as a pair"
        }
    }

    companion object {
        private const val EA_OPEN_SESSION = 0xea00
        private const val EA_CLOSE_SESSION = 0xea01
        private const val START_LOCATION_INFORMATION = 0xfffa
        private const val STOP_LOCATION_INFORMATION = 0xfffc

        private val SUBSCRIPTION_PAIRS = listOf(
            0x5000 to 0x5002, // NowPlaying
            0x5200 to 0x5203, // RouteGuidance
            0xae00 to 0xae02, // Power
            0x4157 to 0x4159, // Communications
            0x4154 to 0x4156, // CallState
        )

        /** What v0.4.1 sent, byte for byte. */
        private val V041_SENT = intArrayOf(
            0xaa01, // AuthenticationCertificate
            0xaa03, // AuthenticationResponse
            0x5000, // StartNowPlayingUpdates
            0x5002, // StopNowPlayingUpdates
            0x5200, // StartRouteGuidanceUpdates
            0x5203, // StopRouteGuidanceUpdates
            0xae00, // StartPowerUpdates
            0xae02, // StopPowerUpdates
            0x4157, // StartCommunicationsUpdates
            0x4159, // StopCommunicationsUpdates
            0x4154, // StartCallStateUpdates
            0x4156, // StopCallStateUpdates
            0xae03, // PowerSourceUpdate
            0x4301, // CarPlayStartSession
        )

        /** v0.4.1's phone-side messages, including the EA session pair. */
        private val V041_RECEIVED = intArrayOf(
            0xaa00, // RequestAuthenticationCertificate
            0xaa02, // RequestAuthenticationChallengeResponse
            0xaa04, // AuthenticationFailed
            0xaa05, // AuthenticationSucceeded
            0xea00, // StartExternalAccessoryProtocolSession
            0xea01, // StopExternalAccessoryProtocolSession
            0x5001, // NowPlayingUpdate
            0x5201, // RouteGuidanceUpdate
            0x5202, // RouteGuidanceManeuverUpdate
            0xae01, // PowerUpdate
            0x4158, // CommunicationsUpdate
            0x4155, // CallStateUpdate
            0x4300, // CarPlayAvailability
        )

        /** The same messages without the EA session pair. */
        private val COMPLIANT_RECEIVED = intArrayOf(
            0xaa00, 0xaa02, 0xaa04, 0xaa05,
            0x5001, 0x5201, 0x5202, 0xae01, 0x4158, 0x4155, 0x4300,
        )

        fun forProfile(profile: Iap2IdentificationProfile, eaProtocol: String) =
            when (profile) {
                Iap2IdentificationProfile.VERIFIED_V041 -> Iap2IdentificationMessages(
                    sentByAccessory = V041_SENT.copyOf(),
                    receivedFromPhone = V041_RECEIVED.copyOf(),
                    externalAccessoryProtocols = listOf(eaProtocol),
                )

                Iap2IdentificationProfile.PROTOCOL_COMPLIANT -> Iap2IdentificationMessages(
                    sentByAccessory = V041_SENT.copyOf(),
                    receivedFromPhone = COMPLIANT_RECEIVED.copyOf(),
                    externalAccessoryProtocols = emptyList(),
                )
            }
    }
}

/** Required identity for the minimal wired or wireless iAP2 identification exchange. */
data class Iap2IdentificationConfig(
    val name: String,
    val modelIdentifier: String,
    val manufacturer: String,
    val serialNumber: String,
    val firmwareVersion: String,
    val hardwareVersion: String,
    /** The iPhone USB interface used by CarPlay, supplied explicitly by the deployment. */
    val carPlayUsbInterfaceNumber: Int,
    val language: String = "en",
    /** This app's own EA protocol identifier; it deliberately does not claim a CarPlay EA flag. */
    val externalAccessoryProtocol: String = "com.shilapi.xcertplay",
    /** Non-null selects the wireless Bluetooth and WirelessCarPlay transport components. */
    val wireless: Iap2WirelessIdentification? = null,
    /** Advertises and enables iAP2 LocationInformation from the accessory to the phone. */
    val locationInformationEnabled: Boolean = false,
    /** Declares an electric vehicle and answers the phone's vehicle-status subscription. */
    val vehicleStatusEnabled: Boolean = false,
    /** The charging inlets declared with [vehicleStatusEnabled]. */
    val chargingConnectors: EvChargingConnectors = EvChargingConnectors.CCS2_TYPE2,
    /** Which message set parameters 6/7/10 carry. See [Iap2IdentificationProfile]. */
    val profile: Iap2IdentificationProfile = Iap2IdentificationProfile.VERIFIED_V041,
) {
    constructor(
        name: String,
        modelIdentifier: String,
        manufacturer: String,
        serialNumber: String,
        firmwareVersion: String,
        hardwareVersion: String,
        wireless: Iap2WirelessIdentification,
        language: String = "en",
        externalAccessoryProtocol: String = "com.shilapi.xcertplay",
    ) : this(
        name = name,
        modelIdentifier = modelIdentifier,
        manufacturer = manufacturer,
        serialNumber = serialNumber,
        firmwareVersion = firmwareVersion,
        hardwareVersion = hardwareVersion,
        carPlayUsbInterfaceNumber = 0,
        language = language,
        externalAccessoryProtocol = externalAccessoryProtocol,
        wireless = wireless,
    )

    init {
        listOf(name, modelIdentifier, manufacturer, serialNumber, firmwareVersion, hardwareVersion).forEach {
            require(it.isNotBlank()) { "Identification identity strings must not be blank" }
        }
        require(language.isNotBlank()) { "Identification language must not be blank" }
        require(externalAccessoryProtocol.isNotBlank()) { "External accessory protocol must not be blank" }
        listOf(
            name,
            modelIdentifier,
            manufacturer,
            serialNumber,
            firmwareVersion,
            hardwareVersion,
            language,
            externalAccessoryProtocol,
        ).forEach {
            require('\u0000' !in it) { "NUL-terminated identification strings must not contain U+0000" }
        }
        require(carPlayUsbInterfaceNumber in 0..0xff) {
            "carPlayUsbInterfaceNumber must be in 0..255"
        }
    }
}

/** Identification failures distinguished from the underlying iAP2 transport failure. */
sealed class Iap2IdentificationException(message: String) : IOException(message) {
    class Rejected(
        parameterIds: Set<Int>,
        profile: Iap2IdentificationProfile = Iap2IdentificationProfile.VERIFIED_V041,
    ) : Iap2IdentificationException(
        "iAP2 identification rejected parameters ${parameterIds.sorted().joinToString(prefix = "[", postfix = "]") { "0x${it.toString(16).padStart(4, '0')}" }} " +
            "profile=${profile.name}; parameters 6/7/10 are the message set and the EA protocol, " +
            "and the phone rejects them together when one names what another does not carry",
    ) {
        val parameterIds: Set<Int> = parameterIds.toSet()
        val profile: Iap2IdentificationProfile = profile
    }

    class UnexpectedMessage(messageId: Int) : Iap2IdentificationException(
        "Unexpected iAP2 identification message 0x${messageId.toString(16).padStart(4, '0')}",
    )
}

/**
 * Synchronous accessory-side wired or wireless identification over an already owned CSM channel.
 *
 * This is intentionally only identification: it neither invokes MFi nor itself starts any
 * CarPlay, subscription, power, media, or UI service.
 */
class Iap2IdentificationClient(private val session: Iap2Session) {
    /** Waits for link negotiation, then completes the 1D00/1D01/1D02 exchange. */
    @Throws(IphoneUsbException::class, Iap2IdentificationException::class)
    fun identify(config: Iap2IdentificationConfig, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        require(timeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAXIMUM_TIMEOUT_MILLIS"
        }
        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        if (!session.awaitReady(remainingMillis(deadlineNanos))) {
            throw IphoneUsbException.TimedOut("Timed out waiting for iAP2 control session readiness")
        }

        var currentConfig = config
        var retried = false
        while (true) {
            val frame = session.recv(remainingMillis(deadlineNanos))
                ?: throw IphoneUsbException.TimedOut("Timed out waiting for iAP2 identification")
            when (frame.messageId) {
                START_IDENTIFICATION -> session.send(identificationInformation(currentConfig), remainingMillis(deadlineNanos))
                IDENTIFICATION_ACCEPTED -> return
                IDENTIFICATION_REJECTED -> {
                    val rejected = Iap2BodyReader.of(frame).list().mapTo(LinkedHashSet()) { it.id }
                    if (!retried && (currentConfig.vehicleStatusEnabled || currentConfig.locationInformationEnabled)) {
                        retried = true
                        currentConfig = currentConfig.copy(
                            vehicleStatusEnabled = false,
                            locationInformationEnabled = false,
                        )
                        session.send(identificationInformation(currentConfig), remainingMillis(deadlineNanos))
                        continue
                    }
                    throw Iap2IdentificationException.Rejected(rejected, currentConfig.profile)
                }
                else -> throw Iap2IdentificationException.UnexpectedMessage(frame.messageId)
            }
        }
    }

    companion object {
        const val START_IDENTIFICATION = 0x1d00
        const val IDENTIFICATION_INFORMATION = 0x1d01
        const val IDENTIFICATION_ACCEPTED = 0x1d02
        const val IDENTIFICATION_REJECTED = 0x1d03

        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private const val MAXIMUM_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Builds the smallest honest LIVI-compatible wired or wireless IdentificationInformation. */
        fun identificationInformation(config: Iap2IdentificationConfig): Iap2Frame {
            val wireless = config.wireless
            val messages = messagesFor(config)
            // Bluetooth drops the power-source update and adds the Wi-Fi configuration; the message
            // set behind it is the same one parameters 6/7/10 were checked against.
            val sentByAccessory = if (wireless == null) {
                messages.sentByAccessory.toList()
            } else {
                messages.sentByAccessory.filterNot { it == POWER_SOURCE_UPDATE } +
                    ACCESSORY_WIFI_CONFIGURATION_INFORMATION
            }
            val receivedFromPhone = if (wireless == null) {
                messages.receivedFromPhone.toList()
            } else {
                messages.receivedFromPhone.toList() + WIRELESS_PHONE_MESSAGES.toList()
            }
            return Iap2Messages.build(Iap2Endpoints.IDENTIFICATION_INFORMATION) {
                string(0, config.name)
                string(1, config.modelIdentifier)
                string(2, config.manufacturer)
                string(3, config.serialNumber)
                string(4, config.firmwareVersion)
                string(5, config.hardwareVersion)
                u16List(6, sentByAccessory)
                u16List(7, receivedFromPhone)
                u8(8, if (wireless == null) 2 else 0)
                u16(9, 20)
                // Omitted rather than sent empty when the profile offers no EA plane: parameter 10
                // naming a protocol is what makes the phone expect 0xea00/0xea01 in parameter 7.
                messages.externalAccessoryProtocols.forEachIndexed { index, protocol ->
                    group(10) {
                        u8(0, messages.externalAccessoryProtocols.size)
                        string(1, protocol)
                        u8(2, index)
                    }
                }
                string(12, config.language)
                strings(13, listOf(config.language))
                if (wireless == null) {
                    group(16) {
                        u16(0, 0)
                        string(1, "USBHostTransport")
                        void(2)
                        u8(3, config.carPlayUsbInterfaceNumber)
                        void(4)
                    }
                } else {
                    group(17) {
                        u16(0, 0)
                        string(1, "blue")
                        void(2)
                        bytes(3, wireless.bluetoothMacBytes())
                        string(4, "blue")
                        void(5)
                    }
                    group(24) {
                        u16(0, 1)
                        string(1, wireless.ssid)
                        void(2)
                        u16(3, 1)
                        void(4)
                        void(5)
                    }
                }
                if (config.vehicleStatusEnabled) electricVehicleComponents(config.manufacturer, config.chargingConnectors)
                if (config.locationInformationEnabled) {
                    group(22) {
                        u16(0, 0)
                        string(1, config.name)
                        void(17)
                        void(18)
                    }
                }
                group(30) {
                    u16(0, 42)
                    string(1, "RouteGuidance")
                    // Without these limits the iPhone may omit road names from 0x5201/0x5202.
                    u16(2, 64) // MaxCurrentRoadNameLength
                    u16(4, 64) // MaxAfterManeuverRoadNameLength
                    u16(6, 8)
                }
            }
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw IphoneUsbException.TimedOut("iAP2 identification timed out")
            return min(MAXIMUM_TIMEOUT_MILLIS, (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
        }

        /**
         * The profile's message set plus whatever optional components the deployment turned on.
         *
         * Location and vehicle status are added here rather than in the profile so that both
         * profiles carry them identically, and so that each addition stays in the pairs the
         * constructor checks.
         */
        private fun messagesFor(config: Iap2IdentificationConfig): Iap2IdentificationMessages {
            val base = Iap2IdentificationMessages.forProfile(config.profile, config.externalAccessoryProtocol)
            if (!config.locationInformationEnabled && !config.vehicleStatusEnabled) return base
            val sent = base.sentByAccessory.toMutableList()
            val received = base.receivedFromPhone.toMutableList()
            if (config.locationInformationEnabled) {
                sent += LOCATION_INFORMATION
                received += listOf(START_LOCATION_INFORMATION, STOP_LOCATION_INFORMATION)
            }
            if (config.vehicleStatusEnabled) {
                sent += Iap2VehicleStatus.VEHICLE_STATUS_UPDATE
                received += listOf(
                    Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES,
                    Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES,
                )
            }
            return Iap2IdentificationMessages(
                sentByAccessory = sent.toIntArray(),
                receivedFromPhone = received.toIntArray(),
                externalAccessoryProtocols = base.externalAccessoryProtocols,
            )
        }

        private const val POWER_SOURCE_UPDATE = 0xae03
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val ACCESSORY_WIFI_CONFIGURATION_INFORMATION = 0x5703
        private const val LOCATION_INFORMATION = 0xfffb
        private const val START_LOCATION_INFORMATION = 0xfffa
        private const val STOP_LOCATION_INFORMATION = 0xfffc
        private val WIRELESS_PHONE_MESSAGES = intArrayOf(0x4e0d, 0x4e0e, 0x5702)
    }
}
