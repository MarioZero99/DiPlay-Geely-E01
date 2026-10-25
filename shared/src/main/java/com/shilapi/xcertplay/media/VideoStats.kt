package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.DiagLog
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
data class VideoTelemetrySnapshot(
    val rxFps: Float,
    val shownFps: Float,
    val touchLatencyMs: Long,
    val recoveries: Int,
)

internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private val lastArrivalNs = AtomicLong(0L)
    private val received = AtomicInteger(0)
    private val rendered = AtomicInteger(0)
    private val recoveries = AtomicInteger(0)
    private val bytes = AtomicLong(0L)
    private val maxArrivalGapNs = AtomicLong(0L)
    private val touchLock = Any()
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private val renderLock = Any()
    private var renderSamples = 0
    private var renderLatencySumNs = 0L
    private var maxRenderLatencyNs = 0L

    private val cumulativeReceived = AtomicLong(0L)
    private val cumulativeRendered = AtomicLong(0L)
    @Volatile private var telemetryTimeNs = nanoTime()
    @Volatile private var telemetryReceived = 0L
    @Volatile private var telemetryRendered = 0L
    @Volatile private var lastTelemetryShownFps = 0.0f
    @Volatile private var lastTelemetryRxFps = 0.0f

    fun onReceived(size: Int) {
        val now = nanoTime()
        val prevArrival = lastArrivalNs.getAndSet(now)
        val gap = now - prevArrival
        if (prevArrival != 0L && gap < IDLE_GAP_NS) {
            maxArrivalGapNs.updateAndGet { current -> maxOf(current, gap) }
        }
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            synchronized(touchLock) {
                touchSamples++
                touchLatencySumNs += touchLatency
                maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
            }
        }
        received.incrementAndGet()
        cumulativeReceived.incrementAndGet()
        bytes.addAndGet(size.toLong())
    }

    /**
     * [nowNs] is taken before `releaseOutputBuffer`, so this closes when the buffer is handed to the
     * surface — not when the pixel lights up. It is still one composite plus one vsync short, which
     * is why it compares versions rather than claiming an absolute number.
     */
    fun onRendered(nowNs: Long) {
        rendered.incrementAndGet()
        cumulativeRendered.incrementAndGet()
        // Only the main screen carries touches; a second stream must not consume their samples.
        if (label.isNotEmpty()) return
        val latency = TouchLatencyProbe.onRendered(nowNs)
        if (latency >= 0) {
            synchronized(renderLock) {
                renderSamples++
                renderLatencySumNs += latency
                maxRenderLatencyNs = maxOf(maxRenderLatencyNs, latency)
            }
        }
    }

    fun onRecovery() { recoveries.incrementAndGet() }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        val rcv = received.getAndSet(0)
        val (tSamples, tLatencySum, maxTouch) = synchronized(touchLock) {
            val s = touchSamples
            val sum = touchLatencySumNs
            val max = maxTouchLatencyNs
            touchSamples = 0
            touchLatencySumNs = 0L
            maxTouchLatencyNs = 0L
            Triple(s, sum, max)
        }
        if (rcv == 0 && tSamples == 0) { windowStartNs = now; return null }
        val rnd = rendered.getAndSet(0)
        val rec = recoveries.getAndSet(0)
        val b = bytes.getAndSet(0L)
        val maxGap = maxArrivalGapNs.getAndSet(0L)
        val (rSamples, rLatencySum, maxRender) = synchronized(renderLock) {
            val s = renderSamples
            val sum = renderLatencySumNs
            val max = maxRenderLatencyNs
            renderSamples = 0
            renderLatencySumNs = 0L
            maxRenderLatencyNs = 0L
            Triple(s, sum, max)
        }

        val touchAvgMs = if (tSamples == 0) -1 else tLatencySum / tSamples / 1_000_000
        val renderAvgMs = if (rSamples == 0) -1 else rLatencySum / rSamples / 1_000_000
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touch2render avg=%dms max=%dms n=%d " +
            "touchSendMax=%dms").format(
            rcv / seconds, rnd / seconds, maxGap / 1_000_000,
            (b * 8 / 1000 / seconds).toLong(), rec,
            touchAvgMs, maxTouch / 1_000_000, tSamples,
            renderAvgMs, maxRender / 1_000_000, rSamples,
            TouchLatencyProbe.maxSendNs / 1_000_000,
        ) + (if (label.isEmpty()) TouchLatencyProbe.queueSummary() else "")
        TouchLatencyProbe.maxSendNs = 0
        DiagLog.i(TAG, line)
        windowStartNs = now
        return line
    }

    /**
     * 当前投屏瞬时遥测快照，只读差值，严禁重置计数器以保护 5 秒官方诊断日志。
     */
    fun readCurrentTelemetry(): VideoTelemetrySnapshot {
        val now = nanoTime()
        val curRendered = cumulativeRendered.get()
        val curReceived = cumulativeReceived.get()
        val elapsedNs = now - telemetryTimeNs
        if (elapsedNs >= 300_000_000L) {
            val seconds = elapsedNs / 1e9
            val diffRendered = curRendered - telemetryRendered
            val diffReceived = curReceived - telemetryReceived
            lastTelemetryShownFps = (diffRendered / seconds).toFloat()
            lastTelemetryRxFps = (diffReceived / seconds).toFloat()
            telemetryRendered = curRendered
            telemetryReceived = curReceived
            telemetryTimeNs = now
        }
        val touchAvg = synchronized(renderLock) {
            if (renderSamples > 0) renderLatencySumNs / renderSamples / 1_000_000 else -1L
        }
        return VideoTelemetrySnapshot(
            rxFps = lastTelemetryRxFps,
            shownFps = lastTelemetryShownFps,
            touchLatencyMs = touchAvg,
            recoveries = recoveries.get(),
        )
    }


    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}
