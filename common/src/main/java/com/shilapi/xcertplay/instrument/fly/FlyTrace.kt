package com.shilapi.xcertplay.instrument.fly

import com.shilapi.xcertplay.DiagLog
import com.shilapi.xcertplay.system.RootShellBridge

/**
 * 1885 出没出去，只有 Extraservice 的 logcat 知道。
 * 车机主缓冲大约 11 秒就被冲掉，诊断报告又只收本进程的行，所以在三指广播发出后马上抽一次。
 * sendBroadcast 先返回，对端 onReceive 还没跑，所以稍等再抽。抽到的行写入会话日志。
 */
internal object FlyTrace {
    private const val TAG = ClusterFlyLog.TAG

    /** 给 Extraservice 主线程跑完接收器。仍远小于主缓冲被冲掉的时间。 */
    internal const val CAPTURE_DELAY_MILLIS = 200L

    /** 必须放进 ExtraUtils 的 220 字节命令上限，含退出码包装。只收 tag Extra，Debug 才有三指那几行。 */
    internal const val COMMAND = "logcat -d -t 200 -s Extra:D '*:S'"

    internal var dumpOverrideForTest: (() -> Pair<Int?, String>)? = null

    private val keep = listOf(
        "ThreeFingerReceiver",
        "导航状态",
    )

    fun captureAfterGesture() {
        val override = dumpOverrideForTest
        if (override != null) {
            record(override())
            return
        }
        Thread({
            try {
                Thread.sleep(CAPTURE_DELAY_MILLIS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            record(dumpFromDevice())
        }, "fly-trace").apply { isDaemon = true }.start()
    }

    internal fun select(body: String): List<String> {
        return body.lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && keep.any { key -> line.contains(key) } }
            .toList()
            .takeLast(16)
    }

    private fun record(dump: Pair<Int?, String>) {
        val rc = dump.first
        val body = dump.second
        if (rc != null && rc != 0) {
            DiagLog.w(TAG, "fly.extra logcat rc=$rc body=${body.take(160)}")
        }
        val lines = select(body)
        if (lines.isEmpty()) {
            DiagLog.i(TAG, "fly.extra empty. No ThreeFingerReceiver line in tag Extra.")
            return
        }
        lines.forEach { line -> DiagLog.i(TAG, "fly.extra $line") }
    }

    private fun dumpFromDevice(): Pair<Int?, String> {
        if (!RootShellBridge.isAvailable()) {
            DiagLog.w(TAG, "fly.extra skipped, ExtraUtilsService missing")
            return null to ""
        }
        return RootShellBridge.executeWithOutput(COMMAND, timeoutMillis = RootShellBridge.ROOT_COMMAND_TIMEOUT_MILLIS)
    }
}
