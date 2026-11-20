package com.shilapi.xcertplay.instrument.fly

/**
 * One snapshot of the links the cluster could use, written so the uploaded report still says
 * which address was where.
 *
 * The diagnostic redactor replaces every dotted address with `[ip]`. These lines use hyphens
 * (`10-255-255-101`) so the numbers survive. No MAC is kept. Each shell command fits the
 * ExtraUtils 220-byte wrapper on its own; the vendor service rejects a longer one.
 */
internal object FlyProbe {

    const val SYSFS = "ls /sys/class/net"
    const val ADDR = "/system/bin/busybox ip addr"
    const val NEIGH = "/system/bin/busybox ip neigh"
    const val ROUTE = "/system/bin/busybox ip route"
    const val TCP = "/system/bin/busybox grep -e :0404 -e :70D7 /proc/net/tcp /proc/net/tcp6"

    val commands: List<String> = listOf(SYSFS, ADDR, NEIGH, ROUTE, TCP)

    const val CLUSTER_HOST = "10.255.255.101"
    const val CLUSTER_RTP = "10.255.255.106"
    const val IPK_HOST = "172.30.100.10"
    const val IPK_PEER = "172.30.100.11"

    private const val MAX_LINES = 48
    private val dotted = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val linkHeader = Regex("^\\d+: ([^:]+): .*\\bstate (\\S+)")

    fun lines(
        sysfs: ShellText,
        addr: ShellText,
        neigh: ShellText,
        route: ShellText,
        tcp: ShellText,
        extra: String,
    ): List<String> {
        val out = mutableListOf<String>()
        out += summary(sysfs, addr, neigh)
        out += netLines(sysfs)
        out += addrLines(addr)
        out += neighLines(neigh)
        out += routeLines(route)
        out += tcpLines(tcp)
        out += extraLines(extra)
        if (out.size <= MAX_LINES) return out
        return out.take(MAX_LINES - 1) + "fly.probe truncated"
    }

    fun summary(sysfs: ShellText, addr: ShellText, neigh: ShellText): String {
        val names = names(sysfs)
        val eth = presence(names, addr, sysfs, "eth0")
        val tbox = presence(names, addr, sysfs, "tbox0")
        return "fly.probe summary eth0=$eth tbox0=$tbox" +
            " cluster-host=${holder(addr, CLUSTER_HOST)}" +
            " ipk-host=${holder(addr, IPK_HOST)}" +
            " ipk-peer=${seen(neigh, IPK_PEER)}" +
            " rtp-peer=${seen(neigh, CLUSTER_RTP)}"
    }

    /** Little-endian `/proc/net/tcp` address, as hyphenated octets. `65FFFF0A` is `10-255-255-101`. */
    fun decodeIpv4Hex(hex: String): String? {
        if (hex.length != 8 || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        val value = hex.toLong(16)
        return listOf(0, 8, 16, 24).joinToString("-") { ((value shr it) and 0xFF).toString() }
    }

    private fun presence(names: List<String>, addr: ShellText, sysfs: ShellText, iface: String): String = when {
        iface in names || addr.body.contains("$iface:") -> "present"
        sysfs.rc != 0 && addr.rc != 0 -> "unknown"
        else -> "absent"
    }

    private fun holder(addr: ShellText, host: String): String {
        if (addr.rc == null || (addr.rc != 0 && !addr.body.contains(host))) return "unknown"
        return ifaceHolding(addr.body, host) ?: "none"
    }

    private fun seen(neigh: ShellText, host: String): String = when {
        neigh.body.contains(host) -> "seen"
        neigh.rc == null -> "unknown"
        else -> "none"
    }

    private fun names(sysfs: ShellText): List<String> {
        if (sysfs.rc != 0) return emptyList()
        return sysfs.body.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { name -> name.isNotEmpty() && name.none { it == '/' || it == ':' } }
    }

    private fun netLines(sysfs: ShellText): List<String> {
        if (sysfs.rc == null) return listOf("fly.probe net failed")
        if (sysfs.rc != 0) return listOf("fly.probe net failed rc=${sysfs.rc}")
        val names = names(sysfs)
        if (names.isEmpty()) return listOf("fly.probe net empty")
        return listOf("fly.probe net ${names.joinToString(" ")}")
    }

    private fun addrLines(addr: ShellText): List<String> {
        if (addr.rc == null) return listOf("fly.probe addr failed")
        if (addr.rc != 0 && addr.body.isBlank()) return listOf("fly.probe addr failed rc=${addr.rc}")
        val out = mutableListOf<String>()
        var iface = ""
        for (raw in addr.body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("inet6") || line.startsWith("link/")) continue
            val header = linkHeader.find(line)
            if (header != null) {
                iface = header.groupValues[1]
                out += "fly.probe link $iface ${header.groupValues[2]}"
                continue
            }
            if (line.startsWith("inet ")) out += "fly.probe addr $iface ${hyphenate(line)}"
        }
        if (out.isEmpty()) out += "fly.probe addr empty"
        return out
    }

