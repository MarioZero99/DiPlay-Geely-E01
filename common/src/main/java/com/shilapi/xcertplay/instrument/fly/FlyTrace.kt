package com.shilapi.xcertplay.instrument.fly

import com.shilapi.xcertplay.DiagLog
import com.shilapi.xcertplay.system.RootShellBridge

/** What Extra's log says about the page this swipe asked for. */
enum class PageProof { OPENED, CLOSED, DENIED, DROPPED, MISSING, UNCONFIRMED }

data class PageWitness(val proof: PageProof)

/**
 * 1885 出没出去，只有 Extraservice 的 logcat 知道。
 * 车机主缓冲大约 11 秒就被冲掉，诊断报告又只收本进程的行，所以在三指广播发出后马上抽一次。
 * sendBroadcast 先返回，对端 onReceive 还没跑，所以稍等再抽。抽到的行写入会话日志。
 */
internal object FlyTrace {
    private const val TAG = ClusterFlyLog.TAG

    /** 给 Extraservice 主线程跑完接收器。仍远小于主缓冲被冲掉的时间。 */
    internal const val CAPTURE_DELAY_MILLIS = 200L

    /** 首次未抓到新行时再等一次，避免车机主线程调度慢导致的误判。 */
    internal const val RETRY_DELAY_MILLIS = 250L

    /** 必须放进 ExtraUtils 的 220 字节命令上限，含退出码包装。带 threadtime 方便去重对齐。 */
    internal const val COMMAND = "logcat -d -v threadtime -t 200 -s Extra:D '*:S'"

    internal var dumpOverrideForTest: (() -> Pair<Int?, String>)? = null

    /** Each key: the word in the log and the substring that proves Extra processed it. */
    private val keys = listOf(
        "导航状态" to "导航状态",
        "mmi" to "setMMI_NavigationMod_indicator",
        "navi-front" to "Navi front",
        "navi-back" to "Navi back",
        "threefinger" to "ThreeFingerReceiver",
    )

    /** The page-proof tokens. `setInfo` prints the whole 25-byte array, so only its prefix is fixed. */
    private const val NAVIGATION = "ThreeFingerReceiver isNavigation = "
    private const val LEFT = "ThreeFingerReceiver left"
    private const val RIGHT = "ThreeFingerReceiver right"
    private const val OPEN_INFO = "setInfo = [74, 1,"
    private const val CLOSE_INFO = "setInfo = [74, 2,"

    /**
     * Compares two dumps of the same logcat. Only lines that arrived after [before] count: an old
     * `setInfo` still in the ring buffer belongs to an earlier swipe.
     */
    fun witness(before: String, after: String): PageWitness {
        val delta = newLines(before, after).joinToString("\n")
        val hasNavigation = delta.contains(NAVIGATION)
        val hasLeft = delta.contains(LEFT) && delta.contains(OPEN_INFO)
        val hasRight = delta.contains(RIGHT) && delta.contains(CLOSE_INFO)
        val proof = when {
            hasLeft -> PageProof.OPENED
            hasRight -> PageProof.CLOSED
            hasNavigation && lastNavigation(delta) == false -> PageProof.DENIED
            hasNavigation -> PageProof.DROPPED
            else -> PageProof.MISSING
        }
        return PageWitness(proof)
    }

    /** Returns only the lines in [after] that follow the overlapping suffix of [before]. */
    internal fun newLines(before: String, after: String): List<String> {
        val beforeLines = before.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.toList()
        val afterLines = after.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.toList()
        if (beforeLines.isEmpty() || afterLines.isEmpty()) return afterLines
        val maxOverlap = minOf(beforeLines.size, afterLines.size)
        for (overlap in maxOverlap downTo 1) {
            if (beforeLines.subList(beforeLines.size - overlap, beforeLines.size) ==
                afterLines.subList(0, overlap)
            ) {
                return afterLines.subList(overlap, afterLines.size)
            }
        }
        return afterLines
    }

