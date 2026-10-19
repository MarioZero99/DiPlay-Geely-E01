package com.shilapi.xcertplay.orchestration

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.DiagLog
import com.shilapi.xcertplay.PerfTrace
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationException
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.RemoteMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.CarHotspotController
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.ExistingWifiManager
import com.shilapi.xcertplay.network.HotspotAddressPolicy
import com.shilapi.xcertplay.network.HotspotAddressRecovery
import com.shilapi.xcertplay.network.HotspotStartResult
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.PortHolders
import com.shilapi.xcertplay.network.packageNameResolver
import com.shilapi.xcertplay.carhop.CarBluetoothHops
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.WirelessInterfaceDiagnostics
import com.shilapi.xcertplay.network.WirelessReceiveDiagnostics
import com.shilapi.xcertplay.network.WirelessStartupDiagnostics
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.network.arpPeersOn
import com.shilapi.xcertplay.transport.AndroidIap2BluetoothLink
import com.shilapi.xcertplay.transport.Iap2BluetoothLink
import com.shilapi.xcertplay.transport.Iap2BluetoothLinks
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Ch341DeviceMatcher
import com.shilapi.xcertplay.transport.Ch341I2cTransport
import com.shilapi.xcertplay.transport.Ch341UsbHost
import com.shilapi.xcertplay.transport.Ch341UsbSession
import com.shilapi.xcertplay.transport.IAP2_IPHONE_UUID
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.UsbBusSnapshot
import com.shilapi.xcertplay.transport.WiredLinkAssessment
import com.shilapi.xcertplay.transport.WiredLinkCycles
import com.shilapi.xcertplay.transport.WiredLinkFinding
import com.shilapi.xcertplay.transport.WiredLinkObservation
import com.shilapi.xcertplay.transport.WirelessHotspotMode
import com.shilapi.xcertplay.transport.WiredLinkStep
import com.shilapi.xcertplay.transport.describeAppleUsbDevice
import com.shilapi.xcertplay.transport.AdapterBluetoothHost
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()

    /**
     * A bring-up that stopped.
     *
     * The reason is a code and not a sentence: this module has no resources and no locale, so a
     * message built here reaches a Chinese car in English. The screen owns the wording.
     * [detail] carries the exception text where there is one — that part is diagnostic and stays
     * as reported, next to a sentence the driver can read.
     */
    data class Failed(
        val reason: CarPlayFailureReason,
        val detail: String? = null,
    ) : CarPlayStatus()
}

/** Why a bring-up stopped, in the screen's own vocabulary. */
enum class CarPlayFailureReason {
    MFI_USB_PERMISSION_DENIED,
    MFI_USB_PERMISSION_TIMEOUT,
    IPHONE_USB_PERMISSION_DENIED,
    IPHONE_USB_PERMISSION_TIMEOUT,
    CONTROL_CHANNEL_CLOSED,
    WIRELESS_CONTROL_CHANNEL_CLOSED,
    TRANSPORT_ATTACH_FAILED,
    BRING_UP_FAILED,

    /**
     * The iPhone refused this accessory's host identity at `com.apple.carkit.service`.
     *
     * Unlike every other reason here it is not a fault on the wire: the phone holds no CarPlay record for
     * the host the accessory presented, which is phone-side state that no retry can change. The screen
     * answers it with an instruction instead of another attempt.
     */
    HOST_ID_REJECTED,

    /**
     * The car is running its own hotspot and DiPlay cannot switch it off, which the same-network route
     * needs it to do — one radio cannot be an access point and a station at once.
     *
     * Filed with [HOST_ID_REJECTED] rather than as a fault on the wire: retrying changes nothing about
     * the head unit's state, it only writes the same sentence into the log every thirty seconds. What
     * differs is that this one needs a person: the screen stops and says to turn the hotspot off in the
     * car's own settings, with the retry button left for afterwards.
     */
    CAR_HOTSPOT_STAYS_ON,

    /**
     * The phone was given an address and a port and never opened the AirPlay port.
     *
     * Retryable, unlike the two above: the next attempt is told to publish the other address family,
     * so this is the one failure here whose retry is not the same attempt again.
     */
    FIRST_TCP_TIMEOUT,

    /**
     * No device ever appeared on the access point, so the address this receiver published was never
     * the question.
     *
     * From the listening socket this and [FIRST_TCP_TIMEOUT] look identical — a port nobody opened —
     * and they are fixed in different places: one by publishing the other address family, the other
     * by getting the phone onto the network at all. The separation is only ever made on evidence: an
     * IPv4 session whose access point shows no resolved neighbour other than this receiver. Then the
     * retry is deliberately *not* told to move the address family, because a phone that never joined
     * cannot be helped by a different address on a network it is not on.
     */
    WIRELESS_PHONE_NOT_ON_NETWORK,
}

/**
 * The one thing the same-network route asks of the car, and the car will not do it.
 *
 * Carried as its own type rather than as a message because the screen has to tell it apart from every
 * other bring-up failure: see [CarPlayFailureReason.CAR_HOTSPOT_STAYS_ON].
 */
internal class CarHotspotStaysOnException(message: String) : IOException(message)

/**
 * No AirPlay TCP inside the window the phone was given.
 *
 * Its own type for the same reason as the exception above, and for one more: this is the failure that
 * says which address family to publish next time, so the screen's sentence and the flip are two
 * readings of one event rather than two guesses at it.
 */
internal class FirstTcpTimeoutException(message: String) : IOException(message)

/**
 * The access point never saw the phone, so there was no address for it to fail to reach.
 *
 * A separate type from [FirstTcpTimeoutException] because the screen has to say the other sentence:
 * nothing was retried with a different address, and the thing to fix is the network the phone should
 * have joined — not the one this receiver handed it.
 */
internal class WirelessPhoneNotOnNetworkException(message: String) : IOException(message)

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/**
 * True when [error], or anything it wraps, carries the Lockdown error code [code].
 *
 * The phone refuses a request by putting the code in its response, so text in the message the client built is
 * the only place the code exists on this side. Both codes this fork acts on are read this way because they are
 * answered differently: `InvalidPairRecord` says the saved record is unusable and is retried once, and
 * `InvalidHostID` says the phone will not accept the host that record names, which no retry changes.
 */
internal fun reportsLockdownError(error: Throwable, code: String): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause.message?.contains(code, ignoreCase = true) == true) return true
        cause = cause.cause
    }
    return false
}

/**
 * Which reason a bring-up failure is reported under.
 *
 * Two of them have to arrive as themselves because the screen answers them by not retrying: a host
 * identity the phone refused, and a hotspot the car will not give up. Everything else is a fault on
 * the wire, and the retry ladder is what that is for. The first-TCP timeout is also named here even
 * though it *is* retried, because it is the one retry that comes out different: the attempt after it
 * publishes the other address family.
 */
internal fun failureReasonFor(error: Throwable): CarPlayFailureReason = when {
    error is CarHotspotStaysOnException -> CarPlayFailureReason.CAR_HOTSPOT_STAYS_ON
    error is FirstTcpTimeoutException -> CarPlayFailureReason.FIRST_TCP_TIMEOUT
    error is WirelessPhoneNotOnNetworkException -> CarPlayFailureReason.WIRELESS_PHONE_NOT_ON_NETWORK
    reportsLockdownError(error, "InvalidHostID") -> CarPlayFailureReason.HOST_ID_REJECTED
    else -> CarPlayFailureReason.BRING_UP_FAILED
}

