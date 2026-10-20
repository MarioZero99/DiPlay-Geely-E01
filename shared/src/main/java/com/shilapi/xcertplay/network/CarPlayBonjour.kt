package com.shilapi.xcertplay.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.DiagLog
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

data class CarPlayBonjourEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int,
    val bluetoothId: String?,
)

sealed interface CarPlayBonjourEvent {
    data class Discovery(val stage: Stage, val ipv4Count: Int = 0, val ipv6Count: Int = 0) : CarPlayBonjourEvent {
        enum class Stage { ADDED, NO_MATCHING_ADDRESS, INVALID_PORT }
    }
    data class Resolved(val endpoint: CarPlayBonjourEndpoint) : CarPlayBonjourEvent

    data class Probed(
        val endpoint: CarPlayBonjourEndpoint,
        val attempts: Int,
        val statusLine: String?,
        val error: IOException?,
    ) : CarPlayBonjourEvent
}

/** Saved reports need discovery outcomes without phone names, addresses, or pairing identifiers. */
fun CarPlayBonjourEvent.diagnosticSummary(): String = when (this) {
    is CarPlayBonjourEvent.Discovery -> "control discovery stage=$stage ipv4=$ipv4Count ipv6=$ipv6Count"
    is CarPlayBonjourEvent.Resolved ->
        "control resolved family=${if (':' in endpoint.host) "IPv6" else "IPv4"} port=${endpoint.port}"
    is CarPlayBonjourEvent.Probed -> {
        val status = statusLine?.let { Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})(?: |$)").find(it)?.groupValues?.get(1) }
        "control probe attempts=$attempts status=${status ?: "none"} error=${error?.javaClass?.simpleName ?: "none"}"
    }
}

/** Pure protocol values shared by the Android runtime and JVM tests. */
object CarPlayBonjourProtocol {
    /**
     * The feature bits as Bonjour spells them: the low 32 bits, then the high ones when there are any.
     *
     * Takes the value rather than a config so that the caller has to take it from the same place
     * `/info` does — see [AirPlayInfoPlist.features].
     */
    internal fun featuresTxt(features: Long): String {
        val low = "0x${(features and 0xffffffffL).toString(16)}"
        val high = features ushr 32
        return if (high == 0L) low else "$low,0x${high.toString(16)}"
    }

    fun airPlayTxtRecords(
        config: AirPlayConfig,
        identity: AirPlayIdentity,
    ): Map<String, String> = linkedMapOf(
        "deviceid" to config.deviceId,
        "features" to featuresTxt(AirPlayInfoPlist.features(config)),
        "flags" to "0x4",
        "model" to config.model,
        "srcvers" to config.sourceVersion,
        "protovers" to "1.1",
        "pi" to identity.pairingId,
        "pk" to identity.publicKeyHex,
    )

