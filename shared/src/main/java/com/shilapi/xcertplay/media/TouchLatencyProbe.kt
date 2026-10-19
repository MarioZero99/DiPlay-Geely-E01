package com.shilapi.xcertplay.media

/**
 * Diagnostic link between the touch uplink and the video pipeline, read by [VideoStats].
 *
 * One touch, four numbers, disjoint segments that add up:
 * `input` (kernel dispatch + UI thread) + `touchQueue` (waiting for the sender) +
 * `touchSendMax` (the write itself) + `touch2frame` (phone + network) ≈ finger to arrival.
 * `touch2render` closes the same origin at the frame actually handed to the surface.
 */
internal object TouchLatencyProbe {

    /** A touch whose frame never came is not a 3-second latency sample. */
    private const val MAX_SAMPLE_NS = 500_000_000L

    @Volatile private var pendingArrivalNs = 0L
    @Volatile private var pendingRenderNs = 0L
    @Volatile var maxSendNs = 0L

    private var inputSamples = 0
    private var inputSumNs = 0L
    private var inputMaxNs = 0L
    private var queueSamples = 0
    private var queueSumNs = 0L
    private var queueMaxNs = 0L
    private var coalescedMoves = 0
    private var staleSamples = 0
    private var droppedPackets = 0

    /** `eventTime` → `sendTouch` entry: kernel dispatch plus whatever the UI thread did first. */
    @Synchronized fun onInputDelay(nanos: Long) {
        inputSamples++
        inputSumNs += nanos
        inputMaxNs = maxOf(inputMaxNs, nanos)
    }

    @Synchronized fun onQueueDelay(nanos: Long) {
        queueSamples++
        queueSumNs += nanos
        queueMaxNs = maxOf(queueMaxNs, nanos)
    }

    @Synchronized fun onMoveCoalesced() { coalescedMoves++ }

    /** A packet that never reached the phone because its session had already ended. */
    @Synchronized fun onDropped() { droppedPackets++ }

    /** [originNs] is the finger's own instant, not the moment the send task started. */
    fun onTouchSent(originNs: Long, sendDurationNs: Long) {
        if (pendingArrivalNs == 0L) pendingArrivalNs = originNs
        if (pendingRenderNs == 0L) pendingRenderNs = originNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    /** Returns touch-to-arrival latency for the first packet after a touch, or -1. */
    fun onFrame(nowNs: Long): Long = consume(nowNs, render = false)

    /** Returns touch-to-render latency for the first frame put on screen after a touch, or -1. */
    fun onRendered(nowNs: Long): Long = consume(nowNs, render = true)

    private fun consume(nowNs: Long, render: Boolean): Long {
        val pending = synchronized(this) {
            val current = if (render) pendingRenderNs else pendingArrivalNs
            if (render) pendingRenderNs = 0L else pendingArrivalNs = 0L
            current
        }
        if (pending == 0L) return -1L
        val latency = nowNs - pending
        if (latency < 0L || latency > MAX_SAMPLE_NS) {
            synchronized(this) { staleSamples++ }
            return -1L
        }
        return latency
    }

    /** One 5-second window of everything that is not a per-touch sample. Resets as it reports. */
    @Synchronized fun queueSummary(): String {
        val inputAvg = if (inputSamples == 0) -1 else inputSumNs / inputSamples / 1_000_000
        val queueAvg = if (queueSamples == 0) -1 else queueSumNs / queueSamples / 1_000_000
        val result = " input avg=${inputAvg}ms max=${inputMaxNs / 1_000_000}ms" +
            " touchQueue avg=${queueAvg}ms max=${queueMaxNs / 1_000_000}ms" +
            " coalesced=$coalescedMoves stale=$staleSamples dropped=$droppedPackets"
        inputSamples = 0
        inputSumNs = 0
        inputMaxNs = 0
        queueSamples = 0
        queueSumNs = 0
        queueMaxNs = 0
        coalescedMoves = 0
        staleSamples = 0
        droppedPackets = 0
        return result
    }

    /** Test seam: the object is a process-wide singleton, so tests must not inherit state. */
    @Synchronized fun resetForTest() {
        pendingArrivalNs = 0L
        pendingRenderNs = 0L
        maxSendNs = 0L
        inputSamples = 0
        inputSumNs = 0
        inputMaxNs = 0
        queueSamples = 0
        queueSumNs = 0
        queueMaxNs = 0
        coalescedMoves = 0
        staleSamples = 0
        droppedPackets = 0
    }

    @Synchronized fun staleForTest(): Int = staleSamples
}
