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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.BitSet
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
    /**
     * Every password the driver has saved, one per network, for the network the car is on.
     *
     * Used only where the platform would not hand the car's own copy over, and the one used is the one
     * whose name matches the network the head unit actually joined — which is not necessarily the one
     * that was saved last, because the car is not always on the same network. See
     * [ExistingWifiDriverCredentials] for what a saved entry is.
     */
    private val driverCredentials: List<ExistingWifiDriverCredentials> = emptyList(),
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

    /**
     * Whether anything other than the router ever turned up on the network the car joined.
     *
     * Null until [attach] has pinned a baseline, which is the whole of a session that never attached.
     * Read only by [connectionDiagnosticSnapshot], and it decides nothing: see [WirelessLanPeers].
     */
    @Volatile private var lanPeers: WirelessLanPeers? = null

    /**
     * What the last look at the platform was still missing.
     *
     * Set as the wait goes along rather than only at the end: the timeout message names it, and each
     * change is also logged, so a run that never gets there still says how far it got. Empty until the
     * first look, which is what makes that first look log.
     */
    @Volatile private var waitingFor = ""
    @Volatile private var multipleNetworksDetectedAt: Long? = null

    /**
     * Why the last look for the saved configuration came up empty, for the message that ends the run.
     *
     * The three ways it comes up empty — this app not being allowed to read the list, the list not
     * holding the network the car is on, and the list being empty — all end the same way, and telling
     * them apart is the whole difference between "grant the permission" and "join the network in the
     * car's own settings".
     */
    @Volatile private var savedDetailsMissing = "the head unit would not say what they are"

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
        // One wait, one grace. This instance is built per bring-up, so the field cannot outlive the
        // attempt it measures today — clearing it here says so, and keeps it true if the manager is
        // ever reused for a second bring-up.
        multipleNetworksDetectedAt = null
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
                "DiPlay cannot read the saved details of Wi-Fi network '${attached.ssid}': " +
                    "$savedDetailsMissing. Without its security type the phone cannot be told " +
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
        // The peer baseline is pinned here, at the moment this session takes the link over, and not at
        // the first sample: that first sample is ten seconds later, and a phone that arrived inside
        // those ten seconds would be recorded as having been on the network all along — which is
        // exactly the question the field exists to answer. Pinned before it is published, so the
        // observer can never see the object without its baseline. The read sits after the check above
        // rather than inside the window that check closes: nothing may slow that check down.
        val peers = WirelessLanPeers(attached.interfaceName, defaultGateway(attached.properties))
        peers.pin()
        lanPeers = peers
        val password = existingWifiPassword(credentials.passphrase, driverCredentials, attached.ssid)
        // Two lines, and the split is the whole point of them. The report's redactor drops a line that
        // carries a credential keyword — whole, not field by field — and both `ssid` and `pass…` are on
        // its list. One line carrying both the network's name and whether the platform handed the key
        // over arrives in the report as `[withheld: credential or device name]` and answers nothing.
        // The name goes on the line that gets dropped: it is a device name and the report is not where
        // it belongs, and `adb logcat` has it in full. What the driver has to read — whether this head
        // unit gives up the key at all, and who supplied one when it did not — goes on a line with no
        // keyword on it, so it survives.
        onDiagnostic("Existing Wi-Fi attached ssid=${attached.ssid}")
        onDiagnostic(
            "Existing Wi-Fi details iface=${attached.interfaceName} host=${attached.host.hostAddress} " +
                "credentials=${if (password.value == null) "absent" else "present"} " +
                "from=${password.source} " +
                "channel=$channel security=${credentials.security}",
        )
        return WirelessHotspotInfo(
            ssid = attached.ssid,
            passphrase = password.value,
            security = credentials.security,
            channel = channel,
            frequencyMHz = frequencyMHz,
            // The router's BSSID is not this receiver's AirPlay device identity.
            bssid = null,
            interfaceName = attached.interfaceName,
            hostAddress = attached.host,
            bandLabel = bandLabel(frequencyMHz),
            backend = WirelessHotspotBackend.EXISTING_WIFI,
            // Both families the router handed out, so the phone is told about each of them rather than
            // only about the one this side happened to prefer.
            hostAddresses = attached.addresses,
            // Read from the live connection, never typed by the driver: this is the access point the
            // car is actually on, and it is a hint about somebody else's hardware.
            accessPointBssid = accessPointAddress(attached.info?.bssid),
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
            val now = System.currentTimeMillis()
            val firstSeen = multipleNetworksDetectedAt
            if (firstSeen == null) {
                multipleNetworksDetectedAt = now
                return waitFor("multiple Wi-Fi networks to settle (${networks.size} detected)")
            } else if (now - firstSeen < MULTIPLE_NETWORKS_GRACE_MILLIS) {
                return waitFor("multiple Wi-Fi networks to settle (${networks.size} detected)")
            } else {
                throw IOException(
                    "The car is on ${networks.size} Wi-Fi networks at once; leave it on the one " +
                        "the iPhone is on and connect again",
                )
            }
        }
        multipleNetworksDetectedAt = null
        val network = networks.singleOrNull() ?: return waitFor("a Wi-Fi connection")
        val properties = connectivity.getLinkProperties(network) ?: return waitFor("the Wi-Fi interface")
        val name = properties.interfaceName ?: return waitFor("the Wi-Fi interface")
        val iface = runCatching { NetworkInterface.getByName(name) }.getOrNull()
            ?: return waitFor("the Wi-Fi interface")
        val addresses = existingWifiHostAddresses(properties.linkAddresses.map { it.address }, iface.index)
        val host = addresses.firstOrNull() ?: return waitFor("an address on the Wi-Fi network")
        val info = stationInfo()
        val ssid = readableSsid(info) ?: return waitFor("the network's name")
        return Attachment(network, properties, name, iface.index, host, addresses, ssid, info)
    }

    private fun waitFor(what: String): Attachment? {
        // Said when it changes, not on every turn: the wait runs for up to a minute at five turns a
        // second, and what a report needs is the order it went through — a connection, then an
        // interface, then an address, then a name — not five hundred copies of the last one.
        if (waitingFor != what) {
            waitingFor = what
            onDiagnostic("Existing Wi-Fi waiting for $what")
        }
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
        val networkId = info?.networkId ?: -1
        val configurations = try {
            wifi.configuredNetworks
        } catch (_: SecurityException) {
            savedDetailsMissing = "this app is not allowed to read the head unit's saved networks"
            return null
        }
        val credentials = existingWifiCredentials(configurations, ssid, networkId)
        if (credentials == null) {
            // The helper is the one place that decides a saved network cannot be used; this says
            // which of the two reasons it was. WEP is named rather than counted, because the driver
            // can act on it — it is a network that has to change, not a lookup that came up empty.
            val saved = configurations?.firstOrNull { networkId >= 0 && it.networkId == networkId }
                ?: configurations?.firstOrNull { unquoted(it.SSID) == ssid }
            savedDetailsMissing = if (saved != null && isWepConfiguration(saved)) {
                "network uses WEP which is insecure and not supported by CarPlay"
            } else {
                // Counted, not named: the name is on the screen and in the message around this, and what
                // the reader has to tell apart here is a list that is empty from a list that is not.
                "none of the ${configurations?.size ?: 0} saved networks is the one the car is on " +
                    "(its id is $networkId)"
            }
        }
        return credentials
    }

    private fun bandLabel(frequencyMHz: Int?): String = when {
        frequencyMHz == null -> "Auto"
        frequencyMHz < 2500 -> "2.4 GHz"
        frequencyMHz < 5955 -> "5 GHz"
        else -> "6 GHz"
    }

    /**
     * The router's address on the link the attachment carries, or null when the platform names none.
     *
     * Taken from the link properties already in hand rather than looked up: this runs in the middle of
     * attaching, and nothing here may go and ask the platform for anything new. The default route on
     * the network the car has joined is the router by definition, and only its IPv4 form is taken,
     * because IPv4 is the family the neighbour table this is used against counts in.
     */
    private fun defaultGateway(properties: LinkProperties): String? =
        properties.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress

    /**
     * The access point's address, or null when there is nothing worth telling the phone.
     *
     * The phone acts on whatever it is given here, so the value is only passed on when it looks like a
     * real access point: a malformed or unknown address, an all-zero one, the `02:00:00:00:00:00`
     * placeholder some frameworks answer with, and a group/multicast address are all dropped rather
     * than sent as a hint that would send the phone looking for hardware that is not there.
     */
    private fun accessPointAddress(text: String?): ByteArray? {
        if (text == null || !text.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) return null
        val bytes = text.split(':').map { it.toInt(16).toByte() }.toByteArray()
        if (bytes.all { it == 0.toByte() } || bytes[0].toInt() and 1 != 0 ||
            text.equals("02:00:00:00:00:00", ignoreCase = true)
        ) {
            return null
        }
        return bytes
    }

    private fun invalidate(reason: String) {
        if (!closed && invalidated.compareAndSet(false, true)) {
            onDiagnostic("Existing Wi-Fi $reason; restarting wireless session")
            onNetworkChanged()
        }
    }

    override fun connectionDiagnosticSnapshot(): String =
        "existingWifiLink=${if (closed || invalidated.get()) "lost" else "attached"}" +
            // The peer count rides on this line instead of one of its own. This is the one string the
            // session's periodic observer asks this backend for, and that observer keeps only the first
            // four lines of what it samples — the kernel receive counters already fill the rest — so a
            // line of its own would be dropped before it ever reached the report.
            (lanPeers?.let { " " + it.snapshot() } ?: "")

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
        /**
         * Every usable address on the interface, [host] first.
         *
         * The network is somebody else's and may hand out both families; the phone is told about all of
         * them because which one it can reach is not something this side gets to decide.
         */
        val addresses: List<InetAddress>,
        val ssid: String,
        val info: WifiInfo?,
    )

    private companion object {
        const val POLL_MILLIS = 200L
        const val MULTIPLE_NETWORKS_GRACE_MILLIS = 600L
    }
}

