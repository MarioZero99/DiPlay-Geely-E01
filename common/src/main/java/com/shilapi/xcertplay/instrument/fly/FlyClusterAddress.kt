package com.shilapi.xcertplay.instrument.fly

import com.shilapi.xcertplay.system.RootShellBridge

/**
 * Makes 10.255.255.101 exist for as long as the fly link is open.
 *
 * Navi writes that address down and the cluster dials it, but this head unit does not configure
 * it. Listening on every interface still receives nothing until the address is local. The address
 * is added on the one up interface that is not the phone or the T-Box, and removed when the link
 * closes. It is never written into /system.
 */
class FlyClusterAddress(
    private val shell: (String) -> Pair<Int?, String> = { RootShellBridge.executeWithOutput(it) },
    private val interfaces: () -> List<Nic> = { FlyNet.local() },
) {
    private val lock = Any()
    private var addedOn: String? = null

    fun prepare(): List<String> {
        val nics = interfaces()
        val lines = mutableListOf(FlyNet.line(nics))
        if (FlyNet.has(nics, HOST)) return lines
        val target = FlyNet.aliasTarget(nics)
        if (target == null) {
            lines += FlyNet.skipLine(nics)
            return lines
        }
        val (rc, _) = shell(addCommand(target))
        if (rc == 0) {
            synchronized(lock) { addedOn = target }
            lines += "fly.net add $target"
        } else {
            lines += "fly.net add $target failed"
        }
        return lines
    }

    fun release() {
        val iface = synchronized(lock) {
            val current = addedOn
            addedOn = null
            current
        } ?: return
        shell(delCommand(iface))
    }

    companion object {
        const val HOST = "10.255.255.101"

        fun addCommand(iface: String): String {
            check(safe(iface))
            return "/system/bin/busybox ip addr add $HOST/24 dev $iface"
        }

        fun delCommand(iface: String): String {
            check(safe(iface))
            return "/system/bin/busybox ip addr del $HOST/24 dev $iface"
        }

        private fun safe(iface: String): Boolean = iface.matches(Regex("[A-Za-z0-9._:-]{1,16}"))
    }
}
