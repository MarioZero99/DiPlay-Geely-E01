package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessSessionParameters
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import kotlin.math.min

/**
 * The wireless LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, the five update subscriptions, then Wi-Fi credentials and the
 * 0x4E0D/0x4E0E Wireless CarPlay transport notifications.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-control CSM frame to [onIncoming].
 */
class Iap2WirelessControlClient(
    private val session: Iap2Session,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    fun run(
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        locationProvider: Iap2LocationProvider? = null,
        vehicleStatusProvider: VehicleStatusProvider? = null,
        onReady: () -> Unit = {},
        /**
         * Called once per 0x4301 sent: the phone has now been told the address and the port to use.
         *
         * Its own parameter rather than something read back out of [onProgress]. The progress line
         * exists to be read by a person, and anything that fires on it is wired to the wording of a
         * sentence — a reworded log would silently disarm whatever was listening.
         */
        onCarPlayStartSessionSent: () -> Unit = {},
        onIncoming: (Iap2Frame) -> Unit = {},
        onProgress: (String) -> Unit = {},
        /**
         * Whether a phone that asked for the certificate and then never challenged is carried past instead
         * of failing the run. See [Iap2MfiAuthenticationClient.whenItGoesQuiet] for why silence is worth
         * reading as something other than a failure; off unless a caller asks.
         */
        carryOnWhenTheChallengeNeverComes: Boolean = false,
    ): Iap2WirelessControlResult {
        require(identification.wireless != null) {
            "Wireless control requires an Iap2IdentificationConfig with wireless transport"
        }
        require(timeoutMillis == NO_TIMEOUT_MILLIS || timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
        }

        val deadlineNanos = if (timeoutMillis == NO_TIMEOUT_MILLIS) {
            Long.MAX_VALUE
        } else {
            deadlineAfter(timeoutMillis)
        }
        val identified = identification.withVehicleStatusFrom(vehicleStatusProvider)
        if (identified.vehicleStatusEnabled != identification.vehicleStatusEnabled) {
            onProgress("iap2 no battery reading: not declaring an electric vehicle")
        }
        Iap2IdentificationClient(session).identify(identified, requireRemaining(deadlineNanos))
        onProgress("iap2 identification accepted")
        var stage = Iap2WirelessControlStage.IDENTIFIED

        mfi.run(
            session,
            min(requireRemaining(deadlineNanos), MFI_AUTHENTICATION_TIMEOUT_MILLIS),
            onProgress,
            carryOnWhenTheChallengeNeverComes,
        ).let { challenged ->
            onProgress(
                if (challenged) {
                    "iap2 authentication accepted"
                } else {
                    // Not "accepted": nothing was accepted, the exchange simply never happened. Saying so
                    // is what keeps a run that carried on from reading like a run that authenticated.
                    "iap2 no challenge came for the certificate the phone asked for; carrying on"
                },
            )
        }
        stage = Iap2WirelessControlStage.AUTHENTICATED

        for (subscription in Iap2WiredControlClient.subscriptions()) {
            send(subscription, deadlineNanos)
        }
        stage = Iap2WirelessControlStage.SUBSCRIBED
        onProgress("iap2 subscriptions sent")
        onReady()

        var forwardedFrames = 0
        var wifiConfigurationsSent = 0
        var carPlayStartSessionsSent = 0
        var preTransportWiFiConfigurationsSent = 0
        var postTransportWiFiConfigurationsSent = 0
        var transportNotificationSeen = false
        var wirelessCarPlayAvailableSeen = false
        var locationActive = false
        var locationSentLogged = false
        val vehicleStatus = Iap2VehicleStatusReporter(vehicleStatusProvider, onProgress)
        while (true) {
                val remaining = remainingMillis(deadlineNanos)
                if (remaining == 0L) {
                    return Iap2WirelessControlResult(
                        Iap2WirelessControlTerminal.TIMED_OUT,
                        stage,
                        forwardedFrames,
                        wifiConfigurationsSent,
                        carPlayStartSessionsSent,
                        transportNotificationSeen,
                        postTransportWiFiConfigurationsSent,
                        wirelessCarPlayAvailableSeen,
                    )
                }
                if (locationActive && sendLatestLocation(locationProvider, deadlineNanos) && !locationSentLogged) {
                    locationSentLogged = true
                    onProgress("iap2 tx=0xfffb location-information")
                }
                vehicleStatus.tick { send(it, deadlineNanos) }
                val pollTimeout = vehicleStatus.pollTimeout(
                    if (locationActive) {
                        min(remaining, LOCATION_POLL_INTERVAL_MILLIS)
                    } else {
                        remaining
                    },
                )
                val incoming = session.recv(pollTimeout)
                if (incoming == null) {
                    if (session.isClosed) {
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayAvailableSeen,
                        )
                    }
                    if (remainingMillis(deadlineNanos) == 0L) {
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.TIMED_OUT,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayAvailableSeen,
                        )
                    }
                    continue
                }

                when (incoming.messageId) {
                    REQUEST_ACCESSORY_WIFI_CONFIGURATION -> {
                        onProgress("iap2 rx=0x5702 request-wifi-configuration")
                        val postTransport = transportNotificationSeen
                        val sentCount = if (postTransport) {
                            postTransportWiFiConfigurationsSent
                        } else {
                            preTransportWiFiConfigurationsSent
                        }
                        val limit = if (postTransport) {
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        } else {
                            MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        }
                        if (sentCount >= limit) {
                            onProgress(
                                "iap2 0x5703 ignored: maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            send(accessoryWiFiConfiguration(endpoint), deadlineNanos)
                            stage = later(
                                stage,
                                if (postTransport) {
                                    Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT
                                } else {
                                    Iap2WirelessControlStage.WIFI_CONFIG_SENT
                                },
                            )
                            wifiConfigurationsSent++
                            if (postTransport) {
                                postTransportWiFiConfigurationsSent++
                            } else {
                                preTransportWiFiConfigurationsSent++
                            }
                            onProgress("iap2 tx=0x5703 accessory-wifi-configuration")
                        }
                    }

                    CARPLAY_AVAILABILITY -> {
                        onProgress("iap2 rx=0x4300 carplay-availability")
                        send(carPlayStartSession(endpoint), deadlineNanos)
                        stage = later(stage, Iap2WirelessControlStage.CARPLAY_START_SENT)
                        carPlayStartSessionsSent++
                        onCarPlayStartSessionSent()
                        onProgress("iap2 tx=0x4301 carplay-start-session")
                    }

                    WIRELESS_CARPLAY_UPDATE -> {
                        val available = Iap2WirelessMessages.wirelessCarPlayAvailability(incoming)
                        if (available) wirelessCarPlayAvailableSeen = true
                        onProgress("iap2 rx=0x4e0d wireless-carplay-update available=$available")
                    }

                    DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION -> {
                        transportNotificationSeen = true
                        val identifiers = Iap2WirelessMessages.deviceTransportIdentifier(incoming)
                        stage = later(stage, Iap2WirelessControlStage.TRANSPORT_NOTIFIED)
                        onProgress(
                            "iap2 rx=0x4e0e device-transport-identifier " +
                                "bluetooth=${identifiers.bluetoothMac ?: "none"} " +
                                "usb=${identifiers.usbTransportIdentifier ?: "none"}; " +
                                "resending 0x5703",
                        )
                        if (
                            postTransportWiFiConfigurationsSent >=
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        ) {
                            onProgress(
                                "iap2 post-transport 0x5703 ignored: " +
                                    "maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            send(accessoryWiFiConfiguration(endpoint), deadlineNanos)
                            stage = later(
                                stage,
                                Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT,
                            )
                            wifiConfigurationsSent++
                            postTransportWiFiConfigurationsSent++
                            onProgress(
                                "iap2 tx=0x5703 post-transport accessory-wifi-configuration",
                            )
                        }
                    }

                    Iap2LocationMessages.START_LOCATION_INFORMATION -> {
                        onProgress("iap2 rx=0xfffa start-location-information")
                        locationActive = startLocationUpdates(locationProvider, onProgress)
                        locationSentLogged = false
                        if (locationActive && sendLatestLocation(locationProvider, deadlineNanos)) {
                            locationSentLogged = true
                            onProgress("iap2 tx=0xfffb location-information")
                        }
                    }

                    Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES, Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES -> {
                        vehicleStatus.handle(incoming) { send(it, deadlineNanos) }
                    }

                    Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
                        onProgress("iap2 rx=0xfffc stop-location-information")
                        locationActive = false
                        locationSentLogged = false
                        locationProvider?.stop()
                    }

                    else -> {
                        onProgress("iap2 rx=0x${incoming.messageId.toString(16).padStart(4, '0')}")
                        onIncoming(incoming)
                        forwardedFrames++
                    }
                }
        }
    }

    private fun send(frame: Iap2Frame, deadlineNanos: Long) {
        session.send(frame, requireRemaining(deadlineNanos))
    }

    private fun sendLatestLocation(
        provider: Iap2LocationProvider?,
        deadlineNanos: Long,
    ): Boolean {
        val sentence = provider?.latestNmea() ?: return false
        session.send(
            Iap2LocationMessages.locationInformation(sentence),
            requireRemaining(deadlineNanos),
        )
        return true
    }

    private fun startLocationUpdates(
        provider: Iap2LocationProvider?,
        onProgress: (String) -> Unit,
    ): Boolean {
        if (provider == null) return false
        return try {
            provider.start().also { started ->
                if (!started) onProgress("iap2 location provider did not start")
            }
        } catch (error: Exception) {
            onProgress("iap2 location provider start failed: ${error.message}")
            false
        }
    }

    companion object {
        private const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702
        private const val ACCESSORY_WIFI_CONFIGURATION = 0x5703
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val WIRELESS_CARPLAY_UPDATE = 0x4e0d
        private const val DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION = 0x4e0e
        private const val LOCATION_POLL_INTERVAL_MILLIS = 1_000L
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L

        /**
         * The budget the MFi exchange gets, rather than the caller's remaining deadline.
         *
         * [timeoutMillis] covers the whole control run: identification, five subscriptions, Wi-Fi
         * credentials, and the transport notifications, which is why it is measured in tens of
         * seconds. Authentication is a small part of that, so handing it the whole remaining
         * deadline bought a run that was never going to authenticate another forty seconds of
         * standing still.
         *
         * Six seconds is the measured shape of this step over Bluetooth, not a preference. The
         * phone never answers the first copy of the accessory certificate; it answers the second,
         * and that copy goes out on the peer's own advertised retransmission timeout of four
         * seconds. Measured across every run on this bench, including the ones that authenticated,
         * the phone's first words land 110-120 ms after that retransmission -- so a challenge
         * cannot arrive before the certificate plus about 4.1 seconds, and the certificate is sent
         * a fraction of a second into this step. Six seconds leaves that more than a second and a
         * half of margin. A peer that advertised a longer retransmission timeout would need more,
         * which is why this is written down as a formula rather than picked.
         */
        private const val MFI_AUTHENTICATION_TIMEOUT_MILLIS = 6_000L

        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_RECV_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS = 5
        private const val MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS = 2
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Reference-compatible 0x5703 body. BSSID is omitted when the platform does not expose it. */
        fun accessoryWiFiConfiguration(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2WirelessMessages.accessoryWiFiConfiguration(
                ssid = endpoint.ssid,
                passphrase = endpoint.passphrase,
                channel = endpoint.channel,
                securityType = endpoint.security.wireValue,
                bssid = endpoint.accessPointBssid,
            )

        /** Wireless 0x4301 reply carrying the receiver address, port and pairing identity. */
        fun carPlayStartSession(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2CarPlayMessages.startSession(
                wireless = Iap2WirelessSessionParameters(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    ipAddresses = endpoint.ipAddresses,
                    securityType = endpoint.security.wireValue,
                ),
                airPlayPort = endpoint.airPlayPort,
                deviceIdentifier = endpoint.deviceIdentifier,
                publicKey = endpoint.publicKey,
                sourceVersion = endpoint.sourceVersion,
            )

        private fun later(
            current: Iap2WirelessControlStage,
            next: Iap2WirelessControlStage,
        ): Iap2WirelessControlStage = if (current.ordinal >= next.ordinal) current else next

        private fun deadlineAfter(timeoutMillis: Long): Long {
            val now = System.nanoTime()
            val delta = timeoutMillis * NANOS_PER_MILLISECOND
            return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
        }

        private fun requireRemaining(deadlineNanos: Long): Long = remainingMillis(deadlineNanos).also {
            if (it == 0L) throw IphoneUsbException.TimedOut("Timed out during wireless iAP2 control bring-up")
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return 0L
            return min(
                MAX_RECV_TIMEOUT_MILLIS,
                (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            )
        }
    }
}

/** Wireless 0x5703 security values. */
enum class Iap2WirelessSecurity(val wireValue: Int) {
    NONE(0),
    WEP(1),
    WPA_WPA2(2),
    WPA3_TRANSITION(3),
    WPA3_ONLY(4),
}

/**
 * Wireless network and AirPlay endpoint sent in 0x5703 and 0x4301.
 *
 * [passphrase] is null only where the session joined a network this accessory does not own and
 * could not read its password; the parameter is then omitted from both messages. Every network the
 * accessory creates itself still supplies one, and a secured network whose password is merely
 * unknown is not allowed to pass as either open or password-less.
 */
class Iap2WirelessCarPlayEndpoint(
    val ssid: String,
    val passphrase: String?,
    val channel: Int,
    val security: Iap2WirelessSecurity,
    ipAddresses: List<String>,
    val airPlayPort: Int,
    val deviceIdentifier: String,
    val publicKey: String,
    val sourceVersion: String,
    /**
     * The access point's own address, as a 0x5703 hint, or null when there is none to send.
     *
     * Not this receiver's AirPlay identity: see [Iap2WirelessCarPlayEndpoint] callers, which fill it
     * only where the network belongs to somebody else.
     */
    accessPointBssid: ByteArray? = null,
) {
    val ipAddresses: List<String> = ipAddresses.toList()
    val accessPointBssid: ByteArray? = accessPointBssid?.copyOf()

    init {
        require(ssid.isNotBlank()) { "ssid is required and must not be blank" }
        require(this.accessPointBssid == null || this.accessPointBssid.size == 6) {
            "AP address must contain six bytes"
        }
        require('\u0000' !in ssid) { "ssid must not contain U+0000" }
        require(passphrase == null || '\u0000' !in passphrase) {
            "passphrase must not contain U+0000"
        }
        if (security != Iap2WirelessSecurity.NONE) {
            // Non-null and empty is the one combination that means nothing: it would announce a
            // secured network with no password. Null is the honest "we have none to give".
            require(passphrase == null || passphrase.isNotEmpty()) {
                "passphrase is required for secured Wi-Fi"
            }
        }
        require(channel in 0..0xff) { "channel must be in 0..255" }
        require(ipAddresses.isNotEmpty()) { "At least one wireless IP address is required" }
        require(ipAddresses.all { it.isNotBlank() && '\u0000' !in it }) {
            "Every wireless IP address must be non-blank and must not contain U+0000"
        }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(deviceIdentifier.isNotBlank()) { "deviceIdentifier is required and must not be blank" }
        require('\u0000' !in deviceIdentifier) { "deviceIdentifier must not contain U+0000" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
    }
}

enum class Iap2WirelessControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    WIFI_CONFIG_SENT,
    CARPLAY_START_SENT,
    TRANSPORT_NOTIFIED,
    POST_TRANSPORT_WIFI_CONFIG_SENT,
}

enum class Iap2WirelessControlTerminal { CHANNEL_CLOSED, TIMED_OUT }

/** End state of the control loop only; it is not evidence of a live Wi-Fi or AirPlay session. */
data class Iap2WirelessControlResult(
    val terminal: Iap2WirelessControlTerminal,
    val stage: Iap2WirelessControlStage,
    val forwardedFrames: Int,
    val wifiConfigurationsSent: Int,
    val carPlayStartSessionsSent: Int,
    val transportNotificationSeen: Boolean,
    val postTransportWiFiConfigurationsSent: Int,
    val wirelessCarPlayAvailableSeen: Boolean,
)
