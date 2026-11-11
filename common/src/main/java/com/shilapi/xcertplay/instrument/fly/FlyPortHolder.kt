package com.shilapi.xcertplay.instrument.fly

import com.shilapi.xcertplay.system.RootShellBridge

/** A process that is listening on the cluster's TCP port. */
data class PortHolder(val pid: Int?, val comm: String, val local: String)

/** One LISTEN row from /proc/net/tcp, before it has been matched to a process. */
data class ProcListen(val local: String, val inode: Long)

/**
 * Finds who already holds TCP 1028 and turns that into one log line.
 *
 * The holder is only looked up when the bind failed because the port is taken. A missing address
 * is a different failure and must not be reported as a conflict. Nothing here stops the other process.
 */
object FlyPortHolder {

    private const val NETSTAT = "/system/bin/busybox netstat -tlnp"

    fun report(
        error: Exception,
        port: Int,
        selfPid: Int = currentPid(),
        shell: (String) -> Pair<Int?, String> = { RootShellBridge.executeWithOutput(it) },
    ): List<String> {
        if (port <= 0 || !inUse(error)) return emptyList()
        val (rc, body) = shell(NETSTAT)
        if (rc == null && body.isEmpty()) return listOf("fly.tcp holder skipped")
        val named = parseNetstat(body, port).filter { it.pid != null }
        if (named.isNotEmpty()) return named.take(4).map { format(it, selfPid) }
        val proc = shell("cat /proc/net/tcp /proc/net/tcp6")
        val listens = parseProc(proc.second, port)
        if (listens.isEmpty()) return listOf("fly.tcp holder none")
        return listens.take(4).map { listen ->
            val found = resolve(listen.inode, shell) ?: return@map "fly.tcp holder none local=${listen.local}"
            format(found.copy(local = listen.local), selfPid)
        }
    }

    fun parseNetstat(text: String, port: Int): List<PortHolder> {
        val holders = mutableListOf<PortHolder>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.contains("LISTEN")) continue
            val local = line.split(Regex("\\s+")).firstOrNull { token ->
                token.endsWith(":$port") || token.endsWith(".$port")
            } ?: continue
            val program = Regex("(\\d+)/(\\S*)").findAll(line).lastOrNull()
            holders += PortHolder(
                pid = program?.groupValues?.get(1)?.toIntOrNull(),
                comm = program?.groupValues?.get(2).orEmpty(),
                local = local,
            )
        }
        return holders
    }

    fun parseProc(text: String, port: Int): List<ProcListen> {
        val portHex = port.toString(16).padStart(4, '0')
        val listens = mutableListOf<ProcListen>()
        for (raw in text.lineSequence()) {
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 10) continue
            if (!parts[3].equals("0A", ignoreCase = true)) continue
            val address = parts[1].split(":")
            if (address.size != 2 || !address[1].equals(portHex, ignoreCase = true)) continue
            val inode = parts[9].toLongOrNull() ?: continue
            listens += ProcListen(decode(address[0], port), inode)
        }
        return listens
    }

    /** One shell command, short enough for ExtraUtils, that prints /proc/<pid>/fd paths for an inode. */
    fun inodeCommand(inode: Long): String =
        "for l in /proc/[0-9]*/fd/*; do case \$(readlink \$l) in socket:\\[$inode]) echo \$l;; esac; done"

    private fun resolve(inode: Long, shell: (String) -> Pair<Int?, String>): PortHolder? {
        val (rc, body) = shell(inodeCommand(inode))
        if (rc == null) return null
        val path = body.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/proc/") } ?: return null
        val pid = path.removePrefix("/proc/").substringBefore("/").toIntOrNull() ?: return null
        val (crc, cmdline) = shell("tr '\\0' ' ' </proc/$pid/cmdline")
        val comm = if (crc == 0) cmdline else ""
        return PortHolder(pid, comm, local = "")
    }

    private fun format(holder: PortHolder, selfPid: Int): String {
        val comm = holder.comm.trim().replace(Regex("\\s+"), "_").take(64).ifEmpty { "unknown" }
        val self = holder.pid != null && holder.pid > 0 && holder.pid == selfPid
        val kind = if (self) "self " else ""
        return "fly.tcp holder ${kind}pid=${holder.pid} comm=$comm local=${holder.local}"
    }

    private fun inUse(error: Exception): Boolean {
        val message = error.message ?: return false
        return message.contains("Address already in use", ignoreCase = true) ||
            message.contains("EADDRINUSE", ignoreCase = true)
    }

    private fun decode(ipHex: String, port: Int): String {
        if (ipHex.length != 8) return "$ipHex:$port"
        val value = ipHex.toLong(16)
        val bytes = (0..3).map { shift -> ((value shr (8 * shift)) and 0xFF).toInt() }
        return bytes.joinToString(".") + ":$port"
    }

    private fun currentPid(): Int = try {
        android.os.Process.myPid()
    } catch (_: Throwable) {
        -1
    }
}
