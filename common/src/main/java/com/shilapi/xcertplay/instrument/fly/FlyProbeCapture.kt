package com.shilapi.xcertplay.instrument.fly

import com.shilapi.xcertplay.DiagLog
import com.shilapi.xcertplay.system.RootShellBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the fly probe once and writes it into the session log.
 *
 * The quick bar starts this off the UI thread. A second press while the first is still walking
 * the root service logs `fly.probe busy` and does nothing else: ExtraUtils runs one command at a
 * time, and two walks would interleave.
 */
internal object FlyProbeCapture {

    private val running = AtomicBoolean(false)

    fun collectOnCar() {
        collect(
            shell = { command ->
                if (!RootShellBridge.isAvailable()) {
                    null to ""
                } else {
                    RootShellBridge.executeWithOutput(command)
                }
            },
            listDir = { path ->
                try {
                    File(path).list()?.toList()
                } catch (_: Exception) {
                    null
                }
            },
            readText = { path ->
                try {
                    File(path).takeIf { it.isFile }?.readText()
                } catch (_: Exception) {
                    null
                }
            },
            log = { line -> DiagLog.i(ClusterFlyLog.TAG, line) },
        )
    }

    internal fun collect(
        shell: (String) -> Pair<Int?, String>,
        listDir: (String) -> List<String?>?,
        readText: (String) -> String?,
        log: (String) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) {
            log("fly.probe busy")
            return
        }
        try {
            log("fly.probe begin")
            val sysfs = shell(FlyProbe.SYSFS).asText().ifBlank {
                val names = listDir("/sys/class/net")?.filterNotNull().orEmpty()
                if (names.isEmpty()) ShellText(null, "") else ShellText(0, names.joinToString(" "))
            }
            val addr = shell(FlyProbe.ADDR).asText()
            val neigh = shell(FlyProbe.NEIGH).asText().ifBlank {
                val arp = readText("/proc/net/arp")
                if (arp.isNullOrBlank()) ShellText(null, "") else ShellText(0, arp)
            }
            val route = shell(FlyProbe.ROUTE).asText()
            val tcp = shell(FlyProbe.TCP).asText().ifBlank { tcpFromFiles(readText) }
            val extra = shell(FlyTrace.COMMAND).second
            FlyProbe.lines(sysfs, addr, neigh, route, tcp, extra).forEach(log)
            log("fly.probe end")
        } finally {
            running.set(false)
        }
    }

    private fun tcpFromFiles(readText: (String) -> String?): ShellText {
        val body = listOf("/proc/net/tcp", "/proc/net/tcp6").mapNotNull { path ->
            val text = readText(path) ?: return@mapNotNull null
            text.lineSequence()
                .filter { it.contains(":0404") || it.contains(":70D7") }
                .joinToString("\n") { "$path: $it" }
                .ifBlank { null }
        }.joinToString("\n")
        return if (body.isBlank()) ShellText(1, "") else ShellText(0, body)
    }
}

private fun Pair<Int?, String>.asText(): ShellText = ShellText(first, second)

private fun ShellText.ifBlank(fallback: () -> ShellText): ShellText =
    if (rc == 0 || body.isNotBlank()) this else fallback()