    fun connectProbeRequest(
        host: String,
        port: Int,
        sourceVersion: String,
        deviceId: String,
    ): String {
        val unbracketedHost = host.removeSurrounding("[", "]").substringBefore('%')
        require(unbracketedHost.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion must not be empty" }
        require('\r' !in sourceVersion && '\n' !in sourceVersion) {
            "sourceVersion must not contain a line break"
        }
        require('\r' !in unbracketedHost && '\n' !in unbracketedHost) {
            "host must not contain a line break"
        }
        val receiverDeviceId = deviceId.replace(":", "")
        require(receiverDeviceId.isNotEmpty()) { "deviceId must contain a hexadecimal value" }
        require('\r' !in receiverDeviceId && '\n' !in receiverDeviceId) {
            "deviceId must not contain a line break"
        }
        val hostHeader = if (':' in unbracketedHost) {
            "[$unbracketedHost]:$port"
        } else {
            "$unbracketedHost:$port"
        }
        return "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
            "Host: $hostHeader\r\n" +
            "User-Agent: AirPlay/$sourceVersion\r\n" +
            "AirPlay-Receiver-Device-ID: $receiverDeviceId\r\n" +
            "Connection: close\r\n" +
            "\r\n"
    }
}

/**
 * Parses the host the AirPlay record is published under.
 *
 * A link-local IPv6 arrives here with its interface attached — `fe80::1%wlan0`, or `%3` when the
 * address was built from a numeric index — which is both how the platform spells one and what JmDNS
 * needs to join the right multicast group. The bare literal is what iAP2 wants, and that stripping
 * happens on the iAP2 side rather than here, because the two are different requirements.
 *
 * Either IPv6 form is accepted. A global address needs no scope and is what an interface handed only a
 * global prefix will have; a link-local one is unusable without the scope this keeps.
 */
internal fun advertisedHostAddress(advertisedHost: String?): InetAddress? {
    val value = advertisedHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val address = try {
        InetAddress.getByName(value.removeSurrounding("[", "]"))
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid advertised host: $value", error)
    }
    require(!address.isLoopbackAddress) {
        "advertisedHost must not be a loopback address"
    }
    // Link-local and global IPv6 both belong here. Refusing the second cost the whole advertisement
    // rather than one address: the constructor throws, the caller carries on without mDNS, and an
    // address the interface really has ends up published nowhere — which on an interface that only
    // holds a global IPv6 is the same as publishing nothing at all. What has to be excluded is the
    // thing that is not a host on this interface: a multicast group, or the unspecified address.
    require(!address.isMulticastAddress && !address.isAnyLocalAddress) {
        "advertisedHost must be a unicast address of this interface"
    }
    return address
}

/**
 * Binds every address it can, keeping the families that worked when one family's bind is refused.
 *
 * KitKat's `MulticastSocket` binds 5353 before enabling `SO_REUSEADDR`, so the second address family is
 * refused with `EADDRINUSE` even though no other process owns the port — the refusal is ours, not
 * someone else's. Without this the first family takes the port, the second throws, and the caller's
 * teardown closes both: `mdnsFamilies=none`, no advertisement at all, which on this head unit is what
 * every wireless run logged before 2026-10-20. One family advertised is worth more than none, and the
 * address the phone dials comes from 0x4301 either way.
 *
 * Taken from the parent repository (`programmerguohuajing/DiPlay-Legacy-Android`), which fixed this
 * for Android 4.x; the grandparent keeps the unguarded loop because it ships to Android 8 and above,
 * where the platform enables `SO_REUSEADDR` before binding.
 */
internal fun <T> bindAvailableMdnsFamilies(
    addresses: List<InetAddress>,
    bind: (InetAddress) -> T,
    onFailure: (InetAddress, IOException) -> Unit,
): List<Pair<InetAddress, T>> {
    val bound = mutableListOf<Pair<InetAddress, T>>()
    for (address in addresses) {
        try {
            bound += address to bind(address)
        } catch (error: IOException) {
            onFailure(address, error)
        }
    }
    return bound
}

/**
 * Publishes the accessory AirPlay service and discovers the iPhone's CarPlay control service.
 *
 * The NSD callbacks only enqueue work. Resolution, probing, and [onEvent] all run on the worker
 * started by [start], so a blocking consumer callback never runs on the caller or main thread.
 */
class CarPlayBonjour(
    context: Context,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val advertisedHost: String? = null,
    private val useInterfaceMdns: Boolean = false,
    private val onEvent: (CarPlayBonjourEvent) -> Unit = {},
    /**
     * The rest of the selected interface's addresses, published alongside [advertisedHost].
     *
     * A JmDNS instance joins only its own address family's multicast group, so serving both families
     * takes one instance per address — and an address the phone can reach over TCP but cannot find over
     * Bonjour is an address it will not use. Empty where the interface has only one, which is every
     * wired run.
     */
    additionalAddresses: List<InetAddress> = emptyList(),
) : Closeable {
    // Interface-bound mDNS does not need Android's NSD service, which may be absent on some head units.
    private val nsdManager: NsdManager by lazy {
        (context.applicationContext ?: context)
            .getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: throw IOException("Android NSD service is unavailable")
    }

    private val services = LinkedBlockingQueue<NsdServiceInfo>()
    private val interfaceServices = LinkedBlockingQueue<Pair<CarPlayBonjourEndpoint, InetAddress>>()
    private val discoveryEvents = LinkedBlockingQueue<CarPlayBonjourEvent.Discovery>(32)
    private val seenServices = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleLock = Any()
    private val localAdvertisedAddress = advertisedHostAddress(advertisedHost)
    private val advertisedAddresses =
        (listOfNotNull(localAdvertisedAddress) + additionalAddresses).distinct()
    @Volatile
    private var publishedFamilies = "none"

    /**
     * Whether the bundled responder is the discovery path this run actually got.
     *
     * Starts false and only [start] sets it, because which path ran is the registration's answer and not
     * the caller's wish: the caller says the bundled responder may be used (`useInterfaceMdns`), NSD is
     * asked first regardless, and this flag says whether the fallback ended up being taken. A value that
     * began as the caller's preference would send the worker down the bundled path while NSD was the one
     * publishing.
     */
    @Volatile
    private var interfaceMdnsActive = false

    /** Whether the system NSD service published the record this run, which is the normal answer. */
    @Volatile
    private var nsdAvailable = false

    /** The records this run published, so [releaseInterfaceMdns] gives back these and no one else's. */
    private val publishedRecords = mutableListOf<ServiceInfo>()
    private val addedCount = AtomicInteger()
    private val resolvedCount = AtomicInteger()
    private val addressMismatchCount = AtomicInteger()
    private val probeCount = AtomicInteger()
    private val successfulProbeCount = AtomicInteger()
    private val lastProbe = AtomicReference("not_started")
    private val multicastLock = (context.applicationContext ?: context)
        .getSystemService(Context.WIFI_SERVICE).let { it as WifiManager }
        .createMulticastLock("carplay-bonjour").apply { setReferenceCounted(false) }

    private var started = false
    @Volatile
    private var closed = false

    /**
     * Guards [close]'s body, which is not the same thing as [closed].
     *
     * A start that failed marks the object closed so nothing else can be published through it — but it
     * may still be holding the multicast lock, a bound mDNS socket and a worker thread. Letting [closed]
     * short-circuit [close] left those to the garbage collector while the port stayed taken.
     */
    private var released = false
    private var registrationRequested = false

    /**
     * The outcome of the NSD registration, so [start] can tell a service that got published from one the
     * platform refused. Registration is asynchronous and its callback is the only word on it; without
     * this, "NSD first, bundled mDNS as the fallback" would fall back on a failure it could not see.
     */
    @Volatile
    private var registrationResult = REGISTRATION_PENDING
    private val registrationLatch = CountDownLatch(1)

    /** Set when the run stops waiting for NSD, so a callback that arrives afterwards changes nothing. */
    @Volatile
    private var registrationAbandoned = false
    private var discoveryRequested = false
    @Volatile
    private var worker: Thread? = null
    @Volatile
    private var activeSocket: Socket? = null

    private val interfaceListener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            if (!closed) {
                discoveryEvents.offer(CarPlayBonjourEvent.Discovery(CarPlayBonjourEvent.Discovery.Stage.ADDED))
                event.dns.requestServiceInfo(event.type, event.name, true)
            }
        }

