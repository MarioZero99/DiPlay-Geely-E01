package com.shilapi.xcertplay.instrument.fly

internal class FlyPainter(
    private val slot: FlyFrameSlot,
    private val rgb: ByteArray,
    private val draw: (ByteArray, Int, Int) -> Boolean,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long,
    private val measure: (() -> Unit) -> Long,
) {
    private var frameLogged = false
    private var layoutLogged = false
    private var lastSlowLogMs = Long.MIN_VALUE

    fun paintOne(): Boolean {
        val taken = slot.take() ?: return false
        val (yuv, length, meta) = taken
        var wrote = false
        val spentConvert = try {
            measure { wrote = FlyYuv.toRgb565(yuv, length, meta, rgb) }
        } finally {
            slot.release()
        }
        if (!wrote) {
            if (!layoutLogged) {
                layoutLogged = true
                log("fly.display paint layout")
            }
            return false
        }
        var posted = false
        val spentDraw = measure { posted = draw(rgb, meta.width, meta.height) }
        if (!posted) return false
        if (!frameLogged) {
            frameLogged = true
            log("fly.display frame")
        }
        val spent = spentConvert + spentDraw
        val now = nowMs()
        if (spent > PAINT_BUDGET_MS && (lastSlowLogMs == Long.MIN_VALUE || now - lastSlowLogMs >= SLOW_LOG_GAP_MS)) {
            lastSlowLogMs = now
            log("fly.display paint slow $spent")
        }
        return true
    }

    private companion object {
        const val PAINT_BUDGET_MS = 166L
        const val SLOW_LOG_GAP_MS = 5_000L
    }
}

internal fun flyDecoderFailure(error: Throwable): String {
    val top = error.stackTrace.firstOrNull()
    val at = if (top == null) "none" else "${top.fileName}:${top.lineNumber}"
    val detail = error.message?.lineSequence()?.firstOrNull()?.take(80) ?: ""
    return "fly.display decoder failed ${error.javaClass.simpleName} $detail at $at"
}
