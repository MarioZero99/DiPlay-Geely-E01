package com.shilapi.xcertplay.network

import android.content.Context

/**
 * Names whoever holds a port, from the kernel's own table.
 *
 * A bind refused with EADDRINUSE says only "someone else has it", and on a head unit there is no adb to
 * go and look. The distinction that matters is between a helper this app started and something that was
 * already there, because the two need different fixes — and only the kernel table tells them apart.
 * `/proc/net/{udp,udp6,tcp,tcp6}` carry the uid, state and inode of every socket, so a failure line can
 * name its own cause. A table that cannot be read is reported as such: that is an answer too.
 */
internal object PortHolders {
    fun udp(port: Int, packageOf: ((Int) -> String?)? = null): String = describe(TABLES_UDP, port, packageOf)

    fun tcp(port: Int, packageOf: ((Int) -> String?)? = null): String = describe(TABLES_TCP, port, packageOf)

    private fun describe(tables: List<Pair<String, String>>, port: Int, packageOf: ((Int) -> String?)?): String =
        tables.joinToString("; ") { (name, path) ->
            val text = runCatching { readBoundedDiagnosticFile(path, MAX_PROC_BYTES) }.getOrNull()
                ?: return@joinToString "$name unreadable"
            val held = text.lineSequence().mapNotNull { holderOf(it, port, packageOf) }.toList()
            if (held.isEmpty()) "$name free" else held.joinToString(",") { "$name $it" }
        }

    /** One row's socket when it is bound to [port], or null. The column order is the kernel's. */
    fun holderOf(row: String, port: Int, packageOf: ((Int) -> String?)? = null): String? {
        val fields = row.trim().split(Regex("\\s+"))
        // sl, local_address, rem_address, st, queues, timer, retrnsmt, uid, timeout, inode, ...
        if (fields.size < 10) return null
        if (fields[1].substringAfterLast(':').toIntOrNull(16) != port) return null
        val uid = fields[7]
        val owner = uid.toIntOrNull()?.let { packageOf?.invoke(it) }?.takeIf { it.isNotEmpty() }
        return "uid=$uid" + (owner?.let { " pkg=$it" } ?: "") + " st=${fields[3]} inode=${fields[9]}"
    }

    private const val MAX_PROC_BYTES = 64 * 1024
    private val TABLES_UDP = listOf("udp" to "/proc/net/udp", "udp6" to "/proc/net/udp6")
    private val TABLES_TCP = listOf("tcp" to "/proc/net/tcp", "tcp6" to "/proc/net/tcp6")
}

/**
 * Turns a uid from the socket table into the installed package behind it.
 *
 * This is what makes the difference between "the port is held by uid 10123" and "held by
 * com.some.other.receiver" — a name that decides the next step and needs no root and no trip to the car.
 * A system uid has no package and comes back empty, which is itself the answer: a daemon, not an app.
 */
internal fun packageNameResolver(context: Context): (Int) -> String? {
    val manager = (context.applicationContext ?: context).packageManager
    return { uid -> runCatching { manager.getPackagesForUid(uid)?.joinToString(",") }.getOrNull() }
}