        override fun serviceRemoved(event: ServiceEvent) {
            seenServices.remove("${event.name}|${event.dns.inetAddress is Inet4Address}")
        }

        override fun serviceResolved(event: ServiceEvent) {
            if (closed) return
            val info = event.info
            // The family comes from the instance that heard it, not from the local advertised address:
            // with one instance per address, that is the only thing that says which family this
            // resolution belongs to. Keep the HTTP probe in the same family as its bound source.
            val address = info.inetAddresses.firstOrNull {
                (it is Inet4Address) == (event.dns.inetAddress is Inet4Address)
            }?.let(::applyLocalScope)
            if (address == null || info.port !in 1..65535) {
                discoveryEvents.offer(CarPlayBonjourEvent.Discovery(
                    if (address == null) CarPlayBonjourEvent.Discovery.Stage.NO_MATCHING_ADDRESS
                    else CarPlayBonjourEvent.Discovery.Stage.INVALID_PORT,
                    info.inetAddresses.count { it is Inet4Address }, info.inetAddresses.count { it is Inet6Address },
                ))
                return
            }
            // Keyed by family as well as name: one instance per address means the same service is heard
            // once per family, and a name-only key would let the first family suppress the second.
            if (!seenServices.add("${event.name}|${address is Inet4Address}")) return
            val endpoint = CarPlayBonjourEndpoint(
                event.name, address.hostAddress ?: return, info.port,
                info.getPropertyString("id"),
            )
            interfaceServices.offer(endpoint to address)
        }
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            // The fallback has already been taken and this registration given up: a late success must not
            // leave this run believing it holds a record it has handed back.
            if (registrationAbandoned) return
            registrationResult = REGISTRATION_OK
            registrationLatch.countDown()
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            DiagLog.w(TAG, "AirPlay NSD registration failed code=$errorCode")
            if (registrationAbandoned) return
            registrationResult = errorCode
            registrationLatch.countDown()
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            DiagLog.w(TAG, "AirPlay NSD unregistration failed code=$errorCode")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            DiagLog.w(TAG, "CarPlay control discovery failed code=$errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            DiagLog.w(TAG, "CarPlay control discovery stop failed code=$errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (closed) return
            val name = serviceInfo.serviceName ?: return
            val type = serviceInfo.serviceType ?: CARPLAY_CONTROL_SERVICE_TYPE
            val key = "$type|$name"
            if (!seenServices.add(key)) return
            services.offer(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName ?: return
            val type = serviceInfo.serviceType ?: CARPLAY_CONTROL_SERVICE_TYPE
            seenServices.remove("$type|$name")
        }
    }