/**
 * Wires the complete wired or wireless CarPlay path: MFi coprocessor discovery, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
@android.annotation.SuppressLint("MissingPermission")
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    private val locationProvider: Iap2LocationProvider? = null,
    private val vehicleStatusProvider: com.shilapi.xcertplay.transport.VehicleStatusProvider? = null,
    private val adapterBluetooth: AdapterBluetoothHost? = null,
    /**
     * The address family to prefer on the next attempt, once this one is known not to have worked.
     *
     * Called only where the phone never reached us at all — never for a run that connected and later
     * dropped. Where the preference is kept is the caller's business: this class reads
     * [CarPlayRuntimeConfig.wirelessAddressFamily] and never writes a setting itself.
     */
    private val onWirelessAddressFamilyUnusable: (String) -> Unit = {},
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext

    /** Turns a socket-table uid into a package name, so a held port names its holder. */
    private val portPackageNames by lazy { packageNameResolver(appContext) }
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val bluetoothAdapter =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val iphoneHost = IphoneUsbHost(
        appContext,
        usbManager,
        if (config.iphoneDevices.isNotEmpty()) {
            IphoneUsbMatcher(config.iphoneDevices)
        } else {
            IphoneUsbMatcher.appleVendor()
        },
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0
    private var lastReportedStatus: CarPlayStatus? = null
    private var mfiResetLogged = false

    // Where the connection spent its time, kept only so one line can be written when the ladder ends.
    // The report has the whole timeline; this is for a log pulled off the unit without one.
    private var firstStepAtNs = 0L
    private var slowestStep = ""
    private var slowestStepMs = 0L

    // The wired-link diagnosis. [startIphone] resets `wiredEmptyPolls` rather than every phase change,
    // because a re-enumeration detaches the phone by design. The re-plug rounds outlive even this
    // controller: they live in [WiredLinkCycles.shared].
    private var wiredEmptyPolls = 0
    private var lastWiredFinding: WiredLinkFinding? = null

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var ch341Host: Ch341UsbHost? = null
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    private val playbackStatus = com.shilapi.xcertplay.media.CarPlayPlaybackStatus()

    /** Told when the iPhone starts or stops playing media; may run on any thread. */
    @Volatile var playbackListener: ((Boolean) -> Unit)? = null
    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    @Volatile private var wirelessDiagnostics: WirelessStartupDiagnostics? = null
    @Volatile private var bluetoothStream: BlockingDuplexByteStream? = null
    @Volatile private var bluetoothLink: Iap2BluetoothLink? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    /** The address this run handed the phone, and the one the other family could offer instead. */
    @Volatile private var wirelessPublishedAddress: InetAddress? = null
    @Volatile private var wirelessAlternateAddress: InetAddress? = null
    @Volatile private var wirelessAddressRecovery: HotspotAddressRecovery? = null
    /**
     * The interface the access point came up on, kept only for the question asked when no TCP arrives:
     * whose neighbour table to read. Null whenever no hotspot of this run is up.
     */
    @Volatile private var wirelessHotspotInterface: String? = null
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)

    /**
     * How many bring-ups have already ended with the phone taking the certificate and never answering it.
     *
     * Starting the whole thing over is the one thing that failure has ever given way to: on the bench the
     * same bytes that went unanswered were challenged on the next connection, twice, while nothing about
     * the accessory's own frames differed. So this failure takes the bring-up back to its start instead of
     * being reported at once -- which is also what a driver does by hand -- and the count is what keeps that
     * from becoming a loop.
     *
     * The certificate is deliberately not re-sent on the same link as an alternative: that was measured, a
     * phone that stayed silent through three connections was given the certificate as a new message with the
     * next sequence number and answered nothing, and a re-send placed early actually costs the link's own
     * repeat of the old number -- the one stimulus every answered run has answered.
     */
    private val wirelessAuthenticationAttempts = AtomicInteger(0)
    /**
     * Bounds the automatic restarts of one wireless run, so flapping interfaces cannot restart it
     * forever.
     *
     * The budget is per connect: [start] hands it back in full, because a driver pressing connect
     * again is asking for a fresh attempt rather than extending a spent one. It is deliberately not
     * restored in [startWireless] — [restartWireless] goes through [startWireless] after its backoff,
     * so resetting there would make the breaker unable to trip at all.
     */
    private val wirelessRestartLimiter = WirelessRestartLimiter()
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var detachCloseable: Closeable? = null
    private var ch341PermissionCloseable: Closeable? = null
    private var vpnLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            fail(IphoneUsbException.DeviceUnavailable("CarPlay VPN service disconnected"))
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            activeSession = session
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) {
                activeSession = null
                synchronized(playbackStatus) { playbackStatus.clear() }?.let { playing -> playbackListener?.invoke(playing) }
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        // The user tapped the car icon in CarPlay: show the head unit's own menu, like its Home button.
        // The session keeps running in the background, so returning to DiPlay resumes CarPlay.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI; opening the head-unit home screen")
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { debugLog("Car home screen could not open: ${it.javaClass.simpleName}") }
            uiListener?.onHostUiRequested(session)
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && vpnService?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        // The driver asked for this connection, so the restart budget starts here and nowhere else:
        // `restartWireless()` re-enters `startWireless()`, and a reset there would be a budget that
        // refills itself. Without this, a run that spent its five restarts could not be tried again
        // from the page at all — the only way back would be restarting the app.
        wirelessAuthenticationAttempts.set(0)
        wirelessRestartLimiter.reset()
        if (config.transport == CarPlayTransport.WIRED) {
            permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
            attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
            detachCloseable = iphoneHost.registerDetachReceiver(::onIphoneDetached)
        }
        startMfi()
    }

    /** Reopens the CH341/MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        // Asked for by hand, so it gets the whole budget again.
        wirelessAuthenticationAttempts.set(0)
        wirelessRestartLimiter.reset()
        if (mfiSession == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRELESS) {
            restartWireless()
        } else {
            startIphone()
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTouch(contacts) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends one CarPlay media-button press (an [com.shilapi.xcertplay.airplay.AirPlayHid] media index). */
    /** Opens Siri on the iPhone, as the car's voice button does in CarPlay. */
    fun requestSiri(): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.invokeSiri() }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendMediaButton(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendMedia(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        closeReceivers()
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        permissionPollGeneration += 1
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = vpnService
        unbindVpn()
        Thread(
            {
                try {
                    if (config.transport == CarPlayTransport.WIRELESS) {
                        closeBestEffort("wireless stack") { closeWirelessStack(service) }
                    } else {
                        closeBestEffort("CSM") { csm?.close() }
                        csm = null
                    }
                    closeBestEffort("USBMUX") { mux?.close() }
                    mux = null
                    if (config.transport == CarPlayTransport.WIRED) {
                        closeBestEffort("VPN/NCM") { service?.detach() }
                    }
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    wirelessIdentification = null
                    wirelessAirPlayEndpoint = null
                    closeBestEffort("location provider") { locationProvider?.close() }
                } finally {
                    executor.shutdownNow()
                    try {
                        executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    teardownComplete.countDown()
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Waits for USB, iAP2, MFi and VPN teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun onRouteFrame(frame: com.shilapi.xcertplay.iap2.wire.Iap2Frame) {
        synchronized(playbackStatus) { playbackStatus.accept(frame) }?.let { playing -> playbackListener?.invoke(playing) }
    }

    private fun startMfi() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        val privateRoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) appContext.noBackupFilesDir else appContext.filesDir
        val offlineDirectory = java.io.File(privateRoot, LocalMfiAuthenticationClient.DIRECTORY)
        if (offlineDirectory.exists()) {
            openLocalMfi(offlineDirectory)
            return
        }
        when (config.mfiTarget) {
            MfiTarget.LOCAL -> openLocalMfi(offlineDirectory)
            MfiTarget.USB_CH341 -> {
                debugLog("mfi discovery backend=CH341 devices=${config.ch341Devices}")
                val host = ch341Host ?: Ch341UsbHost(
                    appContext,
                    usbManager,
                    Ch341DeviceMatcher(config.ch341Devices),
                ).also {
                    ch341Host = it
                    ch341PermissionCloseable = it.registerPermissionReceiver(::onCh341Permission)
                }
                checkCh341Mfi(host)
            }
            MfiTarget.I2C -> {
                debugLog("mfi discovery backend=Linux I2C path=${config.linuxI2cPath}")
                openLinuxMfi()
            }
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi()
            }
        }
    }

    private fun openLocalMfi(directory: java.io.File) {
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently use the helper.
                fail(error)
            }
        }
    }

    private fun openRemoteMfi() {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun checkCh341Mfi(host: Ch341UsbHost) {
        if (closed || phase != Phase.MFI) return
        val device = host.discover().firstOrNull()
        if (device == null) {
            waitForMfi()
        } else {
            availabilityPollGeneration.incrementAndGet()
            requestCh341Permission(device)
        }
    }

    private fun openLinuxMfi() {
        executor.execute {
            try {
                val transport = LinuxI2cTransport.open(config.linuxI2cPath!!)
                try {
                    mfiSession = MfiSession(MfiRuntime.scan(transport), transport)
                    debugLog("mfi Linux I2C coprocessor ready path=${config.linuxI2cPath}")
                    onStatus(CarPlayStatus.MfiReady)
                    startPhone()
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: MfiCoprocessorNotFoundException) {
                debugLog("mfi Linux discovery failed: ${error.message}")
                waitForMfi()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun requestCh341Permission(device: UsbDevice) {
        try {
            when (val request = ch341Host!!.requestPermission(device)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted -> {
                    permissionGrant.set(false)
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(request.device))
                }
                is Ch341UsbHost.PermissionRequest.Requested -> {
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingMfiPermission)
                    pollCh341Permission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onCh341Permission(result: Ch341UsbHost.PermissionResult) {
        if (closed || phase != Phase.MFI) return
        when (result) {
            is Ch341UsbHost.PermissionResult.Granted -> {
                // The system broadcast and CarUsbHandler's direct grant can both observe success.
                if (!permissionGrant.compareAndSet(false, true)) return
                permissionPollGeneration++
                openCh341(result.device)
            }
            is Ch341UsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed(CarPlayFailureReason.MFI_USB_PERMISSION_DENIED))
            }
        }
    }

    /** Some car systems grant USB access through CarUsbHandler without delivering a broadcast. */
    private fun pollCh341Permission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || phase != Phase.MFI || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(CarPlayFailureReason.MFI_USB_PERMISSION_TIMEOUT),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun openCh341(device: UsbDevice) {
        ch341Host!!.openAsync(device, executor) { result ->
            when (result) {
                is Ch341UsbHost.OpenResult.Connected -> {
                    val session: Ch341UsbSession = result.session
                    if (closed || phase != Phase.MFI) {
                        session.close()
                        return@openAsync
                    }
                    try {
                        val transport = Ch341I2cTransport(session)
                        config.ch341MfiResetGpio?.let { gpio ->
                            transport.pulseActiveLowReset(gpio)
                            if (!mfiResetLogged) {
                                mfiResetLogged = true
                                debugLog("mfi reset pulse gpio=D$gpio mode=low/high-z")
                            }
                        }
                        val client = MfiRuntime.scan(transport)
                        val probes = mfiCandidateAddresses
                            .associateWith { address -> probeMfiCandidate(transport, address) }
                        for ((address, probe) in probes) {
                            debugLog("mfi probe address=0x${address.toString(16)} ${probe.describe()}")
                        }
                        val selected = preferCertificateBearingAddress(client, transport, probes)
                        debugLog(
                            "mfi coprocessor address=0x${selected.address7Bit.toString(16)} " +
                                "protocolMajor=${selected.protocolMajor()}",
                        )
                        mfiSession = MfiSession(selected, session)
                        debugLog("mfi CH341 session ready")
                        onStatus(CarPlayStatus.MfiReady)
                        startPhone()
                    } catch (error: MfiCoprocessorNotFoundException) {
                        debugLog("mfi CH341 discovery failed: ${error.message}")
                        Log.w(IphoneCarPlayConfiguration.TAG, error.message ?: "MFi discovery failed")
                        session.close()
                        waitForMfi()
                    } catch (error: Throwable) {
                        session.close()
                        fail(error)
                    }
                }
                is Ch341UsbHost.OpenResult.Failed -> when (result.error) {
                    is I2cTransportException.DeviceUnavailable -> waitForMfi()
                    else -> fail(result.error)
                }
            }
        }
    }

    private val mfiCandidateAddresses = listOf(0x10, 0x11)
    private val maxMfiCertificateBytes = 1280

    private data class MfiCandidateProbe(
        val deviceVersion: Int?,
        val firmwareVersion: Int?,
        val protocolMajor: Int?,
        val accessoryCertificateLength: Int?,
        val appleCertificateLength: Int?,
        val failure: String?,
    ) {
        val hasAccessoryCertificate: Boolean
            get() = (accessoryCertificateLength ?: 0) in 1..1280

        fun describe(): String {
            if (failure != null) return failure
            return "deviceVersion=" + hex(deviceVersion) +
                " firmwareVersion=" + hex(firmwareVersion) +
                " protocolMajor=" + hex(protocolMajor) +
                " accessoryCertificateLength=" + accessoryCertificateLength +
                " appleCertificateLength=" + appleCertificateLength
        }

        private fun hex(value: Int?): String =
            if (value == null) "?" else "0x" + value.toString(16).padStart(2, '0')
    }

    private fun probeMfiCandidate(transport: I2cTransport, address7Bit: Int): MfiCandidateProbe = try {
        MfiCandidateProbe(
            deviceVersion = readMfiRegister(transport, address7Bit, 0x00, 1),
            firmwareVersion = readMfiRegister(transport, address7Bit, 0x01, 1),
            protocolMajor = readMfiRegister(transport, address7Bit, 0x02, 1),
            accessoryCertificateLength = readMfiRegister(transport, address7Bit, 0x30, 2),
            appleCertificateLength = readMfiRegister(transport, address7Bit, 0x50, 2),
            failure = null,
        )
    } catch (error: Throwable) {
        MfiCandidateProbe(
            deviceVersion = null,
            firmwareVersion = null,
            protocolMajor = null,
            accessoryCertificateLength = null,
            appleCertificateLength = null,
            failure = "failed: " + error.javaClass.simpleName + ": " + error.message,
        )
    }

    private fun readMfiRegister(
        transport: I2cTransport,
        address7Bit: Int,
        register: Int,
        length: Int,
    ): Int {
        transport.transaction(address7Bit, byteArrayOf(register.toByte()), 0)
        var value = 0
        for (byte in transport.transaction(address7Bit, ByteArray(0), length)) {
            value = (value shl 8) or (byte.toInt() and 0xff)
        }
        return value
    }

    private fun preferCertificateBearingAddress(
        client: MfiAuthenticationClient,
        transport: I2cTransport,
        probes: Map<Int, MfiCandidateProbe>,
    ): MfiAuthenticationClient {
        if (probes[client.address7Bit]?.hasAccessoryCertificate == true) return client
        val alternative = probes.entries.firstOrNull { (address, probe) ->
            address != client.address7Bit && probe.hasAccessoryCertificate
        } ?: return client
        debugLog(
            "mfi address override: 0x" + client.address7Bit.toString(16) +
                " has no accessory certificate; using 0x" + alternative.key.toString(16),
        )
        return MfiAuthenticationClient(transport, alternative.key)
    }

    private fun waitForMfi() {
        if (closed || phase != Phase.MFI) return
        onStatus(CarPlayStatus.WaitingForMfi)
        scheduleAvailabilityPoll(Phase.MFI) {
            when (config.mfiTarget) {
                MfiTarget.USB_CH341 -> ch341Host?.let(::checkCh341Mfi)
                MfiTarget.I2C -> openLinuxMfi()
                MfiTarget.REMOTE, MfiTarget.LOCAL -> Unit
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun startWireless() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.WIRELESS
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        onStatus(CarPlayStatus.StartingHotspot)
        val generation = wirelessGeneration.incrementAndGet()
        executor.execute {
            runWireless(generation)
        }
    }

    private fun restartWireless() {
        val backoff = wirelessRestartLimiter.nextBackoffOrNull()
        if (backoff == null) {
            val count = wirelessRestartLimiter.currentAttempts
            debugLog("wireless restart budget exhausted ($count/${wirelessRestartLimiter.maxAttempts}), failing session")
            // Nothing will run after this, so the stack is torn down here rather than reported and
            // left standing: every other failure path out of the bring-up closes first and fails
            // second, and with the budget spent there is no later run to release Bonjour, RFCOMM, the
            // AirPlay listener or the hotspot. `fail` only turns the lamp red — it stops nothing.
            //
            // The generation is moved first, so a bring-up still in flight treats itself as superseded
            // and lets go of what it opened instead of continuing into a torn-down stack.
            wirelessGeneration.incrementAndGet()
            Thread(
                {
                    closeWirelessStack()
                    fail(
                        IOException(
                            "Wireless CarPlay failed: maximum restart budget " +
                                "(${wirelessRestartLimiter.maxAttempts}) exceeded due to persistent " +
                                "network flapping",
                        ),
                    )
                },
                "xcertplay-wireless-restart-exhausted",
            ).apply {
                isDaemon = true
                start()
            }
            return
        }
        wirelessGeneration.incrementAndGet()
        Thread(
            {
                debugLog("wireless restart scheduled with backoff=${backoff}ms (attempt=${wirelessRestartLimiter.currentAttempts})")
                try {
                    Thread.sleep(backoff)
                } catch (ignored: InterruptedException) {
                    return@Thread
                }
                closeWirelessStack()
                if (!closed) startWireless()
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Takes a bring-up whose authentication went unanswered back to its first step, within the budget.
     *
     * Returns false once the budget is spent, and then the caller fails exactly as it did before this
     * existed. Nothing of the failed attempt is kept: the whole wireless bring-up runs again from the top.
     */
    private fun restartWirelessAfterSilentAuthentication(): Boolean {
        val attempt = wirelessAuthenticationAttempts.incrementAndGet()
        if (attempt >= MAXIMUM_WIRELESS_AUTHENTICATION_ATTEMPTS) {
            debugLog("wireless authentication went unanswered $attempt times in a row; reporting the failure")
            return false
        }
        debugLog(
            "wireless authentication was not answered; starting the whole wireless bring-up over " +
                "($attempt of $MAXIMUM_WIRELESS_AUTHENTICATION_ATTEMPTS done)",
        )
        restartWireless()
        return true
    }

    private fun runWireless(generation: Int) {
        // A run superseded mid-flight still owns whatever it opened after the newer run's cleanup ran.
        // The shared fields now belong to that newer run, so `closeWirelessStack()` would tear down the
        // wrong session; these locals are what lets the losing run let go of its own.
        var ownBonjour: CarPlayBonjour? = null
        var ownStream: BlockingDuplexByteStream? = null
        try {
            debugLog("wireless bring-up generation=$generation starting")
            closeWirelessStack()
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            // Before the hotspot, not after the Bonjour advertisement. §18.4's name comes from the car's
            // own radio, and a radio that will not say what it is called ends the run whatever else came
            // up — so asking first costs one service call instead of a whole bring-up: hotspot switched
            // on, AirPlay listener bound, 5353 taken, RFCOMM open, and only then the name refused, with
            // the failure landing on the data link rather than on identity. The authoritative reading
            // still happens below; this is the same question asked at a point where the answer is cheap.
            requireCarHopRadioName()
            val hotspotInfo = startWirelessHotspot(generation)
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            wirelessHotspotInterface = hotspotInfo.interfaceName
            val startedHotspot = hotspot
            wirelessConnectionProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
            }
            // Which family to publish is what the last run learned, not a fresh guess each time. A run
            // that published an address the phone never answered on leaves the other family behind for
            // the next attempt, so the two get tried in turn instead of the same one failing forever.
            val preferredFamily = config.wirelessAddressFamily
            val addressRecovery = HotspotAddressRecovery(
                sample = { hotspotCandidates(hotspotInfo, fallbackToSnapshot = false) },
                cancelled = { isStaleWirelessRun(generation) },
                pause = Thread::sleep,
                log = { if (!isStaleWirelessRun(generation)) debugLog(it) },
            )
            val candidates = addressRecovery.awaitCandidates(hotspotCandidates(hotspotInfo), preferredFamily)
            val hostAddress = HotspotAddressPolicy.select(candidates, preferredFamily)
                ?: hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            wirelessPublishedAddress = hostAddress
            wirelessAlternateAddress = HotspotAddressPolicy.alternateFamilyCandidate(candidates, hostAddress)
            wirelessAddressRecovery = addressRecovery
            // Built here rather than at the 0x4301, because the port it watches is bound before then and
            // a phone that finds us through Bonjour may open it first. See [FirstTcpWatchdog].
            val firstTcpWatchdog = FirstTcpWatchdog(
                schedule = { delay, body -> mainHandler.postDelayed(body, delay) },
                onTimeout = { onFirstTcpTimedOut(generation) },
                log = { message -> if (!isStaleWirelessRun(generation)) debugLog(message) },
            )
            // A link-local IPv6 has to carry a scope to be useful at all: the scope is what says
            // which interface the fe80::/64 belongs to, and it is read locally by JmDNS and by every
            // socket that binds this address. One that arrives without it names nothing this side can
            // reach, so it is refused rather than published.
            //
            // A global IPv6 is a different case and is allowed: it carries no scope by nature and is
            // routable for a phone on the same network, which is a shape the same-network route can
            // now produce (see [existingWifiHostAddresses]).
            if (
                hostAddress is Inet6Address &&
                hostAddress.isLinkLocalAddress &&
                hostAddress.scopeId == 0
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "preference=${preferredFamily ?: "default"} " +
                    // Whether the flip has anywhere to go. Recorded on every run because it is the one
                    // thing that decides whether the next attempt can differ: with no address on the
                    // other family, a first-TCP timeout can only be retried on the same one again.
                    "alternate=${wirelessAlternateAddress != null} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            var startedBonjour: CarPlayBonjour? = null
            val receiveDiagnostics = WirelessReceiveDiagnostics(hotspotInfo.interfaceName)
            val diagnostics = WirelessStartupDiagnostics(
                sample = {
                    "${WirelessInterfaceDiagnostics.snapshot(hotspotInfo.interfaceName)} " +
                        "${startedHotspot?.connectionDiagnosticSnapshot() ?: "association=unknown"} " +
                        (startedBonjour?.diagnosticSnapshot() ?: "bonjour=not_started") + "\n" +
                        receiveDiagnostics.snapshot()
                },
                log = { message -> if (!isStaleWirelessRun(generation)) debugLog(message) },
            )
            wirelessDiagnostics = diagnostics
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)

            val link = Iap2BluetoothLinks.chooseForWireless(
                config.wirelessBluetoothHop,
                adapterBluetooth,
                config.wirelessBluetoothDeviceAddress,
                ::debugLog,
                fallbackAdapterName = config.identification.name,
            ) {
                // A hop this build ships, for head units whose own stack cannot carry the leg. Absent
                // from builds that have none, which is the ordinary case.
                val vendorHop = CarBluetoothHops.transport?.takeIf {
                    runCatching { it.applies(appContext) }.getOrDefault(false)
                }
                if (vendorHop != null) {
                    debugLog("wireless Bluetooth hop=car-supplied")
                    return@chooseForWireless vendorHop.link(
                        appContext,
                        config.wirelessBluetoothDeviceAddress,
                        airPlayConfig.btMac,
                        ::debugLog,
                    )
                }
                val adapter = bluetoothAdapter
                    ?: throw IOException("Bluetooth adapter is unavailable")
                if (!adapter.isEnabled) throw IOException("Bluetooth is not enabled")
                AndroidIap2BluetoothLink(
                    appContext,
                    adapter,
                    config.wirelessBluetoothDeviceAddress,
                    airPlayConfig.btMac,
                    ::debugLog,
                )
            }
            bluetoothLink = link
            val hostBluetoothMac = link.localAddress
                ?: throw IOException("Bluetooth adapter is unavailable")
            val target = link.target()
                ?: throw IOException("No unambiguous bonded iPhone found; pair one iPhone and retry")
            debugLog(
                "wireless selected Bluetooth target name=${target.name ?: "unknown"} " +
                    "address=${target.address} localBt=$hostBluetoothMac",
            )
            // The port is not chosen here. Every address this attachment serves has to share one — the
            // phone learns a single port for the whole device — so it is settled by binding all of them
            // together, inside the service, and read back below. 7000 is conventional and on a head unit
            // it is also a port something else can be holding; the iPhone learns whichever one was bound
            // from the Bonjour record and from the iAP2 Wi-Fi configuration, so a fallback reaches it.
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
            )
            // The addresses the phone is told about, and the ones it can actually reach, are the same
            // list: everything on the selected interface except the one the endpoint prefers.
            val additionalAddresses = hotspotInfo.hostAddresses.filter { it != hostAddress }

            onStatus(CarPlayStatus.AttachingNetwork)
            val service = awaitVpnService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            when (
                val result = service.attachWireless(
                    bindAddress = hostAddress,
                    config = wirelessAirPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfi,
                    listener = wirelessSessionListener(generation, firstTcpWatchdog),
                    media = media,
                    additionalBindAddresses = additionalAddresses,
                )
            ) {
                CarPlayVpnService.AttachResult.Started -> Unit
                CarPlayVpnService.AttachResult.AlreadyStarted ->
                    throw IOException("Wireless AirPlay transport is already attached")
                is CarPlayVpnService.AttachResult.Failed -> {
                    // Every address is tried on one shared port, so a refusal covers both the port being
                    // taken on all of them and one address having gone away under us. Naming the set is
                    // what tells the two apart afterwards.
                    debugLog(
                        "wireless AirPlay listener attach failed addresses=" +
                            (listOf(hostAddress) + additionalAddresses).joinToString(",") { it.hostAddress ?: "?" },
                    )
                    throw IOException(result.message)
                }
            }
            // What was actually bound, which is what gets advertised. A fallback the phone is never
            // told about is the same as no listener at all.
            val listenPort = service.boundPort() ?: wirelessAirPlayConfig.port
            val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = listenPort)
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText port=$listenPort" +
                    (if (listenPort != airPlayConfig.port) " (preferred ${airPlayConfig.port} in use)" else "") +
                    " addresses=${(listOf(hostAddress) + additionalAddresses).joinToString(",") { it.hostAddress ?: "?" }}",
            )
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            // Bluetooth before discovery: the iPhone opens this leg unprompted (the iAP2 bootstrap carries
            // the Wi-Fi credentials it needs first), and discovery is the step that can fail outside here.
            onStatus(CarPlayStatus.ConnectingBluetooth)
            debugLog(
                "wireless RFCOMM connecting address=${target.address} " +
                    "uuid=$IAP2_IPHONE_UUID",
            )
            val stream = link.open().also { bluetoothStream = it }
            ownStream = stream
            debugLog("wireless RFCOMM connected address=${target.address}")
            if (isStaleWirelessRun(generation)) {
                releaseOwnWirelessResources(ownBonjour, ownStream)
                closeWirelessStack()
                return
            }

            // A responder that will not come up costs this receiver's advertisement, not the attempt. Every
            // wireless attempt on this head unit has ended on this step, and the iPhone is given the
            // address and the port over the Bluetooth leg as well; losing the advertisement is
            // recoverable, losing the attempt is not. This stays additive: where 5353 is free nothing here
            // changes, and where it is not, the attempt used to die without ever reaching authentication.
            //
            // The construction belongs inside the guard with the start, and the guard takes any exception:
            // the advertised address is parsed in the constructor, so a host it rejects is the same lost
            // advertisement as a socket that will not bind — and it rejects one by throwing
            // IllegalArgumentException, which the IOException this used to catch never covered. Built
            // outside the guard, that one exception ended the whole attempt at the very step the guard
            // exists to make survivable.
            val bonjourClient = try {
                CarPlayBonjour(
                    context = appContext,
                    config = advertisedAirPlayConfig,
                    identity = identity,
                    // The scoped form, not the display text above: a JmDNS instance for a link-local
                    // IPv6 needs the scope to know which interface's group to join. [hostAddressText]
                    // strips it because iAP2 wants a bare literal, and that is a different requirement.
                    advertisedHost = hostAddress.hostAddress,
                    // Bind discovery and its connect probe to the same AP/address family as AirPlay.
                    // The car hotspot previously used system NSD, which could resolve another interface
                    // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
                    useInterfaceMdns = true,
                    onEvent = { event -> debugLog("wireless bonjour: ${event.diagnosticSummary()}") },
                    // Published as well as listened on: an address the phone can reach but cannot find
                    // is one it will not use, and discovery is where it decides that.
                    additionalAddresses = additionalAddresses,
                )
            } catch (error: Exception) {
                debugLog("wireless Bonjour unavailable, carrying on without an advertisement: ${error.message}")
                null
            }
            // Set even when the construction failed, so nothing is built that no teardown path can see.
            bonjour = bonjourClient
            ownBonjour = bonjourClient
            if (bonjourClient != null) {
                // Sampled where it matters: this is the step that binds 5353, once per advertised
                // address. Whoever holds the port is the whole explanation for an advertisement that
                // never appears, and the kernel table is the only place that says who.
                debugLog("wireless 5353 before-bonjour ${PortHolders.udp(CarPlayBonjour.MDNS_PORT, portPackageNames)}")
                try {
                    bonjourClient.start()
                    startedBonjour = bonjourClient
                    debugLog("wireless Bonjour services started mode=interface iface=${hotspotInfo.interfaceName ?: "unknown"}")
                } catch (error: Exception) {
                    debugLog("wireless Bonjour unavailable, carrying on without an advertisement: ${error.message}")
                }
            }
            diagnostics.start()
            if (isStaleWirelessRun(generation)) {
                releaseOwnWirelessResources(ownBonjour, ownStream)
                closeWirelessStack()
                return
            }

            val channel = Iap2Session.openWireless(
                stream,
                traceContext = "wireless-rfcomm",
                onTrace = ::debugLog,
            ).also { csm = it }
            debugLog("wireless iAP2 CSM channel opened over RFCOMM")
            if (isStaleWirelessRun(generation)) {
                releaseOwnWirelessResources(ownBonjour, ownStream)
                closeWirelessStack()
                return
            }
            // §18.4 ties this name to the one in the radio's Extended Inquiry Response. The radio here
            // is the head unit's own and belongs to a vendor service, so the name is read off it, at
            // this moment rather than at build time — the driver can rename the car's Bluetooth.
            //
            // Nothing is substituted when the read comes up empty. A name the phone has not heard in an
            // inquiry makes it file this exchange under an accessory it has never paired with, which is
            // the exact condition the requirement exists to prevent; a session that cannot name itself
            // honestly is worth less than a session that says why it did not start.
            val radioName = WirelessIdentityResolver.resolveRadioName(
                link = link,
                hopType = config.wirelessBluetoothHop,
                fallbackCarHopName = CarBluetoothHops.active?.localName(appContext),
            )
            debugLog("wireless radio name=$radioName")
            val identification = config.identification.copy(
                name = radioName,
                wireless = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid),
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                // Every address the listener is bound on and Bonjour publishes has to be named here
                // too, or the phone can only ever dial the one this side happened to prefer: the other
                // family is bound and advertised and invisible to it, and the phone picks out of this
                // list, not out of the advertisement. The preferred address stays first, the rest are
                // the same set the bind and the advertisement were given.
                ipAddresses = (listOf(hostAddress) + additionalAddresses).map { hostAddressText(it) },
                airPlayPort = listenPort,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                accessPointBssid = hotspotInfo.accessPointBssid,
            )
            wirelessIdentification = identification
            wirelessAirPlayEndpoint = endpoint
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            onStatus(CarPlayStatus.RunningWireless)
            debugLog("wireless Bluetooth iAP2 control starting")
            val result = Iap2WirelessControlClient(
                session = channel,
                mfi = Iap2MfiAuthenticationClient(mfi),
            ).run(
                identification = identification,
                endpoint = endpoint,
                timeoutMillis = controlLoopTimeoutMillis(),
                locationProvider = locationProvider,
                vehicleStatusProvider = vehicleStatusProvider,
                onCarPlayStartSessionSent = { firstTcpWatchdog.startSessionSent() },
                onIncoming = ::onRouteFrame,
                onProgress = { message ->
                    diagnostics.controlProgress(message)
                    debugLog(message)
                },
            )
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    if (!wirelessActiveReported.get()) {
                        val handoffInProgress = isWirelessHandoffInProgress(
                            handoffRequested = wirelessHandoffRequested.get(),
                            tunnelActive = wirelessTunnelChannel != null,
                            sessionActive = activeSession != null,
                        )
                        if (!handoffInProgress) {
                            throw IOException(
                                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                            )
                        }
                        debugLog(
                            "wireless Bluetooth bootstrap closed during handoff; " +
                                "keeping the Wi-Fi AirPlay tunnel alive",
                        )
                    }
                }
                // The control conversation's own deadline, which says nothing about the address: a phone
                // that opened the AirPlay port and then went quiet reaches this too. The family flip is
                // [FirstTcpWatchdog]'s question, and it has already been answered or already fired by now.
                Iap2WirelessControlTerminal.TIMED_OUT ->
                    if (!wirelessActiveReported.get()) {
                        tearDownAtControlEnded(generation)
                    }
            }
        } catch (error: Throwable) {
            if (closed || generation != wirelessGeneration.get()) {
                releaseOwnWirelessResources(ownBonjour, ownStream)
                return
            }
            if (wirelessActiveReported.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                // The certificate went unanswered, which a fresh link has answered before: restart the whole
                // thing rather than hand the driver a failure they would answer by pressing connect.
                if (error is Iap2MfiAuthenticationException && restartWirelessAfterSilentAuthentication()) {
                    return
                }
                closeWirelessStack()
                if (error is Error) throw error
                fail(error)
            }
        }
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed || config.transport != CarPlayTransport.WIRELESS) return false
        val identification = wirelessIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        wirelessTunnelChannel = channel
        val generation = wirelessGeneration.get()
        debugLog("wireless iAP2 tunnel control starting")
        val tunnelWatchdog = Runnable {
            if (!wirelessTunnelReady.get() && generation == wirelessGeneration.get()) {
                debugLog("wireless iAP2 tunnel handshake timed out after 10s waiting for identification/auth")
                closeBestEffort("stuck iAP2 tunnel link") { channel.close() }
            }
        }
        mainHandler.postDelayed(tunnelWatchdog, 10_000L)
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        vehicleStatusProvider = vehicleStatusProvider,
                        onReady = {
                            mainHandler.removeCallbacks(tunnelWatchdog)
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = ::onRouteFrame,
                        onProgress = { message ->
                            wirelessDiagnostics?.controlProgress(message)
                            debugLog("iAP tunnel $message")
                        },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            tearDownAtControlEnded(generation)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed(CarPlayFailureReason.WIRELESS_CONTROL_CHANNEL_CLOSED))
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get()) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                CarPlayFailureReason.BRING_UP_FAILED,
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    mainHandler.removeCallbacks(tunnelWatchdog)
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            mainHandler.removeCallbacks(tunnelWatchdog)
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun wirelessSessionListener(
        generation: Int,
        firstTcpWatchdog: FirstTcpWatchdog,
    ): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            override fun onConnectionAccepted() {
                if (isStaleWirelessRun(generation)) return
                firstTcpWatchdog.accepted()
                wirelessDiagnostics?.connectionAccepted()
            }

            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessDiagnostics?.let {
                    it.sessionActive()
                    it.close()
                }
                wirelessConnectionProof.activate(generation, session)
                sessionListener.onSessionActive(session)
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                wirelessConnectionProof.rendered(generation, session)
            }
        }

    private fun onWirelessTunnelReady(generation: Int) {
        if (
            closed ||
            phase != Phase.WIRELESS ||
            generation != wirelessGeneration.get()
        ) {
            return
        }
        wirelessTunnelReady.set(true)
        wirelessConnectionProof.authenticated(generation)
        debugLog(
            "wireless iAP2 tunnel ready; " +
                "handoffRequested=${wirelessHandoffRequested.get()}",
        )
        maybeCompleteWirelessHandoff()
    }

    private fun maybeCompleteWirelessHandoff() {
        if (!wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
        if (!wirelessActiveReported.compareAndSet(false, true)) return
        // A bring-up that got all the way through is proof the next one starts from a clean budget.
        wirelessAuthenticationAttempts.set(0)
        wirelessRestartLimiter.reset()
        val generation = wirelessGeneration.get()
        Thread(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@Thread
                }
                debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                closeBluetoothBootstrapTransport()
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@Thread
                }
                // The channel is reported as the endpoint named it and nothing is derived from it.
                // This used to log a band and two different radio treatments, and both branches then
                // ran the same call: the spec's per-band handling (§23.2.5.5) is not implemented
                // anywhere in this build, so a log must not be read as evidence that it is. The band
                // that matters is already on the bring-up line, from the hotspot's own reading.
                debugLog(
                    "wireless handoff: AirPlay endpoint channel=" +
                        (wirelessAirPlayEndpoint?.channel?.toString() ?: "unknown"),
                )
                // RFCOMM is already down. Quiet the USB adapter only after a handoff that stuck:
                // a failed attempt leaves scan on, or the iPhone cannot be paired again.
                idleAdapterRadio()
                onStatus(CarPlayStatus.WirelessActive)
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                Thread(
                    {
                        if (
                            closed ||
                            phase != Phase.WIRELESS ||
                            generation != wirelessGeneration.get() ||
                            wirelessActiveReported.get()
                        ) {
                            return@Thread
                        }
                        closeWirelessStack()
                        fail(IOException("Wireless CarPlay handoff timed out waiting for tunnel iAP2"))
                    },
                    "xcertplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun idleAdapterRadio() {
        val link = bluetoothLink
        bluetoothLink = null
        closeBestEffort("adapter radio idle") {
            link?.idleAfterSession()
            // The car hop does not own the USB adapter. If that radio is still up, quiet it too.
            // A second call on the same adapter is ignored once the first one has taken the hold.
            adapterBluetooth?.idleAfterSession()
        }
    }

    private fun closeBluetoothBootstrapTransport() {
        val activeCsm = csm
        csm = null
        if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }
    }

    private fun startIphone() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.IPHONE
        reenumerationAttempts = 0
        // A new search gets a fresh verdict. The detach counters are not reset here: this runs on a
        // detach too, and forgetting the cycle then would be forgetting the thing being counted.
        wiredEmptyPolls = 0
        lastWiredFinding = null
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val bus = iphoneHost.busSnapshot(config.ch341Devices)
        val device = bus.usable
        if (device == null) {
            wiredEmptyPolls += 1
            publishWiredDiagnosis(bus)
            onStatus(CarPlayStatus.WaitingForIphone)
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            wiredEmptyPolls = 0
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice) {
        if (closed) return
        try {
            when (val request = iphoneHost.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                // The system broadcast and the polling fallback can both observe the grant.
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                permissionPollGeneration++
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        val carPlay = IphoneCarPlayConfiguration.find(result.device)
                        debugLog(
                            if (carPlay != null) {
                                "wired usb/config present id=${carPlay.id} " +
                                    IphoneCarPlayConfiguration.describe(carPlay) +
                                    " " + describeAppleUsbDevice(result.device)
                            } else {
                                "wired usb/config absent " + describeAppleUsbDevice(result.device)
                            },
                        )
                        if (carPlay != null) {
                            openDataPaths(result.device)
                        } else if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {
                            beginReenumeration(result.device)
                        } else {
                            fail(
                                IphoneUsbException.Protocol(
                                    "iPhone did not expose a complete CarPlay USB configuration",
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed(CarPlayFailureReason.IPHONE_USB_PERMISSION_DENIED))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(CarPlayFailureReason.IPHONE_USB_PERMISSION_TIMEOUT),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun beginReenumeration(device: UsbDevice) {
        phase = Phase.REENUMERATION
        // Supersedes any search that was still pending, and any watchdog an earlier attempt left.
        availabilityPollGeneration.incrementAndGet()
        reenumerationAttempts += 1
        debugLog(
            "wired usb/reenum attempt=$reenumerationAttempts/$MAXIMUM_REENUMERATION_ATTEMPTS " +
                describeAppleUsbDevice(device),
        )
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                    armReenumerationWatchdog()
                }
                is IphoneUsbHost.TransitionResult.Failed -> fail(transition.error)
            }
        }
    }

    /**
     * The phone leaves the bus during a re-enumeration by design, so an empty bus is the state this
     * rung waits in rather than a fault on its own — only a bus that *stays* empty is. There is no
     * event for "it never came back", hence a deadline, and one long enough that a slow re-enumeration
     * is not read as a cable that was pulled.
     *
     * Expiry fails rather than searching again: [startIphone] resets [reenumerationAttempts], so
     * restarting from here would let a phone that never exposes the configuration be re-enumerated
     * forever instead of failing once the limit in [onIphonePermission] is reached. The attach that
     * ends this rung bumps the generation and disarms this.
     */
    private fun armReenumerationWatchdog() {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (closed || phase != Phase.REENUMERATION) return@postDelayed
                if (generation != availabilityPollGeneration.get()) return@postDelayed
                fail(
                    IphoneUsbException.DeviceUnavailable(
                        "The iPhone did not come back after the CarPlay configuration request",
                    ),
                )
            },
            REENUMERATION_WATCHDOG_MILLIS,
        )
    }

    private fun onIphoneAttached(device: UsbDevice) {
        // The returning half of a re-plug. The attach that ends a re-enumeration is not one: nothing
        // armed a detach for it, and an unarmed reattach is ignored.
        WiredLinkCycles.shared.noteReattach(System.nanoTime())
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> {
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
        publishWiredDiagnosis(iphoneHost.busSnapshot(config.ch341Devices))
    }

    /**
     * A detach is not always a fault. [beginReenumeration] asks the phone to re-enumerate, so the
     * phone leaves the bus on every connection that works. Only a phase where nothing asked it to
     * leave is a loss — and only those count towards the re-plug cycles, which is what keeps a good
     * connection from looking like a loose plug.
     */
    private fun onIphoneDetached(device: UsbDevice) {
        if (closed) return
        debugLog("wired usb/detach " + describeAppleUsbDevice(device))
        when (phase) {
            Phase.REENUMERATION -> Unit
            Phase.IPHONE -> {
                WiredLinkCycles.shared.noteDetach(System.nanoTime())
                // The device bring-up was waiting on is gone, and a permission poll already running
                // would otherwise wait out its whole timeout for a phone that is no longer there.
                startIphone()
            }
            Phase.DATAPATHS, Phase.CONTROL -> {
                WiredLinkCycles.shared.noteDetach(System.nanoTime())
                // A session was up. The read that is about to fail would blame the USB layer; name the
                // cable instead. Reconnecting is the existing failure path's job.
                fail(IphoneUsbException.DeviceUnavailable("The iPhone was unplugged"))
            }
            Phase.IDLE, Phase.MFI, Phase.WIRELESS -> Unit
        }
    }

    /**
     * Runs the wired-link assessment for where bring-up is now, and writes the verdict to the
     * handshake log when it changes.
     *
     * A debug line rather than a status, because it is a diagnosis and not a step: [onStatus]
     * deduplicates on equality, so a diagnosis pushed through it would either be swallowed as a
     * repeat or restated on every poll as the counts behind it moved.
     */
    private fun publishWiredDiagnosis(bus: UsbBusSnapshot) {
        if (closed || config.transport != CarPlayTransport.WIRED) return
        val report = WiredLinkAssessment.assess(
            WiredLinkObservation(
                wired = true,
                step = wiredStep(),
                emptyPolls = wiredEmptyPolls,
                deviceCount = bus.devices,
                detachCycles = WiredLinkCycles.shared.cycles(),
                sessionRan = WiredLinkCycles.shared.sessionRan(),
            ),
        )
        if (report.finding == lastWiredFinding) return
        lastWiredFinding = report.finding
        // A cleared finding is reported too: the screen turned the last one into an action, and that
        // action has to go away with the fault. The log line below stays quiet when there is nothing to say.
        try {
            uiListener?.onWiredLinkReport(report)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "wired link report callback failed", error)
        }
        if (!report.worthReporting) return
        if (report.finding == WiredLinkFinding.LOOSE_CONTACT || report.finding == WiredLinkFinding.SUPPLY_DIP) {
            // Said once, then let go: the rounds that proved it are finished, so leaving them counted
            // would restate this verdict on every later attach, including after a deliberate unplug.
            WiredLinkCycles.shared.noteReported()
        }
        debugLog(report.logLine())
    }

    /** Which rung the assessment is told about. Only the search and configuration rungs publish. */
    private fun wiredStep(): WiredLinkStep = when (phase) {
        Phase.IDLE, Phase.MFI, Phase.IPHONE -> WiredLinkStep.SEARCHING
        Phase.WIRELESS -> WiredLinkStep.NOT_WIRED
        Phase.REENUMERATION -> WiredLinkStep.CARPLAY_CONFIGURATION
        Phase.DATAPATHS -> WiredLinkStep.DATA_PATHS
        Phase.CONTROL -> WiredLinkStep.CONTROL
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice) {
        phase = Phase.DATAPATHS
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    try {
                        val ncm = openNcm(device)
                        runStack(result.session, ncm)
                    } catch (error: Throwable) {
                        result.session.close()
                        fail(error)
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> fail(result.error)
            }
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val configuration = IphoneCarPlayConfiguration.find(device)
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/${IphoneCarPlayConfiguration.alternateSetting(function.control)}" +
                " data=${function.data.id}/${IphoneCarPlayConfiguration.alternateSetting(function.data)}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
        )
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        return NcmUsbBridge.open(
            connection,
            function,
            airPlaySessionLive = { activeSession != null },
        )
    }

    private fun runStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge) {
        phase = Phase.CONTROL
        var ncmOwnedLocally = true
        try {
            if (closed) return
            val mux = Iap2UsbMuxHost.open(usbSession)
            this.mux = mux
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                // Two failures a fresh record can answer, and one attempt at each is all either gets. The
                // HostID lives in the pair record, so clearing it is what makes the next attempt present a
                // different host to a phone that will not accept this one. A second refusal is rethrown:
                // repeating it would only write the same failure into the log again and delay the driver.
                val hostRejected = isInvalidHostId(error)
                if (!isInvalidPairRecord(error) && !hostRejected) throw error
                debugLog(
                    if (hostRejected) {
                        "wired the iPhone rejected this accessory's HostID; pairing again"
                    } else {
                        "saved Lockdown pair record rejected; pairing again"
                    },
                )
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            // One write per iAP2 send. Per-packet traces and the 256-byte certificate
            // diagnosis writes used to run here on every playback frame.
            val csm = Iap2Session.open(
                carkit,
                traceContext = "wired",
                onTrace = ::debugLog,
            )
            this.csm = csm
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            if (!attachVpn(ncm, ncmHostMac)) {
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/VPN AirPlay transport")
            }
            ncmOwnedLocally = false
            debugLog("wired NCM/VPN AirPlay transport attached")
            if (closed) {
                vpnService?.detach()
                return
            }

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            // The port the listener actually bound, not the one that was asked for. The iPhone dials
            // whatever this message names, so a fallback the phone is not told about would leave the
            // listener up on a port nothing connects to — which reads as a hang rather than as the
            // bind error it is. Read back for the same reason the wireless path does.
            val wiredAirPlayPort = vpnService?.boundPort() ?: airPlayConfig.port
            if (wiredAirPlayPort != airPlayConfig.port) {
                debugLog("wired AirPlay port ${airPlayConfig.port} is in use; advertising $wiredAirPlayPort")
            }
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = wiredAirPlayPort,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            // The phone asks its user before it accepts NCM traffic, so from here a failed bulk OUT
            // write is that question rather than a fault.
            ncm.awaitCarPlayAuthorization()
            // From here the phone is drawing current, which is what makes a supply dip something the
            // cycles can be evidence of rather than a guess.
            WiredLinkCycles.shared.noteSessionRan()
            debugLog("wired iAP2 control starting")
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                timeoutMillis = controlLoopTimeoutMillis(),
                locationProvider = locationProvider,
                vehicleStatusProvider = vehicleStatusProvider,
                onIncoming = ::onRouteFrame,
                onProgress = { message -> debugLog("wired $message") },
            )
            onStatus(
                when (result.terminal) {
                    Iap2WiredControlTerminal.TIMED_OUT -> CarPlayStatus.ControlEnded
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed(CarPlayFailureReason.CONTROL_CHANNEL_CLOSED)
                },
            )
        } catch (error: Throwable) {
            debugLog("wired bring-up failed", error)
            if (!ncmOwnedLocally) vpnService?.detach()
            fail(error)
        } finally {
            if (ncmOwnedLocally) ncm.close()
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun isInvalidPairRecord(error: Throwable): Boolean = reportsLockdownError(error, "InvalidPairRecord")

    /**
     * True when a Lockdown request came back refusing this accessory's host identity.
     *
     * The phone answers `StartSession` with `Error = InvalidHostID`, which the client raises as text, so it is
     * read off the message exactly the way the pair-record code is.
     */
    private fun isInvalidHostId(error: Throwable): Boolean = reportsLockdownError(error, "InvalidHostID")

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    /**
     * §18.4's name, asked for before anything is started.
     *
     * Only the car's own radio is asked. The USB adapter path answers through its own
     * [Iap2BluetoothLink.localName], which does not exist until the link does, and that path already
     * falls back to the configured name instead of refusing; a gate here would reject a run the later
     * read would have accepted. For the car hop the answer is exactly what the identification is built
     * from, so this cannot refuse a run the later read would allow either.
     *
     * The cost it avoids is the whole front half of the bring-up: hotspot switched on, AirPlay listener
     * bound, mDNS 5353 taken, RFCOMM opened, and only then a name that was never going to arrive —
     * reported, misleadingly, as a data-link failure rather than an identity one.
     *
     * Asked more than once before it refuses, because the answer arrives through a service bind whose
     * callback is dispatched on the main thread: a main thread busy with the app's own start-up turns a
     * readable name into null for that call and for no other reason. Waiting was what this check used to
     * get from being late — the hotspot and the advertisement took tens of seconds before it was asked —
     * and taking that away without a retry would trade a slow failure for a wrong one.
     */
    private fun requireCarHopRadioName() {
        if (config.wirelessBluetoothHop != WirelessBluetoothHop.CAR) return
        val hop = CarBluetoothHops.active ?: throw WirelessIdentityResolver.missingNameException(
            WirelessBluetoothHop.CAR,
        )
        repeat(RADIO_NAME_ATTEMPTS) { attempt ->
            val name = hop.localName(appContext)?.trim()?.takeIf { it.isNotEmpty() }
            if (name != null) {
                debugLog("wireless radio name (before the hotspot) =$name attempt=${attempt + 1}")
                return
            }
            if (attempt + 1 < RADIO_NAME_ATTEMPTS) {
                try {
                    Thread.sleep(RADIO_NAME_RETRY_MILLIS)
                } catch (interrupted: InterruptedException) {
                    // The run is being wound down: stop asking rather than hold the thread a while longer.
                    Thread.currentThread().interrupt()
                    throw WirelessIdentityResolver.missingNameException(WirelessBluetoothHop.CAR)
                }
            }
        }
        throw WirelessIdentityResolver.missingNameException(WirelessBluetoothHop.CAR)
    }

    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        val hotspotMode = effectiveHotspotMode(config.wirelessHotspotMode, Build.VERSION.SDK_INT)
        if (hotspotMode == WirelessHotspotMode.MANUAL) {
            val started = CarHotspotController(appContext)
                .ensureEnabled(CAR_HOTSPOT_ENABLE_TIMEOUT_MILLIS)
            if (started != HotspotStartResult.READY) {
                throw IOException(
                    "The car hotspot is off and DiPlay could not switch it on: ${started.reason}. " +
                        "Turn it on in the car settings and connect again.",
                )
            }
        }
        if (hotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
            // One radio cannot be a station and an access point at once, so the car's own hotspot has
            // to go before the car can join the driver's network. This is the only place DiPlay
            // switches a hotspot off, and it never switches one on.
            val hotspotWas = CarHotspotStatus.isEnabled(appContext)
            val refused = CarHotspotController(appContext).releaseForStationMode()
            if (refused != null) {
                throw CarHotspotStaysOnException(
                    "The car hotspot is on and DiPlay could not switch it off: $refused. " +
                        "Turn it off in the car settings and connect again.",
                )
            }
            debugLog(
                "wireless car hotspot " + when (hotspotWas) {
                    true -> "switched off"
                    false -> "already off"
                    // The same unreadable answer that let releaseForStationMode() return without
                    // trying — and "without trying" is what has to be said. Calling it "already off"
                    // reports a failed reflection as a fact about the hotspot; calling it "switched
                    // off" claims a request that was never sent. Both would send the driver looking
                    // for a network problem while the hotspot is still holding the radio.
                    null -> "state unreadable, left as it is"
                } + "; the car will join the driver's Wi-Fi network instead",
            )
        }
        val manager: WirelessHotspotManager = when (hotspotMode) {
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createLocalOnlyHotspotManager()
                else throw IOException("Local-only hotspot mode requires Android 8 or newer")
            }
            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw IOException("Manual hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
                onDiagnostic = ::debugLog,
            )
            WirelessHotspotMode.EXISTING_WIFI -> {
                // Not reachable through the settings page, which does not offer this mode below
                // Android 5; reachable by a stored setting carried over from a newer platform.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    throw IOException("Joining an existing Wi-Fi network requires Android 5 or newer")
                }
                ExistingWifiManager(
                    context = appContext,
                    onDiagnostic = ::debugLog,
                    // The network the whole session is built on went away or moved: the addresses in
                    // the messages already sent are stale, so the bring-up starts over rather than
                    // sitting on a link the phone can no longer reach.
                    onNetworkChanged = { if (!closed && phase == Phase.WIRELESS) restartWireless() },
                    // Where the car's own copy is not readable, these are the passwords the driver
                    // typed, one per network. Matched on the name inside the manager.
                    driverCredentials = config.existingWifiCredentials,
                )
            }
        }
        hotspot = manager
        return try {
            manager.start(HOTSPOT_START_TIMEOUT_MILLIS)
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun createLocalOnlyHotspotManager(): WirelessHotspotManager = LocalOnlyHotspotManager(appContext, ::debugLog)

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()

    /**
     * Closes what a superseded run opened for itself.
     *
     * [closeWirelessStack] releases the *shared* fields, which by the time a losing run notices it lost
     * belong to the newer run — so it can close neither this run's Bonjour nor its RFCOMM stream. A
     * Bonjour left behind keeps mDNS 5353 bound, and a bound mDNS socket rejects every later bind with
     * EADDRINUSE, which ends discovery for the rest of the process.
     */
    private fun releaseOwnWirelessResources(
        ownBonjour: CarPlayBonjour?,
        ownStream: BlockingDuplexByteStream?,
    ) {
        if (ownBonjour != null) {
            if (bonjour === ownBonjour) bonjour = null
            closeBestEffort("superseded Bonjour") { ownBonjour.close() }
        }
        if (ownStream != null) {
            if (bluetoothStream === ownStream) bluetoothStream = null
            closeBestEffort("superseded Bluetooth link") { ownStream.close() }
        }
    }

    /**
     * Ends a session the control conversation walked away from, and lets go of what it was holding.
     *
     * Reporting the end and stopping there left the hotspot switched on, mDNS 5353 bound, the AirPlay
     * listener open and RFCOMM held for as long as the app lived: nothing after this point closes them,
     * and the screen has already been told the session is over, so nothing ever asks again. The
     * teardown runs on its own thread for the same reason [onFirstTcpTimedOut] does — it closes sockets
     * out from under the control loop that is calling it — and re-checks the generation first, because
     * a newer run may already own the shared fields by the time this thread gets to them.
     *
     * What it must not do is end a session that is still being used. This control loop's deadline says
     * only that *it* went quiet; a phone that streams without ever asking for the Bluetooth handoff
     * leaves the same loop running until the deadline while the Wi-Fi tunnel carries the screen, and
     * [wirelessActiveReported] stays false the whole time because it is set by the handoff request.
     * Tearing down on that evidence would switch the hotspot off under a session that is working. So
     * the stack is released only when nothing is left holding it: no AirPlay session, and no tunnel
     * that has finished its iAP2 handshake. Anything else is reported and left alone — the paths that
     * do own a session close these themselves when it ends.
     */
    private fun tearDownAtControlEnded(generation: Int) {
        onStatus(CarPlayStatus.ControlEnded)
        if (activeSession != null || wirelessTunnelReady.get()) {
            debugLog("wireless control window ended while a session is still up; keeping the link")
            return
        }
        Thread(
            {
                if (isStaleWirelessRun(generation)) return@Thread
                debugLog("wireless control window ended; releasing the hotspot, Bonjour and the link")
                closeWirelessStack()
            },
            "xcertplay-wireless-control-ended",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun closeWirelessStack(service: CarPlayVpnService? = vpnService) {
        val diagnostics = wirelessDiagnostics
        wirelessDiagnostics = null
        diagnostics?.close()
        wirelessConnectionProof.clear()
        wirelessPublishedAddress = null
        wirelessAlternateAddress = null
        wirelessAddressRecovery = null
        wirelessHotspotInterface = null
        media.setIapTunnelHandler(null)
        val activeTunnel = wirelessTunnelChannel
        wirelessTunnelChannel = null
        if (activeTunnel != null) closeBestEffort("tunneled iAP2 link") { activeTunnel.close() }

        bluetoothLink = null
        // A session ending does not hand the car's own Bluetooth back any more: the hop keeps its patched
        // process in place for as long as the app is running and gives it up when the app closes. A hop
        // that acts at session end rather than at app exit still gets its chance here, and nothing waits
        // on it — the failure paths out of the wireless run reach this too.
        closeBestEffort("car hop session end") { CarBluetoothHops.active?.idleAfterSession(appContext) }
        closeBluetoothBootstrapTransport()

        val activeBonjour = bonjour
        bonjour = null
        if (activeBonjour != null) closeBestEffort("Bonjour") { activeBonjour.close() }

        val activeHotspot = hotspot
        hotspot = null
        if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
        wirelessIdentification = null
        wirelessAirPlayEndpoint = null
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)

        if (service != null) closeBestEffort("AirPlay service") { service.detach() }
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("LocalOnlyHotspot host address is unavailable")
        }
        return text
    }

    /**
     * The addresses the published interface has right now.
     *
     * Re-read rather than remembered, because a group is reported ready before all of its addresses are
     * configured: the one that is missing at creation is often the one worth trying next. Only when the
     * interface answers with nothing at all does the snapshot stand in for it.
     */
    private fun hotspotCandidates(
        info: WirelessHotspotInfo,
        fallbackToSnapshot: Boolean = true,
    ): List<InetAddress> {
        val name = info.interfaceName ?: return listOfNotNull(info.hostAddress)
        val scanned = try {
            NetworkInterface.getByName(name)?.inetAddresses?.toList().orEmpty()
        } catch (error: Exception) {
            debugLog("wireless interface scan failed iface=$name error=${error.javaClass.simpleName}")
            emptyList()
        }
        return if (scanned.isEmpty() && fallbackToSnapshot) listOfNotNull(info.hostAddress) else scanned
    }

    /**
     * Records that this run's address family is not the one to use next time.
     *
     * Only called where the phone never reached us: a run that connected and then dropped says nothing
     * about whether the address was reachable, and flipping on that would send the next attempt down a
     * road the last one had already disproved. The other family has to actually exist on the interface
     * for the flip to be worth anything, which is what [HotspotAddressRecovery.nextFamily] checks.
     */
    private fun flipWirelessAddressFamily() {
        val published = wirelessPublishedAddress
        val next = published?.let { wirelessAddressRecovery?.nextFamily(it) }
        val from = published?.let {
            if (it is Inet6Address) HotspotAddressPolicy.FAMILY_IPV6 else HotspotAddressPolicy.FAMILY_IPV4
        }
        if (next == null || from == null) {
            debugLog(
                "wireless address family flip skipped published=${from ?: "none"} " +
                    "alternate=${next != null}",
            )
            return
        }
        debugLog("wireless address family flip published=$from next=$next")
        onWirelessAddressFamilyUnusable(next)
    }

    /**
     * The phone never opened the AirPlay port inside the window it was given.
     *
     * The timeout arrives on the main looper (it is a `postDelayed`), and none of what follows is
     * main-thread work: the flip re-reads the interface, and the teardown closes sockets out from under a
     * blocking iAP2 control loop. Both go to their own thread, the same way
     * [armWirelessHandoffWatchdog] does it.
     *
     * The flip has to come before [closeWirelessStack] in that order — the teardown clears the published
     * address and the recovery that reads it, so a flip that ran after it would have nothing to flip and
     * would silently record nothing.
     */
    private fun onFirstTcpTimedOut(generation: Int) {
        if (closed || isStaleWirelessRun(generation) || wirelessActiveReported.get()) return
        Thread(
            {
                if (closed || isStaleWirelessRun(generation) || wirelessActiveReported.get()) return@Thread
                // Both are read before anything is closed: the teardown clears the published address
                // and the recovery that reads it, and the access point's name is what says where to
                // look for the phone.
                val ssid = wirelessAirPlayEndpoint?.ssid
                val interfaceName = wirelessHotspotInterface
                val peers = ipv4PeersOnAccessPoint()
                if (peers != null && peers.isEmpty()) {
                    // Nobody ever resolved an address on this access point, so the address this
                    // receiver published was never in play: moving it to the other family would
                    // change the next attempt in a way that cannot help, and would spend the family
                    // budget learning the same thing again.
                    closeWirelessStack()
                    fail(
                        WirelessPhoneNotOnNetworkException(
                            "no device joined ${ssid ?: "the access point"} " +
                                "(${interfaceName ?: "unknown interface"}) within " +
                                "${WirelessStartupPolicy.FIRST_TCP_MILLIS} ms of CarPlay StartSession: " +
                                "no IPv4 peer was ever resolved on it, so the phone either did not " +
                                "join the network or never got an address from it",
                        ),
                    )
                    return@Thread
                }
                flipWirelessAddressFamily()
                closeWirelessStack()
                fail(
                    FirstTcpTimeoutException(
                        "no AirPlay TCP within ${WirelessStartupPolicy.FIRST_TCP_MILLIS} ms of CarPlay StartSession",
                    ),
                )
            },
            "xcertplay-wireless-first-tcp-timeout",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Who the access point has heard from, or null where that cannot be read.
     *
     * The kernel keeps IPv6 neighbours somewhere `/proc` cannot see, so an IPv6 session is never
     * judged by this table: the address family is exactly what such a run is already unsure about,
     * and "the phone never joined" read off evidence that cannot exist would replace one wrong
     * answer with another. A null answer — no interface recorded, no readable table, a published
     * address that is not IPv4 — is the answer that leaves the old behaviour intact.
     */
    private fun ipv4PeersOnAccessPoint(): Set<String>? {
        if (wirelessPublishedAddress !is Inet4Address) return null
        val interfaceName = wirelessHotspotInterface ?: return null
        val peers = arpPeersOn(interfaceName) ?: return null
        debugLog("wireless first TCP timeout: iface=$interfaceName ipv4Peers=${peers.size}")
        return peers
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        try {
            close()
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        }
    }

    private fun controlLoopTimeoutMillis(): Long = when {
        config.transport == CarPlayTransport.WIRED -> Iap2WiredControlClient.NO_TIMEOUT_MILLIS
        config.locationReportingEnabled -> LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS
        else -> CONTROL_LOOP_TIMEOUT_MILLIS
    }

    private fun attachVpn(ncm: NcmUsbBridge, hostMac: ByteArray): Boolean {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService() ?: run {
            debugLog("wired VPN service bind failed")
            ncm.close()
            return false
        }
        debugLog("wired VPN service bound; attaching NCM transport")
        val result = try {
            service.attach(
                ncm = ncm,
                linkLocal = config.linkLocal,
                hostMac = hostMac,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiSession?.client,
                listener = sessionListener,
                media = media,
            )
        } catch (error: Throwable) {
            ncm.close()
            onStatus(CarPlayStatus.Failed(CarPlayFailureReason.BRING_UP_FAILED, error.message ?: error.javaClass.simpleName))
            return false
        }
        return when (result) {
            CarPlayVpnService.AttachResult.Started -> {
                debugLog("wired VPN/NCM transport attach result=started")
                true
            }
            CarPlayVpnService.AttachResult.AlreadyStarted -> {
                debugLog("wired VPN/NCM transport attach result=already-started")
                ncm.close()
                false
            }
            is CarPlayVpnService.AttachResult.Failed -> {
                debugLog("wired VPN/NCM transport attach result=failed ${result.message}")
                ncm.close()
                onStatus(CarPlayStatus.Failed(CarPlayFailureReason.TRANSPORT_ATTACH_FAILED, result.message))
                false
            }
        }
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        vpnBound = true
        try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() {
        if (!vpnBound) return
        vpnBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        vpnService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable, detachCloseable, ch341PermissionCloseable).forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Receiver is already unregistered.
            }
        }
        permissionCloseable = null
        attachCloseable = null
        detachCloseable = null
        ch341PermissionCloseable = null
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun fail(error: Throwable) {
        if (closed) return
        onStatus(
            CarPlayStatus.Failed(
                failureReasonFor(error),
                error.message ?: error.javaClass.simpleName,
            ),
        )
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            uiListener?.onDebugLog(
                "$message: ${error.message ?: error.javaClass.simpleName}",
            )
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    private fun onStatus(status: CarPlayStatus) {
        if (closed) return
        mainHandler.post {
            if (!closed && status != lastReportedStatus) {
                lastReportedStatus = status
                val message = status.debugLogMessage()
                // The STEP line says which rung was reached; the mark says how long the climb to it
                // took. A report with only the first cannot tell a phone that was slow to answer from
                // a head unit that was slow to ask.
                val stepMs = PerfTrace.mark("connect", perfStepName(message))
                noteConnectionCost(status, message, stepMs)
                uiListener?.onDebugLog(message)
                uiStatusReporter?.invoke(status)
            }
        }
    }

    /** `STEP usb/config: ...` becomes `connect/usb/config`: the stable half of the line, for the report. */
    private fun perfStepName(message: String): String =
        if (message.startsWith("STEP ")) "connect/" + message.removePrefix("STEP ").substringBefore(':')
        else "connect/error"

    /**
     * One line when the ladder ends: how long it took, and which rung held it up.
     *
     * The report's marks section says the same thing in more detail, but a report has to be generated
     * and carried off the unit, while `diplay.log` is already there. Written through [DiagLog] rather
     * than the UI listener so it reaches that file without adding a row to the handshake list.
     *
     * A retry inside the same controller restarts the clock at its first STEP, so a ladder that ran
     * twice does not report the sum of both.
     */
    private fun noteConnectionCost(status: CarPlayStatus, message: String, stepMs: Long) {
        val now = System.nanoTime()
        if (message.startsWith("STEP ")) {
            if (firstStepAtNs == 0L) {
                firstStepAtNs = now
                slowestStep = ""
                slowestStepMs = 0L
            }
            if (stepMs > slowestStepMs) {
                slowestStepMs = stepMs
                slowestStep = message.removePrefix("STEP ").substringBefore(':')
            }
        }
        val outcome = when (status) {
            CarPlayStatus.RunningControl, CarPlayStatus.WirelessActive -> "up"
            is CarPlayStatus.Failed -> "failed"
            else -> return
        }
        if (firstStepAtNs == 0L) return
        val totalMs = (now - firstStepAtNs) / 1_000_000L
        DiagLog.i(
            "DiPlay-Perf",
            "connect $outcome in ${totalMs}ms; " +
                "slowest=${slowestStep.ifEmpty { "none" }}+${slowestStepMs}ms",
        )
        firstStepAtNs = 0L
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: MFi coprocessor not present; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting CH341 USB access"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $reason" + (detail?.let { ": $it" } ?: "")
    }

    companion object {
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val CAR_HOTSPOT_ENABLE_TIMEOUT_MILLIS = 15_000L
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2

        /**
         * How many times the radio is asked for its name before a run is refused.
         *
         * The answer comes back through a service bind dispatched on the main thread, so one null is
         * not evidence of anything at app start-up. Three asks is one service call plus two retries —
         * a few seconds against a bring-up that would otherwise run for a minute and fail anyway.
         */
        private const val RADIO_NAME_ATTEMPTS = 3
        private const val RADIO_NAME_RETRY_MILLIS = 700L

        /**
         * How many whole wireless bring-ups a phone that never answers the certificate gets before the
         * failure is reported. Each one runs the bring-up from the top again.
         */
        private const val MAXIMUM_WIRELESS_AUTHENTICATION_ATTEMPTS = 3

        /**
         * How long the phone is given to come back in its CarPlay shape. A real re-enumeration takes
         * about a second, so this is loose enough not to fire on a slow one and tight enough to free
         * the rung when the cable is out.
         */
        private const val REENUMERATION_WATCHDOG_MILLIS = 10_000L
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
    }
}
