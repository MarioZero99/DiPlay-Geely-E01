package com.shilapi.xcertplay.instrument.fly

import java.net.Inet4Address
import java.net.NetworkInterface

/** One network interface, as far as the fly log needs to know. */
data class Nic(val name: String, val up: Boolean, val addresses: List<String>)

/**
 * Which interface may carry 10.255.255.101.
 *
 * The phone's hotspot, Wi-Fi, and the T-Box are not the cable to the cluster. Adding the address
 * there would not make the cluster's dial arrive, so those names are never chosen.
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

    /** The single up interface that can face the cluster, or null when there is not exactly one. */
    fun aliasTarget(nics: List<Nic>): String? = eligible(nics).singleOrNull()?.name

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

    private val EXCLUDED = listOf("ap", "wlan", "tbox", "p2p", "dummy", "rmnet", "ccmni", "sit", "ifb")
}
