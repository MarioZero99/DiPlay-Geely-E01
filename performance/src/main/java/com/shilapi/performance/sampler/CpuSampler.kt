package com.shilapi.performance.sampler

import com.shilapi.performance.model.CpuMetrics
import java.io.File

class CpuSampler {
    val coreCount: Int = Runtime.getRuntime().availableProcessors()

    @Volatile
    private var prevTotal = 0L

    @Volatile
    private var prevIdle = 0L

    @Volatile
    private var lastCalculatedUsage = -1

    fun computeUsageFromLine(line: String): Int {
        if (!line.startsWith("cpu ") && !line.startsWith("cpu  ")) return -1
        val tokens = line.trim().split("\\s+".toRegex())
        if (tokens.size < 5) return -1
        return try {
            val user = tokens[1].toLong()
            val nice = tokens[2].toLong()
            val system = tokens[3].toLong()
            val idle = tokens[4].toLong()
            val iowait = if (tokens.size > 5) tokens[5].toLong() else 0L
            val irq = if (tokens.size > 6) tokens[6].toLong() else 0L
            val softirq = if (tokens.size > 7) tokens[7].toLong() else 0L
            val steal = if (tokens.size > 8) tokens[8].toLong() else 0L

            val total = user + nice + system + idle + iowait + irq + softirq + steal
            if (prevTotal == 0L) {
                prevTotal = total
                prevIdle = idle
                lastCalculatedUsage
            } else {
                val deltaTotal = total - prevTotal
                val deltaIdle = idle - prevIdle
                prevTotal = total
                prevIdle = idle
                if (deltaTotal > 0) {
                    val usage = (((deltaTotal - deltaIdle) * 100) / deltaTotal).toInt().coerceIn(0, 100)
                    lastCalculatedUsage = usage
                    usage
                } else {
                    lastCalculatedUsage
                }
            }
        } catch (_: Throwable) {
            -1
        }
    }

    fun sample(): CpuMetrics {
        val usage = runCatching {
            val statFile = File("/proc/stat")
            if (statFile.exists()) {
                statFile.bufferedReader().use { reader ->
                    val line = reader.readLine() ?: ""
                    computeUsageFromLine(line)
                }
            } else -1
        }.getOrDefault(-1)

        val frequencyMhz = runCatching {
            val curFreqFile = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
            val fallbackFreqFile = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_cur_freq")
            val target = when {
                curFreqFile.exists() -> curFreqFile
                fallbackFreqFile.exists() -> fallbackFreqFile
                else -> null
            }
            target?.readText()?.trim()?.toIntOrNull()?.let { khz -> khz / 1000 } ?: -1
        }.getOrDefault(-1)

        return CpuMetrics(
            usagePercent = if (usage >= 0) usage else lastCalculatedUsage,
            coreCount = coreCount,
            frequencyMhz = frequencyMhz,
        )
    }

    fun reset() {
        prevTotal = 0L
        prevIdle = 0L
        lastCalculatedUsage = -1
    }
}