/** What the head unit saved for the network it is on: the two values 0x5703 and 0x4301 both carry. */
internal data class ExistingWifiCredentials(
    val passphrase: String?,
    val security: Iap2WirelessSecurity,
)

/**
 * The password the driver typed for one network the car goes on.
 *
 * Carried as a pair because that is what it is. A platform that will not hand the car's key over is
 * the reason this exists, and the phone cannot join a secured network without one — so the driver is
 * given somewhere to type it. The name travels with it so that it is used for the network it was
 * typed for and no other, which is also what lets one be kept per network: every pair the driver has
 * saved is carried together, and the one that is used is the one whose name matches the network the
 * head unit is actually on.
 */
data class ExistingWifiDriverCredentials(
    val ssid: String,
    val passphrase: String,
)

/** The password that goes into 0x5703 and 0x4301, and which of the two copies it came from. */
internal data class ExistingWifiPassword(val value: String?, val source: String)

/**
 * Whether two network names are the same network.
 *
 * Public because storage in the `common` module keys its list by this same question, and two answers
 * to "is this the same network" is how a saved password comes to be filed under a name nothing looks
 * it up by.
 */
fun sameWifiNetworkName(left: String, right: String): Boolean =
    left == right || left.trim().equals(right.trim(), ignoreCase = true)

