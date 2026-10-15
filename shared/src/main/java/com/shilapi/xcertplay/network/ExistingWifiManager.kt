package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Attaches to a Wi-Fi network the head unit is already on.
 *
 * The other three backends make an access point and hand its credentials to the phone. This one makes
 * no network at all: the driver joins an existing one — an Android phone's hotspot, a router, or the
 * iPhone's own personal hotspot — in the car's own Wi-Fi settings, and the iPhone is put on that same
 * network. Nothing here switches Wi-Fi on, joins a network, or changes routing; it reads what the
 * platform already has and hands that to the session.
 *
 * The driver types nothing in, here or anywhere else. The network's name comes from the live
 * connection and its security type and password come from the configuration the head unit saved when
 * the driver connected. Where the platform will not show the password, the session goes on without
 * it: the phone is being told which network to join, and it is already on that network.
 *
 * Needs Android 5, and no more: `ConnectivityManager`'s per-network view arrived in API 21, and every
 * call below stays within it. The head unit this fork targets is API 22, so it is comfortably inside
 * — but the project's floor is API 19, which is why the class carries the annotation and the mode is
 * only offered where it can run.
 *
 * The upstream version of this class reaches for `getSystemService(Class)` (API 23) and would die
 * with a `NoSuchMethodError` on that head unit; that is the one thing this port could not copy.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class ExistingWifiManager(
    context: Context,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNetworkChanged: () -> Unit = {},
) : WirelessHotspotManager {
    private val appContext = context.applicationContext ?: context
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw IllegalStateException("ConnectivityManager is unavailable")
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val lock = Any()
    private val invalidated = AtomicBoolean()

    @Volatile private var closed = false
    @Volatile private var selected: Network? = null
    @Volatile private var host: InetAddress? = null
    @Volatile private var interfaceName: String? = null
    private var interfaceIndex = 0
    private var callbackRegistered = false

    /** What the last look at the platform was still missing, for the message a timeout ends with. */
    @Volatile private var waitingFor = "a Wi-Fi connection"

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (network == selected) invalidate("network lost")
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (network == selected && !sameLink(properties)) invalidate("interface or address changed")
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ExistingWifiManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        check(selected == null) { "ExistingWifiManager has already started" }
        val started = System.nanoTime()
        while (!closed) {
            val attached = readAttachment()
            if (attached != null) return attach(attached)
            if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                throw IOException(
                    "The car has no usable Wi-Fi network: DiPlay is still waiting for $waitingFor. " +
                        "Join the same Wi-Fi as the iPhone in the car's Wi-Fi settings and connect again.",
                )
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw IOException("Joining the car's Wi-Fi network was cancelled")
    }

    /**
     * Reads the connection and takes it over, or throws when the platform's answer cannot be acted
     * on. Throwing here means the state is one no amount of waiting changes — several networks at
     * once, or a network whose security type the platform will not show.
     */
    private fun attach(attached: Attachment): WirelessHotspotInfo {
        val credentials = savedCredentials(attached.ssid, attached.info)
            ?: throw IOException(
                "The head unit will not show DiPlay the saved details of Wi-Fi network " +
                    "'${attached.ssid}', and without its security type the phone cannot be told " +
                    "which network to join.",
            )
        val frequencyMHz = attached.info?.frequency?.takeIf { it > 0 }
        val channel = frequencyMHz?.let(::wifiFrequencyMhzToChannel) ?: 0
        synchronized(lock) {
            check(!closed) { "ExistingWifiManager is closed" }
            selected = attached.network
            host = attached.host
            interfaceName = attached.interfaceName
            interfaceIndex = attached.interfaceIndex
            connectivity.registerNetworkCallback(
                requestWithoutDefaultCapabilities()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                callback,
            )
            callbackRegistered = true
        }
        // Close the gap between reading the link and registering the callback.
        val current = connectivity.getLinkProperties(attached.network)
        if (current == null || !sameLink(current) || invalidated.get()) {
            throw IOException("The Wi-Fi connection changed while DiPlay was reading it; connect again")
        }
        // Two lines, and the split is the whole point of them. The report's redactor drops a line that
        // carries a credential keyword — whole, not field by field — and both `ssid` and `pass…` are on
        // its list. One line carrying both the network's name and whether the platform handed the key
        // over arrives in the report as `[withheld: credential or device name]` and answers nothing.
        // The name goes on the line that gets dropped: it is a device name and the report is not where
        // it belongs, and `adb logcat` has it in full. What the driver has to read — whether this head
        // unit gives up the key at all — goes on a line with no keyword on it, so it survives.
        onDiagnostic("Existing Wi-Fi attached ssid=${attached.ssid}")
        onDiagnostic(
            "Existing Wi-Fi details iface=${attached.interfaceName} host=${attached.host.hostAddress} " +
                "credentials=${if (credentials.passphrase == null) "absent" else "present"} " +
                "channel=$channel security=${credentials.security}",
        )
        return WirelessHotspotInfo(
            ssid = attached.ssid,
            passphrase = credentials.passphrase,
            security = credentials.security,
            channel = channel,
            frequencyMHz = frequencyMHz,
            // The router's BSSID is not this receiver's AirPlay device identity.
            bssid = null,
            interfaceName = attached.interfaceName,
            hostAddress = attached.host,
            bandLabel = bandLabel(frequencyMHz),
            backend = WirelessHotspotBackend.EXISTING_WIFI,
        )
    }

    /**
     * The live connection, or null while there is not yet one to read.
     *
     * Every part is required. A network with no interface, no address or no readable name cannot
     * carry an iAP2 message, and waiting for it to grow one is the same wait as waiting for the
     * driver to join the network — so the timeout at the end names the part that never arrived.
     */
    private fun readAttachment(): Attachment? {
        // INTERNET/VALIDATED and the default route do not identify the CarPlay LAN, so the filter is
        // on transport alone. VPN is excluded because it is a second transport on the same network.
        val networks = connectivity.allNetworks.filter { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
        if (networks.size > 1) {
            throw IOException(
                "The car is on ${networks.size} Wi-Fi networks at once; leave it on the one " +
                    "the iPhone is on and connect again",
            )
        }
        val network = networks.singleOrNull() ?: return waitFor("a Wi-Fi connection")
        val properties = connectivity.getLinkProperties(network) ?: return waitFor("the Wi-Fi interface")
        val name = properties.interfaceName ?: return waitFor("the Wi-Fi interface")
        val iface = runCatching { NetworkInterface.getByName(name) }.getOrNull()
            ?: return waitFor("the Wi-Fi interface")
        val addresses = existingWifiHostAddresses(properties.linkAddresses.map { it.address }, iface.index)
        val host = addresses.firstOrNull() ?: return waitFor("an address on the Wi-Fi network")
        val info = stationInfo()
        val ssid = readableSsid(info) ?: return waitFor("the network's name")
        return Attachment(network, properties, name, iface.index, host, ssid, info)
    }

    private fun waitFor(what: String): Attachment? {
        waitingFor = what
        return null
    }

    private fun sameLink(properties: LinkProperties): Boolean =
        properties.interfaceName == interfaceName && properties.linkAddresses.any { it.address == host }

    @Suppress("DEPRECATION")
    private fun stationInfo(): WifiInfo? = try {
        wifi.connectionInfo
    } catch (_: SecurityException) {
        null
    }

    private fun readableSsid(info: WifiInfo?): String? = info?.ssid
        ?.removeSurrounding("\"")
        ?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }

    @Suppress("DEPRECATION")
    private fun savedCredentials(ssid: String, info: WifiInfo?): ExistingWifiCredentials? {
        val configurations = try {
            wifi.configuredNetworks
        } catch (_: SecurityException) {
            return null
        }
        return existingWifiCredentials(configurations, ssid, info?.networkId ?: -1)
    }

    private fun bandLabel(frequencyMHz: Int?): String = when {
        frequencyMHz == null -> "Auto"
        frequencyMHz < 2500 -> "2.4 GHz"
        frequencyMHz < 5955 -> "5 GHz"
        else -> "6 GHz"
    }

    private fun invalidate(reason: String) {
        if (!closed && invalidated.compareAndSet(false, true)) {
            onDiagnostic("Existing Wi-Fi $reason; restarting wireless session")
            onNetworkChanged()
        }
    }

    override fun connectionDiagnosticSnapshot(): String =
        "existingWifiLink=${if (closed || invalidated.get()) "lost" else "attached"}"

    override fun close() {
        synchronized(lock) {
            closed = true
            if (callbackRegistered) {
                connectivity.unregisterNetworkCallback(callback)
                callbackRegistered = false
            }
        }
    }

    // NetworkRequest.Builder.clearCapabilities needs API 30; before that, drop the defaults one by one.
    private fun requestWithoutDefaultCapabilities(): NetworkRequest.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            NetworkRequest.Builder().clearCapabilities()
        } else {
            NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
        }

    private data class Attachment(
        val network: Network,
        val properties: LinkProperties,
        val interfaceName: String,
        val interfaceIndex: Int,
        val host: InetAddress,
        val ssid: String,
        val info: WifiInfo?,
    )

    private companion object {
        const val POLL_MILLIS = 200L
    }
}

