package com.shilapi.xcertplay.network

import java.io.Closeable
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * The socket behind the process's responder: the published record and the discovery listener.
 *
 * Narrow on purpose — it is what lets a test stand in for the socket, and what keeps the borrowed
 * responder's lifetime out of a run's hands. The record's ownership rule lives in [MdnsResponder],
 * which is where a run's publish and retract both go.
 */
internal interface MdnsResponderSocket : Closeable {
    fun addServiceListener(type: String, listener: ServiceListener)

    fun removeServiceListener(type: String, listener: ServiceListener)

    fun publish(info: ServiceInfo)

    fun retract(info: ServiceInfo)
}

private class JmDnsResponderSocket(private val dns: JmDNS) : MdnsResponderSocket {
    override fun addServiceListener(type: String, listener: ServiceListener) =
        dns.addServiceListener(type, listener)

    override fun removeServiceListener(type: String, listener: ServiceListener) =
        dns.removeServiceListener(type, listener)

    override fun publish(info: ServiceInfo) = dns.registerService(info)

    override fun retract(info: ServiceInfo) = dns.unregisterService(info)

    override fun close() = dns.close()
}

/**
 * The one mDNS socket this process binds per advertised address.
 *
 * A bound mDNS socket keeps 5353 for the whole process: a second one is refused with `EADDRINUSE`
 * rather than sharing, and on the platform this fork ships to the first refusal is permanent. So the
 * socket cannot belong to a bring-up attempt — an attempt that ends without closing it would take
 * discovery away from every attempt after it, and the refusal that follows names a port instead of
 * the reason. A run borrows this one; [acquire] hands back the same socket while the address is
 * unchanged, and only a changed address opens a new one.
 *
 * Publishing is a handover rather than a write: the last [publish] owns the record, and only that one
 * can [retract] it. jmDNS matches a retraction by service name and type — the same for every run of
 * the same accessory — so a superseded run letting go would otherwise take the record away from the
 * run that replaced it.
 */
internal object MdnsResponder {
    /** Opens the socket. Replaced in tests so an open can be counted instead of performed. */
    internal var openSocket: (InetAddress, String) -> MdnsResponderSocket = { address, name ->
        JmDnsResponderSocket(JmDNS.create(address, name))
    }

    private val lock = Any()
    private var bound: InetAddress? = null
    private var socket: MdnsResponderSocket? = null
    private var published: ServiceInfo? = null

    /** The socket for [address], opened on first use and handed back unchanged after that. */
    fun acquire(address: InetAddress, name: String): MdnsResponderSocket = synchronized(lock) {
        val current = socket
        if (current != null && bound == address) return current
        // The address changed. The old socket has to be gone before the new one binds: it is the same
        // port, and the platform will not let two sockets have it.
        releaseLocked()
        val opened = openSocket(address, name)
        bound = address
        socket = opened
        opened
    }

    /**
     * Publishes [info] as the responder's record, taking it over from whoever published last.
     *
     * The record this one replaces is taken back first. jmDNS matches a retraction by name and type,
     * and for one accessory those are the same every run — which is why the old record staying behind
     * has not shown up as a problem. They stop being the same the moment the accessory is renamed, and
     * a record left registered is advertised for the rest of the process's life.
     *
     * A publish with no socket is refused rather than recorded. The socket is what [acquire] opens, and
     * a record held without one reads as published to everything except the network.
     */
    fun publish(info: ServiceInfo) {
        synchronized(lock) {
            val current = socket ?: error("publish without a socket: acquire one first")
            published?.takeIf { it !== info }?.let { current.retract(it) }
            published = info
            current.publish(info)
        }
    }

    /** Takes [info] back, and only if it is still the record this responder holds. */
    fun retract(info: ServiceInfo) {
        synchronized(lock) {
            if (published !== info) return
            published = null
            socket?.retract(info)
        }
    }

    /** Drops the socket for good. For the process going away — a run gives its borrow back elsewhere. */
    fun close() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        val current = socket ?: return
        socket = null
        bound = null
        published = null
        runCatching { current.close() }
    }
}

/**
 * The process's wireless discovery, as the rest of the app sees it.
 *
 * The socket belongs to the process rather than to a session — that is what keeps a run from losing
 * 5353 — so a session ending must not release it. Closing the app is when it goes back, and this is
 * the handle for that so no caller outside this module needs the machinery behind it.
 */
object WirelessDiscovery {
    /**
     * Gives the mDNS socket back to the head unit, freeing 5353 for whatever else wants it.
     *
     * The next bring-up opens a fresh socket, so this is safe to call when nothing is running.
     */
    fun releaseResponder() = MdnsResponder.close()
}