/**
 * Which copy of the password the phone is told to use.
 *
 * The car's own leads: it is the one the head unit joined the network with, so it is the one that is
 * known to work. The driver's is what covers the head unit that will not hand its copy over — and it
 * is used only when its network name matches, because a password belongs to one network and half of
 * that pair is a wrong password rather than a missing one. An empty typed value counts as none: it is
 * what a field left blank looks like, and it is not a password.
 *
 * [driver] is every password the driver has saved, one per network, and which of them belongs to this
 * connection is answered by [existingWifiPassphraseFor]; the matching rule lives there and not here.
 *
 * [source] is carried out with the value because the page cannot see this decision and a report has
 * to: "absent" on its own cannot tell a head unit that refuses apart from a driver who never typed one.
 */
internal fun existingWifiPassword(
    fromCar: String?,
    driver: List<ExistingWifiDriverCredentials>,
    ssid: String,
): ExistingWifiPassword {
    if (fromCar != null) return ExistingWifiPassword(fromCar, "car")
    val typed = existingWifiPassphraseFor(driver, ssid)?.takeIf { it.isNotEmpty() }
    return ExistingWifiPassword(typed, if (typed != null) "driver" else "nowhere")
}

/**
 * The password saved for the network with this name, or null when none is.
 *
 * An exact name wins outright, and only then is the tolerant comparison asked. A network's name is
 * case-sensitive on the wire and case-preserving everywhere it is stored, but a driver reads it off a
 * head unit's screen and types it back, and `MyCarWiFi` typed where the car reports `mycarwifi` is the
 * same network with a different capitalisation — discarding the password over that hands the phone a
 * secured network with nothing to join it with.
 *
 * The fallback takes an entry only when it is the only one: two saved networks whose names differ only
 * in case cannot be told apart from the name the head unit reports, and guessing between two passwords
 * is a wrong password rather than a missing one. Both are dropped, and the session goes on without a
 * driver's copy — which is what it would have done had neither been saved.
 */