/** What the head unit saved for the network it is on: the two values 0x5703 and 0x4301 both carry. */
internal data class ExistingWifiCredentials(
    val passphrase: String?,
    val security: Iap2WirelessSecurity,
)

/**
 * The security type and password of a network, from the configurations the head unit saved when the
 * driver connected it.
 *
 * Null when no saved configuration matches, and the caller refuses rather than guesses: both values
 * end up in a message the phone acts on, and a wrong security type is a wrong network.
 *
 * A null [ExistingWifiCredentials.passphrase] is not a failure. Platforms from Android 6 onwards
 * strip the key out of what they hand an app — the ordinary case — and the session goes on without
 * it, which is what the iAP2 side turns into an omitted parameter rather than a wrong value.
 *
 * The live [networkId] is tried first: two networks can carry the same name, and the one the head
 * unit is actually on is the one whose details belong in the message.
 */
@Suppress("DEPRECATION")
internal fun existingWifiCredentials(
    configurations: List<WifiConfiguration>?,
    ssid: String,
    networkId: Int,
): ExistingWifiCredentials? {
    val saved = configurations?.firstOrNull { networkId >= 0 && it.networkId == networkId }
        ?: configurations?.firstOrNull { unquoted(it.SSID) == ssid }
        ?: return null
    // Some platforms answer with a mask instead of nothing when they will not hand the key over.
    val passphrase = unquoted(saved.preSharedKey)
        ?.takeIf { it.isNotEmpty() && it != REDACTED_PASSWORD }
    val keyManagement = saved.allowedKeyManagement
    val open = keyManagement != null &&
        keyManagement.cardinality() == 1 &&
        keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
    return ExistingWifiCredentials(
        passphrase = passphrase,
        security = if (open) Iap2WirelessSecurity.NONE else Iap2WirelessSecurity.WPA_WPA2,
    )
}

/** WifiConfiguration quotes what it stores; the wire format does not. */
private fun unquoted(value: String?): String? = value?.removeSurrounding("\"")

private const val REDACTED_PASSWORD = "*"
