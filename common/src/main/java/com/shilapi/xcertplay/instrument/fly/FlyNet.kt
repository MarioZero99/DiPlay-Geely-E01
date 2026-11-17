package com.shilapi.xcertplay.instrument.fly

import java.net.Inet4Address
import java.net.NetworkInterface

/** One network interface, as far as the fly log needs to know. */
data class Nic(val name: String, val up: Boolean, val addresses: List<String>)

/**
 * Which interface may carry 10.255.255.101.
 *
 * The cluster dials that address. On this head unit it is reached through tbox0.
 * An ethernet NIC is used when exactly one is up. The phone hotspot, Wi-Fi,
 * cellular data, and tunnels are never chosen.
 */
object FlyNet {

    fun line(nics: List<Nic>): String {
        if (nics.isEmpty()) return "fly.net none"
        return "fly.net " + nics.joinToString(" ") { nic ->
            val shown = if (nic.addresses.isEmpty()) "-" else nic.addresses.joinToString(",")
            "${nic.name}=$shown"
        }
    }

    fun has(nics: List<Nic>, host: String): Boolean = nics.any { host in it.addresses }

    /**
     * The interface that receives 10.255.255.101, or null when the choice is ambiguous.
     * One up eth* wins. Otherwise one up tbox*. Otherwise the only remaining eligible NIC.
     */
    fun aliasTarget(nics: List<Nic>): String? {
        val up = eligible(nics)
        val eth = up.filter { it.name.startsWith("eth", ignoreCase = true) }
        if (eth.size == 1) return eth[0].name
        if (eth.size > 1) return null
        val tbox = up.filter { it.name.startsWith("tbox", ignoreCase = true) }
        if (tbox.size == 1) return tbox[0].name
        if (tbox.size > 1) return null
        return up.singleOrNull()?.name
    }

    fun skipLine(nics: List<Nic>): String {
        val names = eligible(nics).joinToString(",") { it.name }
        return if (names.isEmpty()) "fly.net skip none" else "fly.net skip $names"
    }

    fun local(): List<Nic> {
        val found = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val nics = mutableListOf<Nic>()
        while (found.hasMoreElements()) {
            val nic = found.nextElement()
            val addresses = nic.inetAddresses.asSequence()
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .filterNotNull()
                .filter { it.isNotEmpty() }
                .toList()
            val up = try {
                nic.isUp
            } catch (_: Exception) {
                false
            }
            nics += Nic(nic.name, up, addresses)
        }
        return nics
    }

    private fun eligible(nics: List<Nic>): List<Nic> = nics.filter { it.up && !excluded(it.name) }

    private fun excluded(name: String): Boolean {
        val normalized = name.lowercase()
        if (normalized == "lo") return true
        return EXCLUDED.any { normalized == it || normalized.startsWith(it) }
    }

    private val EXCLUDED = listOf(
        "ap", "wlan", "p2p", "dummy", "rmnet", "ccmni", "sit", "ifb",
        "tun", "ppp", "ip6", "gre", "bond",
    )
}