fun existingWifiPassphraseFor(
    stored: List<ExistingWifiDriverCredentials>,
    ssid: String,
): String? {
    stored.firstOrNull { it.ssid == ssid }?.let { return it.passphrase }
    return stored.filter { sameWifiNetworkName(it.ssid, ssid) }.singleOrNull()?.passphrase
}

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
    if (isWepConfiguration(saved)) return null
    // Some platforms answer with a mask instead of nothing when they will not hand the key over.
    val passphrase = unquoted(saved.preSharedKey)
        ?.takeIf { it.isNotEmpty() && it != REDACTED_PASSWORD }
    return ExistingWifiCredentials(
        passphrase = passphrase,
        security = existingWifiSecurity(saved.allowedKeyManagement),
    )
}

internal fun isWepConfiguration(config: WifiConfiguration): Boolean {
    val hasWepKeys = config.wepKeys?.any { !it.isNullOrEmpty() && it != "\"\"" } == true
    val isSharedAuth = config.allowedAuthAlgorithms?.get(WifiConfiguration.AuthAlgorithm.SHARED) == true
    return hasWepKeys || isSharedAuth
}

/**
 * The security type the saved key-management mask describes, which is the one the phone is told to
 * join with.
 *
 * The phone acts on this value, so a wrong one is a wrong network rather than a wrong detail: it is
 * the field the iPhone uses to decide how to associate, and a WPA3 network announced as
 * WPA2-Personal is a join that fails. The mask is the only thing the head unit keeps that says which
 * of them the network is.
 *
 * An unknown or empty mask stays [Iap2WirelessSecurity.WPA_WPA2], which is where every secured
 * network landed before this function existed, and only a mask that is [WifiConfiguration.KeyMgmt.NONE]
 * and nothing else is read as open: unknown is not open, and reading a mask this code does not
 * recognise as one would invite the phone onto a network it has no password for.
 *
 * WPA3-Personal in transition mode is stored as the SAE bit together with a PSK bit, and SAE on its
 * own is WPA3-only — so the two are told apart by what sits beside SAE, not by SAE itself. `OWE`
 * ("Enhanced Open") is a network with no password that is still not a plain open one; iAP2 has no
 * value for it, and [Iap2WirelessSecurity.NONE] is the only one that says what the phone actually has
 * to do, which is associate with nothing typed in.
 *
 * WEP, SUITE_B_192, DPP, FILS_* and WAPI_* are deliberately left on the WPA_WPA2 fallback: the masks
 * handled here are not a list of every mask the platform can hand out, and a reader who takes this for
 * one would go looking for the branch that was never meant to be here.
 */
internal fun existingWifiSecurity(keyManagement: BitSet?): Iap2WirelessSecurity {
    if (keyManagement == null) return Iap2WirelessSecurity.WPA_WPA2
    val bits = keyManagement.cardinality()
    if (bits == 0) return Iap2WirelessSecurity.WPA_WPA2
    val single = bits == 1
    if (single && keyManagement.get(WifiConfiguration.KeyMgmt.NONE)) return Iap2WirelessSecurity.NONE
    if (single && keyManagement.get(KEY_MGMT_OWE)) return Iap2WirelessSecurity.NONE
    if (!keyManagement.get(KEY_MGMT_SAE)) return Iap2WirelessSecurity.WPA_WPA2
    val psk = keyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) ||
        keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
    return if (psk) Iap2WirelessSecurity.WPA3_TRANSITION else Iap2WirelessSecurity.WPA3_ONLY
}

// The API 29 values of WifiConfiguration.KeyMgmt.SAE and .OWE, named as literals so API 19 still loads.
private const val KEY_MGMT_SAE = 8
private const val KEY_MGMT_OWE = 9

/** WifiConfiguration quotes what it stores; the wire format does not. */
private fun unquoted(value: String?): String? = value?.removeSurrounding("\"")

private const val REDACTED_PASSWORD = "*"