    /** Starts publication and discovery. Calling this more than once is harmless. */
    /** Includes zero counts so a silent discovery interval is visible in exported reports. */
    fun diagnosticSnapshot(): String =
        "bonjourAdded=${addedCount.get()} bonjourResolved=${resolvedCount.get()} " +
            "bonjourAddressMismatch=${addressMismatchCount.get()} connectProbes=${probeCount.get()} " +
            "connectProbe2xx=${successfulProbeCount.get()} lastProbe=${lastProbe.get()} " +
            "mdnsFamilies=$publishedFamilies"

    fun start() {
        synchronized(lifecycleLock) {
            check(!closed) { "CarPlayBonjour is closed" }
            if (started) return
            started = true
            try {
                multicastLock.acquire()
                // The system's own responder first, and the bundled one only if it will not take the
                // service. 5353 has one owner on this head unit — the firmware's `mdnsd`, which sets
                // `SO_REUSEADDR` before binding — so a bundled socket bound first is refused outright
                // and the advertisement never goes out at all: every wireless report before 2026-10-20
                // reads `bonjourAdded=0`. The NSD service talks to that daemon instead of competing with
                // it, which is why it is asked first now that the port question is understood.
                nsdAvailable = runCatching {
                    registerAirPlay()
                    registrationRequested = true
                    // Bounded, and only to learn the answer: a platform that never calls back must not
                    // hold the bring-up, so a timeout counts as a refusal and the bundled path is tried.
                    registrationLatch.await(NSD_REGISTRATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) &&
                        registrationResult == REGISTRATION_OK
                }.getOrElse { error ->
                    DiagLog.w(TAG, "AirPlay NSD registration threw: ${error.message}")
                    false
                }
                if (nsdAvailable) {
                    publishedFamilies = "nsd"
                    nsdManager.discoverServices(
                        CARPLAY_CONTROL_SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        discoveryListener,
                    )
                    discoveryRequested = true
                } else {
                    // The daemon would not take it. The bundled responder is the last way to publish at
                    // all. Registration is given up before that, so a callback arriving late cannot leave
                    // this run claiming a record it has already unregistered.
                    if (registrationRequested) {
                        registrationRequested = false
                        registrationAbandoned = true
                        runCatching { nsdManager.unregisterService(registrationListener) }
                    }
                    // The same condition the bundled path always had, not a narrower one: with the
                    // caller asking for it, a missing advertised host is a configuration error the
                    // constructor has already refused, not a reason to publish nothing.
                    val mayUseBundled = useInterfaceMdns ||
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && localAdvertisedAddress != null)
                    if (mayUseBundled) {
                        requireNotNull(localAdvertisedAddress) {
                            "Interface mDNS requires a local advertised address"
                        }
                        DiagLog.w(TAG, "NSD did not publish the AirPlay record; falling back to bundled mDNS")
                        interfaceMdnsActive = startInterfaceMdns()
                    } else {
                        DiagLog.w(TAG, "no mDNS path published the AirPlay record; continuing without discovery")
                    }
                }
                worker = Thread(::runWorker, WORKER_NAME).apply {
                    isDaemon = true
                    start()
                }
            } catch (error: Exception) {
                closed = true
                if (registrationRequested) {
                    registrationRequested = false
                    runCatching { nsdManager.unregisterService(registrationListener) }
                }
                if (discoveryRequested) {
                    discoveryRequested = false
                    runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                }
                worker?.interrupt()
                worker = null
                releaseInterfaceMdns()
                publishedFamilies = "none"
                if (multicastLock.isHeld) multicastLock.release()
                throw error
            }
        }
    }

    /**
     * Publishes the AirPlay record and listens for the phone, taking 5353 for this run where it can.
     *
     * One instance per address, because a JmDNS instance joins only its own family's multicast group.
     * The port, though, is one for the whole process, and an earlier run may still be holding it: a
     * teardown that has not finished, a bring-up this one replaced, a session being handed over. Those
     * instances are taken down here, before ours are created, because a retry ladder waits for nobody —
     * and an advertisement that never appears reads exactly like a phone that never looked.
     *
     * A family that will not bind costs that family and nothing else. On this platform the second
     * family's bind is refused with `EADDRINUSE` by our own first socket, not by another process (see
     * [bindAvailableMdnsFamilies]), so letting the refusal escape would close the family that worked and
     * leave `mdnsFamilies=none`. Returns false only when no address bound at all, which is the caller's
     * signal to fall back to the system NSD service.
     *
     * Serialised on [interfaceMdnsLock] rather than [lifecycleLock]: the run being closed is not this
     * one, and it has a lifecycle lock of its own.
     */
    private fun startInterfaceMdns(): Boolean {
        synchronized(interfaceMdnsLock) {
            // The socket already bound for these addresses is borrowed, not rebuilt. Closing and
            // re-binding here is the exact race process-wide ownership exists to remove: the port comes
            // back a moment later, and in between any bind — ours included — is refused.
            val reusable = interfaceMdns
                .filter { dns -> interfaceAddresses[dns]?.let { it in advertisedAddresses } == true }
            val haveAddresses = interfaceAddresses
                .filterValues { address -> address in advertisedAddresses }
                .values
                .toSet()
            val missing = advertisedAddresses.filter { it !in haveAddresses }
            if (interfaceMdns.isNotEmpty() && missing.isEmpty()) {
                if (interfaceMdnsOwner === this) return true
                DiagLog.w(TAG, "mDNS $MDNS_PORT reused from the process; registering this run's record")
            } else if (interfaceMdns.isNotEmpty()) {
                DiagLog.w(TAG, "mDNS $MDNS_PORT kept for ${haveAddresses.size} address(es); binding ${missing.size} more")
            }
            interfaceMdnsOwner = this
            // Taken before the first service is registered: a registration that throws must leave every
            // socket this run opened on the owner's list, or the caller's teardown closes a port it
            // cannot see and the next run inherits the refusal.
            val bindings = bindAvailableMdnsFamilies(
                missing,
                bind = { address -> JmDNS.create(address, "carplay-${config.deviceId.replace(":", "")}") },
                onFailure = { address, failure ->
                    val family = if (address is Inet4Address) "IPv4" else "IPv6"
                    // Whoever holds 5353 decides the fix, and the kernel table is the only place that
                    // says so — on this platform the answer is usually our own earlier socket. The uid
                    // is reported without resolving it to a package: this class has no Context-backed
                    // resolver, and the caller logs the same table with names before this one runs.
                    DiagLog.w(
                        TAG,
                        "mDNS bind failed for $family on ${address.hostAddress}; preserving other " +
                            "advertised families: ${failure.message}; " +
                            "5353 held by ${PortHolders.udp(MDNS_PORT)}",
                    )
                },
            )
            interfaceMdns.addAll(bindings.map { it.second })
            bindings.forEach { (address, dns) -> interfaceAddresses[dns] = address }
            // Borrowed sockets carry this run's record too: they are already bound for an address this
            // run advertises, and a record registered only on the new ones would leave the reused family
            // publishing nothing at all.
            val publishing = reusable.map { dns -> interfaceAddresses.getValue(dns) to dns } + bindings
            val boundFamilies = mutableListOf<String>()
            for ((address, dns) in publishing) {
                dns.addServiceListener("$CARPLAY_CONTROL_SERVICE_TYPE.local.", interfaceListener)
                val info = ServiceInfo.create(
                    "$AIRPLAY_SERVICE_TYPE.local.", config.deviceName, config.port,
                    0, 0, CarPlayBonjourProtocol.airPlayTxtRecords(config, identity),
                )
                dns.registerService(info)
                // Kept beside the socket so this run gives back exactly the record it published, and
                // only while it is still the record: after a newer run publishes, this one owns nothing.
                publishedRecords += info
                boundFamilies += if (address is Inet4Address) "IPv4" else "IPv6"
            }
            publishedFamilies = if (boundFamilies.isEmpty()) "none" else boundFamilies.joinToString(",")
            return boundFamilies.isNotEmpty()
        }
    }

    /**
     * Gives this run's record and listener back and leaves the socket where it is.
     *
     * The socket is the process's, not this run's. Closing it here would hand the next attempt the
     * very `EADDRINUSE` the process-wide ownership exists to remove: a port released a moment ago can
     * still be refused while the kernel finishes with the last listener. The record, unlike the socket,
     * belongs to the run that published it, so it goes back here — and only if this run is still the
     * owner, because a run that was already replaced must not take the newer run's record down.
     */
    private fun releaseInterfaceMdns() {
        synchronized(interfaceMdnsLock) {
            if (interfaceMdnsOwner !== this) return
            interfaceMdnsOwner = null
            val records = publishedRecords.toList()
            publishedRecords.clear()
            // The sockets stay on the process's list: they are still bound, and clearing them here would
            // hide a live listener from the next run, which would then bind the same port again and be
            // refused for it. Only this run's record and listener come off.
            val sockets = interfaceMdns.toList()
            // The socket outlives the run, so this run's listener has to come off it: left attached, every
            // bring-up would add one more to the same socket and a later discovery would be reported by
            // runs that are already closed.
            sockets.forEach { dns ->
                runCatching { dns.removeServiceListener("$CARPLAY_CONTROL_SERVICE_TYPE.local.", interfaceListener) }
                records.forEach { record -> runCatching { dns.unregisterService(record) } }
            }
        }
    }

    override fun close() {
        val workerToJoin: Thread?
        synchronized(lifecycleLock) {
            if (released) return
            released = true
            closed = true
            if (registrationRequested) {
                registrationRequested = false
                runCatching { nsdManager.unregisterService(registrationListener) }
            }
            if (discoveryRequested) {
                discoveryRequested = false
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            }
            activeSocket?.let { socket -> runCatching { socket.close() } }
            activeSocket = null
            services.clear()
            interfaceServices.clear()
            discoveryEvents.clear()
            // 5353 goes back only if this run is the one holding it: a run that was already replaced
            // leaves the newer one's instances standing, since they are not this run's to close.
            releaseInterfaceMdns()
            publishedFamilies = "none"
            workerToJoin = worker
            worker = null
            workerToJoin?.interrupt()
            if (multicastLock.isHeld) multicastLock.release()
        }
        workerToJoin?.let(::joinWorker)
    }

    @Suppress("DEPRECATION")
    private fun registerAirPlay() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = config.deviceName
            serviceType = AIRPLAY_SERVICE_TYPE
            port = config.port
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CarPlayBonjourProtocol.airPlayTxtRecords(config, identity).forEach { (key, value) ->
                    setAttribute(key, value)
                }
            }
            localAdvertisedAddress?.let(::setHost)
        }
        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener,
        )
    }

    private fun runWorker() {
        while (!closed) {
            if (interfaceMdnsActive) {
                try {
                    while (true) emit(discoveryEvents.poll() ?: break)
                    val (endpoint, address) = interfaceServices.poll(
                        WORKER_POLL_MILLIS, TimeUnit.MILLISECONDS,
                    ) ?: continue
                    emit(CarPlayBonjourEvent.Resolved(endpoint))
                    probe(endpoint, address)?.let(::emit)
                } catch (_: InterruptedException) {
                    return
                } catch (error: Exception) {
                    if (!closed) DiagLog.w(TAG, "Interface CarPlay service handling failed", error)
                }
                continue
            }
            val service = try {
                services.poll(WORKER_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            } ?: continue
            if (closed) return
            try {
                handleService(service)
            } catch (_: InterruptedException) {
                return
            } catch (error: Exception) {
                if (!closed) DiagLog.w(TAG, "CarPlay control service handling failed", error)
            }
        }
    }

    private fun handleService(service: NsdServiceInfo) {
        val resolved = resolveWithRetry(service) ?: return
        val address = preferredAddress(resolved) ?: return
        val port = resolved.port
        if (port !in 1..65535) return
        val serviceName = resolved.serviceName ?: service.serviceName ?: return
        val host = address.hostAddress ?: return
        val bluetoothId = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) resolved.attributes else null)
            ?.get("id")
            ?.let(::decodeTxtValue)
            ?.takeIf { it.isNotBlank() }
        val endpoint = CarPlayBonjourEndpoint(
            serviceName = serviceName,
            host = host,
            port = port,
            bluetoothId = bluetoothId,
        )
        emit(CarPlayBonjourEvent.Resolved(endpoint))
        probe(endpoint, address)?.let(::emit)
    }

    @Suppress("DEPRECATION")
    private fun resolveWithRetry(service: NsdServiceInfo): NsdServiceInfo? {
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            if (closed) return null
            val latch = CountDownLatch(1)
            val resolved = AtomicReference<NsdServiceInfo?>()
            val failure = AtomicInteger(FAILURE_NONE)
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolved.set(serviceInfo)
                    latch.countDown()
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    failure.set(errorCode)
                    latch.countDown()
                }
            }
            val submitted = try {
                synchronized(lifecycleLock) {
                    if (closed) {
                        false
                    } else {
                        nsdManager.resolveService(service, listener)
                        true
                    }
                }
            } catch (error: RuntimeException) {
                DiagLog.w(TAG, "CarPlay control service resolution failed", error)
                false
            }
            if (!submitted) return null
            val completed = try {
                latch.await(RESOLVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                throw error
            }
            if (!completed) {
                DiagLog.w(TAG, "CarPlay control service resolution timed out")
                return null
            }
            resolved.get()?.let { return it }
            if (failure.get() != NsdManager.FAILURE_ALREADY_ACTIVE) {
                DiagLog.w(TAG, "CarPlay control service resolution failed code=${failure.get()}")
                return null
            }
            if (!closed && attempt + 1 < RESOLVE_ATTEMPTS) {
                Thread.sleep(RESOLVE_RETRY_DELAY_MILLIS)
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun preferredAddress(serviceInfo: NsdServiceInfo): InetAddress? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses.orEmpty()
        } else {
            listOfNotNull(serviceInfo.host)
        }
        return addresses.firstOrNull { it is Inet6Address && it.isLinkLocalAddress }
            ?.let(::applyLocalScope)
            ?: addresses.firstOrNull { it is Inet4Address }
            ?: addresses.firstOrNull { it is Inet6Address }
            ?: addresses.firstOrNull()
    }

    private fun applyLocalScope(address: InetAddress): InetAddress {
        // Any advertised IPv6 carries the interface's scope, and the IPv4-only case has none to give.
        val scope = advertisedAddresses.filterIsInstance<Inet6Address>()
            .firstOrNull { it.scopeId != 0 }?.scopeId ?: return address
        if (address !is Inet6Address || address.scopeId != 0) return address
        return try {
            Inet6Address.getByAddress(null, address.address, scope)
        } catch (_: Exception) {
            address
        }
    }

    private fun probe(
        endpoint: CarPlayBonjourEndpoint,
        address: InetAddress,
    ): CarPlayBonjourEvent.Probed? {
        var lastError: IOException? = null
        repeat(MAX_PROBE_ATTEMPTS) { attempt ->
            if (closed) return null
            try {
                val statusLine = probeOnce(address, endpoint.port)
                return CarPlayBonjourEvent.Probed(
                    endpoint = endpoint,
                    attempts = attempt + 1,
                    statusLine = statusLine,
                    error = null,
                )
            } catch (error: IOException) {
                lastError = error
            } catch (error: RuntimeException) {
                lastError = IOException("AirPlay control probe failed", error)
            }
            if (closed) return null
            if (attempt + 1 < MAX_PROBE_ATTEMPTS) {
                Thread.sleep(PROBE_RETRY_DELAY_MILLIS)
            }
        }
        return CarPlayBonjourEvent.Probed(
            endpoint = endpoint,
            attempts = MAX_PROBE_ATTEMPTS,
            statusLine = null,
            error = lastError,
        )
    }

    private fun probeOnce(address: InetAddress, port: Int): String {
        val socket = Socket()
        synchronized(lifecycleLock) {
            check(!closed) { "CarPlayBonjour is closed" }
            activeSocket = socket
        }
        try {
            localAdvertisedAddress?.let { socket.bind(InetSocketAddress(it, 0)) }
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = READ_TIMEOUT_MILLIS
            val host = address.hostAddress
                ?: throw IOException("AirPlay control service has no host address")
            val request = CarPlayBonjourProtocol.connectProbeRequest(
                host = host,
                port = port,
                sourceVersion = config.sourceVersion,
                deviceId = config.deviceId,
            )
            val output = socket.getOutputStream()
            output.write(request.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII),
            )
            return reader.readLine()
                ?: throw IOException("AirPlay control probe returned no status line")
        } finally {
            synchronized(lifecycleLock) {
                if (activeSocket === socket) activeSocket = null
            }
            runCatching { socket.close() }
        }
    }

    private fun emit(event: CarPlayBonjourEvent) {
        if (closed) return
        when (event) {
            is CarPlayBonjourEvent.Discovery -> when (event.stage) {
                CarPlayBonjourEvent.Discovery.Stage.ADDED -> addedCount.incrementAndGet()
                CarPlayBonjourEvent.Discovery.Stage.NO_MATCHING_ADDRESS -> addressMismatchCount.incrementAndGet()
                else -> Unit
            }
            is CarPlayBonjourEvent.Resolved -> resolvedCount.incrementAndGet()
            is CarPlayBonjourEvent.Probed -> {
                probeCount.addAndGet(event.attempts)
                val status = event.statusLine
                    ?.let { Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})(?: |$)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                if (status != null && status in 200..299) successfulProbeCount.incrementAndGet()
                lastProbe.set(
                    if (event.error != null) "failed_${event.error.javaClass.simpleName}"
                    else "status_${status ?: "unknown"}",
                )
            }
        }
        try {
            onEvent(event)
        } catch (error: RuntimeException) {
            DiagLog.w(TAG, "CarPlay Bonjour event callback failed", error)
        }
    }

    private fun decodeTxtValue(value: ByteArray): String =
        String(value, StandardCharsets.UTF_8).trimEnd('\u0000')

    private fun joinWorker(worker: Thread) {
        if (worker === Thread.currentThread()) return
        try {
            worker.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // Internal rather than private: the wireless bring-up samples who holds MDNS_PORT, and naming the
    // same constant there is what keeps one number in one place.
    internal companion object {
        const val TAG = "xcertplay-bonjour"
        const val WORKER_NAME = "carplay-bonjour"
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp"
        const val CARPLAY_CONTROL_SERVICE_TYPE = "_carplay-ctrl._tcp"

        /** Where an interface mDNS responder listens; named so a refusal can say which socket it was. */
        const val MDNS_PORT = 5353
        const val WORKER_POLL_MILLIS = 500L
        const val RESOLVE_ATTEMPTS = 3
        const val RESOLVE_TIMEOUT_MILLIS = 10_000L
        const val RESOLVE_RETRY_DELAY_MILLIS = 250L
        const val MAX_PROBE_ATTEMPTS = 7
        const val PROBE_RETRY_DELAY_MILLIS = 1_500L
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 3_000
        const val JOIN_TIMEOUT_MILLIS = 2_000L
        const val FAILURE_NONE = -1

        /** How long a registration may take before it is read as a refusal and the fallback is tried. */
        const val NSD_REGISTRATION_TIMEOUT_MILLIS = 3_000L

        /** No answer from the platform yet; registered and failed both count down [registrationLatch]. */
        const val REGISTRATION_PENDING = -1
        const val REGISTRATION_OK = 0

        /**
         * The instances this process is holding on [MDNS_PORT], and the run that put them there.
         *
         * One port for the process, so one owner: whoever starts next takes down what is here before
         * creating its own, rather than trusting a teardown to have finished in time.
         */
        private val interfaceMdnsLock = Any()
        private val interfaceMdns = mutableListOf<JmDNS>()

        /**
         * Which address each held socket was bound for.
         *
         * The socket list alone cannot say whether the one already open is the one this run needs: an
         * instance bound to a different address is another multicast group, so it has to be kept apart
         * from the ones this run can borrow.
         */
        private val interfaceAddresses = mutableMapOf<JmDNS, InetAddress>()
        private var interfaceMdnsOwner: CarPlayBonjour? = null

        /**
         * Caller holds [interfaceMdnsLock]. Closing an instance can block, and that is deliberate: the
         * sockets have to be gone before the next run binds, not merely on their way out.
         */
        private fun closeInterfaceMdnsLocked() {
            val closing = interfaceMdns.toList()
            interfaceMdns.clear()
            interfaceAddresses.clear()
            closing.forEach { dns -> runCatching { dns.close() } }
        }

        /**
         * Gives 5353 back to the head unit for good.
         *
         * The socket outlives every run (see [releaseInterfaceMdns]), so nothing inside a bring-up ever
         * closes it. This is where it goes back: when the app is done with wireless discovery, not when
         * one attempt ends. Safe to call with nothing held, and the next bring-up simply binds again.
         *
         * Runs on its own thread: closing a JmDNS instance blocks, and the caller is a service's
         * `onDestroy` on the main thread, where a blocked socket teardown reads as a frozen screen.
         */
        fun releaseProcessResponder() {
            Thread(
                {
                    synchronized(interfaceMdnsLock) {
                        interfaceMdnsOwner = null
                        closeInterfaceMdnsLocked()
                    }
                },
                "carplay-mdns-release",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }
}