    private fun lastNavigation(body: String): Boolean? {
        val at = body.lastIndexOf(NAVIGATION)
        if (at < 0) return null
        return body.startsWith("true", at + NAVIGATION.length)
    }

    /** One dump on the calling thread. `null` means the dump failed, not that the log was empty. */
    fun snapshot(): String? {
        val override = dumpOverrideForTest
        if (override != null) return override().second
        if (!RootShellBridge.isAvailable()) return null
        val dump = dumpFromDevice()
        return if (dump.first == 0) dump.second else null
    }

    /**
     * Logs the dump as before, then reports what it says about the page.
     *
     * [before] is the dump taken just before the gesture; `null` means that dump failed, and the
     * page is reported `UNCONFIRMED` without comparing an empty string to [after].
     */
    fun capture(which: String, before: String? = "", onWitness: (PageWitness) -> Unit = {}) {
        val override = dumpOverrideForTest
        if (override != null) {
            val dump = override()
            record(which, dump)
            onWitness(if (before == null) PageWitness(PageProof.UNCONFIRMED) else witnessOf(dump, before))
            return
        }
        Thread({
            try {
                Thread.sleep(CAPTURE_DELAY_MILLIS)
            } catch (_: InterruptedException) {
                onWitness(PageWitness(PageProof.UNCONFIRMED))
                return@Thread
            }
            var dump = dumpFromDevice()
            var result = resolveDeviceWitness(before, dump)
            if (result.proof == PageProof.MISSING) {
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS)
                    dump = dumpFromDevice()
                    result = resolveDeviceWitness(before, dump)
                } catch (_: InterruptedException) {
                    onWitness(PageWitness(PageProof.UNCONFIRMED))
                    return@Thread
                }
            }
            record(which, dump)
            onWitness(result)
        }, "fly-trace").apply { isDaemon = true }.start()
    }

    /**
     * When the main logcat ring buffer has been flushed by other processes (no Extra gate lines
     * remain at all), treat a `MISSING` diff as `UNCONFIRMED` so the cluster's own 3s surface
     * timeout decides instead of aborting on a wiped buffer.
     */
    internal fun resolveDeviceWitness(before: String?, dump: Pair<Int?, String>): PageWitness {
        if (before == null) return PageWitness(PageProof.UNCONFIRMED)
        val result = witnessOf(dump, before)
        if (result.proof == PageProof.MISSING && select(dump.second).isEmpty()) {
            return PageWitness(PageProof.UNCONFIRMED)
        }
        return result
    }

    private fun witnessOf(dump: Pair<Int?, String>, before: String): PageWitness {
        val rc = dump.first
        if (rc == null || rc != 0) return PageWitness(PageProof.UNCONFIRMED)
        return witness(before, dump.second)
    }

    internal fun select(body: String): List<String> {
        return body.lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && keys.any { (_, key) -> line.contains(key) } }
            .toList()
            .takeLast(16)
    }

    private fun record(which: String, dump: Pair<Int?, String>) {
        val rc = dump.first
        val body = dump.second
        if (rc != null && rc != 0) {
            DiagLog.w(TAG, "fly.extra logcat rc=$rc body=${body.take(160)}")
        }
        select(body).forEach { line -> DiagLog.i(TAG, "fly.extra $which $line") }
        // All five are written every time: a miss is the evidence that the line was not there, and
        // only a hit tells us Extra actually ran the receiver.
        keys.forEach { (label, key) ->
            val hit = body.contains(key)
            DiagLog.i(TAG, "fly.extra $label ${if (hit) "hit" else "miss"}")
        }
    }

    private fun dumpFromDevice(): Pair<Int?, String> {
        if (!RootShellBridge.isAvailable()) {
            DiagLog.w(TAG, "fly.extra skipped, ExtraUtilsService missing")
            return null to ""
        }
        return RootShellBridge.executeWithOutput(COMMAND, timeoutMillis = RootShellBridge.ROOT_COMMAND_TIMEOUT_MILLIS)
    }
}