    private fun neighLines(neigh: ShellText): List<String> {
        if (neigh.rc == null && neigh.body.isBlank()) return listOf("fly.probe neigh failed")
        if (neigh.rc != null && neigh.rc != 0 && neigh.body.isBlank()) {
            return listOf("fly.probe neigh failed rc=${neigh.rc}")
        }
        val out = mutableListOf<String>()
        for (raw in neigh.body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("IP address")) continue
            val shown = if (line.contains(" dev ")) {
                hyphenate(line).replace(Regex("\\blladdr\\s+\\S+"), "").replace(Regex("\\s+"), " ").trim()
            } else {
                arp(line) ?: continue
            }
            out += "fly.probe neigh $shown"
        }
        if (out.isEmpty()) out += "fly.probe neigh empty"
        return out
    }

    /** `/proc/net/arp`: address, hw type, flags, mac, mask, device. The mac is dropped. */
    private fun arp(line: String): String? {
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 6 || !parts[0].contains('.')) return null
        return "${hyphenate(parts[0])} flags=${parts[2]} dev ${parts[5]}"
    }

    private fun routeLines(route: ShellText): List<String> {
        if (route.rc == null && route.body.isBlank()) return listOf("fly.probe route failed")
        if (route.rc != null && route.rc != 0 && route.body.isBlank()) {
            return listOf("fly.probe route failed rc=${route.rc}")
        }
        val out = route.body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { "fly.probe route ${hyphenate(it)}" }
            .toList()
        if (out.isEmpty()) return listOf("fly.probe route empty")
        return out
    }

    private fun tcpLines(tcp: ShellText): List<String> {
        if (tcp.rc == null && tcp.body.isBlank()) return listOf("fly.probe tcp failed")
        val found = mutableSetOf<Int>()
        val out = mutableListOf<String>()
        for (raw in tcp.body.lineSequence()) {
            val parts = raw.trim().split(Regex("\\s+"))
            val localIdx = parts.indexOfFirst { token ->
                val host = token.substringBefore(':')
                host.length == 8 && token.substringAfter(':').length == 4
            }
            if (localIdx < 0) continue
            val hostHex = parts[localIdx].substringBefore(':')
            val port = parts[localIdx].substringAfter(':').toIntOrNull(16) ?: continue
            if (port != 1028 && port != 28887) continue
            val host = decodeIpv4Hex(hostHex) ?: continue
            val shown = if (host == "0-0-0-0") "any" else host
            val st = parts.getOrNull(localIdx + 2) ?: "?"
            val uid = parts.getOrNull(localIdx + 6) ?: "?"
            found += port
            out += "fly.probe tcp port=$port st=$st host=$shown uid=$uid"
        }
        if (out.isEmpty() && tcp.rc != 0 && tcp.rc != 1) return listOf("fly.probe tcp failed rc=${tcp.rc}")
        if (1028 !in found) out += "fly.probe tcp port=1028 absent"
        if (28887 !in found) out += "fly.probe tcp port=28887 absent"
        return out
    }

    private fun extraLines(extra: String): List<String> {
        val kept = FlyTrace.select(extra)
        if (kept.isEmpty()) return listOf("fly.probe extra empty")
        return kept.map { "fly.probe extra ${it.take(240)}" }
    }

    private fun ifaceHolding(body: String, host: String): String? {
        var iface = ""
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            val header = linkHeader.find(line)
            if (header != null) iface = header.groupValues[1]
            if (line.contains(host)) return iface.ifEmpty { "unknown" }
        }
        return null
    }

    private fun hyphenate(text: String): String = dotted.replace(text) { it.value.replace('.', '-') }
}

/** One shell result. A null [rc] means the command never ran. */
internal data class ShellText(val rc: Int?, val body: String)
