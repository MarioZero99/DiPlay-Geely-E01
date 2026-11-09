package com.shilapi.xcertplay.instrument

import com.shilapi.xcertplay.DiagLog
import com.shilapi.xcertplay.system.RootShellBridge

/**
 * 歌名进没进 CAN，只有东软进程的 logcat 知道。
 * 车机主缓冲大约 11 秒就被 MCU 日志冲掉，所以在 binder 返回后立刻抽一次。
 * 抽到的行写入会话日志，诊断报告不用再靠 adb。
 */
internal object IpkTrace {
    private const val TAG = "XingruiInstrument"

    /** 必须放进 ExtraUtils 的 220 字节命令上限，含退出码包装。 */
    internal const val COMMAND =
        "logcat -d -t 40 -s MediaBean:I CAN_SetInfo:I diminteraction:I '*:S'"

    internal var dumpOverrideForTest: (() -> Pair<Int?, String>)? = null

    private val keep = listOf(
        "FE-6",
        "Media---->IPK",
        "source disconnect",
        "不属于音乐",
        "MediaService updateMedia",
        "MediaService onSourceChanged",
        "registerCallBack",
        "cannot send",
        "music result",
    )

    fun capture() {
        val dump = dumpOverrideForTest?.invoke() ?: dumpFromDevice()
        val rc = dump.first
        val body = dump.second
        if (rc != 0) {
            DiagLog.w(TAG, "IPK trace logcat rc=$rc body=${body.take(160)}")
        }
        val lines = select(body)
        if (lines.isEmpty()) {
            DiagLog.i(TAG, "IPK trace empty. No MediaBean, CAN_SetInfo or diminteraction decision in the buffer.")
            return
        }
        lines.forEach { line -> DiagLog.i(TAG, "IPK $line") }
    }

    internal fun select(body: String): List<String> {
        return body.lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && keep.any { key -> line.contains(key) } }
            .toList()
            .takeLast(24)
    }

    private fun dumpFromDevice(): Pair<Int?, String> {
        if (!RootShellBridge.isAvailable()) {
            DiagLog.w(TAG, "IPK trace skipped, ExtraUtilsService missing")
            return null to ""
        }
        return RootShellBridge.executeWithOutput(COMMAND, timeoutMillis = RootShellBridge.ROOT_COMMAND_TIMEOUT_MILLIS)
    }
}
