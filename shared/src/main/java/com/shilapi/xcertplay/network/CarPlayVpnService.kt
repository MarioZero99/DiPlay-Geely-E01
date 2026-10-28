package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.shilapi.xcertplay.DiagLog
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.DisconnectOrigin
import com.shilapi.xcertplay.transport.FirstFaultRecorder
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.BindException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val additionalAddresses: List<InetAddress> = emptyList(),
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0
    @Volatile private var faultRecorder: FirstFaultRecorder? = null

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get()) {
            DiagLog.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        faultRecorder = ncm.faultRecorder
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        // A platform that refuses this leaves the descriptor non-blocking, where an
                        // idle link reads back as EAGAIN. That is a different failure, further from
                        // its cause, so it is recorded here rather than silently deferred.
                        runCatching { setBlocking(true) }
                            .onFailure { DiagLog.w(TAG, "tun setBlocking refused; descriptor stays non-blocking", it) }
                    }
                }
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac, ncm.faultRecorder) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            startAirPlayServer(
                generation,
                AirPlayAttachment(address, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started
        } catch (error: Exception) {
            noteAirPlayBindFailed(config.port, error)
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        /**
         * The rest of the selected interface's addresses, listened on alongside [bindAddress].
         *
         * Bonjour advertises every one of them, and an address the phone can find but not reach is
         * worse than one it never heard of. Empty where the interface has only one.
         */
        additionalBindAddresses: List<InetAddress> = emptyList(),
    ): AttachResult {
        if (active.get()) {
            DiagLog.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(
                    bindAddress, config, identity, pairings, mfi, listener, media,
                    additionalBindAddresses,
                ),
            )
            AttachResult.Started
        } catch (error: Exception) {
            noteAirPlayBindFailed(config.port, error)
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    /**
     * The port the listener actually bound, which may differ from the configured one.
     *
     * The port is chosen by binding rather than in advance, because every address the attachment
     * serves has to share one — the phone learns a single port for the whole device. What was bound is
     * then what gets advertised, so a fallback port reaches the phone rather than only the log.
     */
    fun boundPort(): Int? = attachment?.config?.port

    override fun onDestroy() {
        detach()
        // The mDNS socket outlives every bring-up so a retry never meets its own earlier socket; with
        // the service gone there is nothing left to advertise, so 5353 goes back to the head unit.
        CarPlayBonjour.releaseProcessResponder()
        super.onDestroy()
    }

    /**
     * Preferred port taken, listener moved. The number alone does not say who still holds it, and on
     * this head unit there is no adb to go and look. The kernel table names the uid and, when the
     * holder is an app, the package.
     */
    private fun noteAirPlayPortBusy(busy: Int, bound: Int) {
        DiagLog.w(
            TAG,
            "AirPlay port $busy is in use; listening on $bound instead; " +
                PortHolders.tcp(busy, packageNameResolver(this)),
        )
    }

    /** Every candidate refused. Same table, so the next report says who was on the preferred port. */
    private fun noteAirPlayBindFailed(port: Int, error: Exception) {
        if (error !is BindException) return
        DiagLog.w(
            TAG,
            "AirPlay port $port could not be bound; " + PortHolders.tcp(port, packageNameResolver(this)),
        )
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val addresses = listOf(replacement.address) + replacement.additionalAddresses
        val servers = if (replacement.additionalAddresses.isEmpty()) {
            listOf(
                AirPlayPortSelector.bind(replacement.address, replacement.config.port, onFallback = ::noteAirPlayPortBusy),
            )
        } else {
            AirPlayPortSelector.bindAll(addresses, replacement.config.port, onFallback = ::noteAirPlayPortBusy)
        }
        val server = servers.first()
        // The bound port, not the requested one: every address shares it, and it is what Bonjour and
        // the iAP2 configuration go on to advertise.
        attachment = replacement.copy(config = replacement.config.copy(port = server.localPort))
        serverSocket = server
        additionalServers = servers.drop(1)
        servers.forEach { bound ->
            Thread(
                { acceptLoop(generation, bound) },
                "airplay-accept",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                // Which of this attachment's addresses the phone dialled, by family rather than by
                // literal: an exported report redacts addresses, and this is the line that says whether
                // the link the phone chose is the one this run published.
                val bound = runCatching { server.inetAddress }.getOrNull()
                DiagLog.i(
                    TAG,
                    "airplay connection accepted family=" + when (bound) {
                        is Inet6Address -> "IPv6"
                        null -> "unknown"
                        else -> "IPv4"
                    } + " port=${server.localPort} from=${socket.remoteSocketAddress}",
                )
                // Read the volatile field the way the transport-error path does: the attachment this
                // connection belongs to is whoever is attached right now, and a run that was replaced
                // filters itself out on the listener's own side.
                attachment?.listener?.onConnectionAccepted()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get()) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                        faultRecorder = faultRecorder,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    DiagLog.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        val origin = faultRecorder?.origin ?: DisconnectOrigin.UNKNOWN
        val firstMsg = faultRecorder?.message ?: "none"
        val sessionCount = synchronized(sessionsLock) { sessions.size }
        DiagLog.e(
            TAG,
            "CarPlay transport stopped: $message (origin=$origin, firstFault=$firstMsg, activeSessions=$sessionCount)",
            error,
        )
        Thread(
            {
                synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                }
                listener.onTransportError(message)
                stopSelf()
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        faultRecorder = null
        serverSocket?.close()
        serverSocket = null
        additionalServers.forEach { it.close() }
        additionalServers = emptyList()
        closeSessionsLocked()
        bridge?.close()
        bridge = null
        tun?.close()
        tun = null
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)

        fun classifySocketFault(
            error: Throwable,
            recorder: FirstFaultRecorder?,
            nowMillis: Long = SystemClock.elapsedRealtime(),
        ): DisconnectOrigin {
            val msg = error.message.orEmpty().lowercase()
            val origin = when {
                msg.contains("timed out") || msg.contains("etimedout") -> DisconnectOrigin.TCP_TIMEOUT
                msg.contains("reset by peer") || msg.contains("econnreset") || msg.contains("broken pipe") -> DisconnectOrigin.TCP_PEER_RESET
                else -> DisconnectOrigin.UNKNOWN
            }
            if (origin != DisconnectOrigin.UNKNOWN) {
                recorder?.record(origin, "tcp-socket: ${error.message ?: error.javaClass.simpleName}", nowMillis)
            }
            return origin
        }
    }
}
