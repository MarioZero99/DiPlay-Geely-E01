package com.shilapi.xcertplay

/**
 * What the run cost, in milliseconds, in a form a report can carry off the head unit.
 *
 * The session log already says what happened: every STEP line names the rung the link reached, and the
 * media counters say how fast the picture moved. Neither says what a rung *cost*. A bring-up that sat
 * nine seconds on `usb/config` and one that sat nine seconds on the phone's own certificate read the
 * same in the log — both are one line — and the two have nothing to do with each other. So the
 * question a driver actually asks ("why is this slow") is the one the file cannot answer.
 *
 * Shape follows what the report can afford to print. Durations are kept as a fixed ring per name, so
 * the report quotes p50/p95/max instead of a wall of samples, and a slow tail — the thing that is felt
 * — is not averaged away. Marks keep the order things happened in and the gap before each one, which
 * is the same information the STEP lines have, with the waiting put back into it.
 *
 * Overhead is the design constraint, not an afterthought: this object is called from the 60 fps video
 * path and from a once-a-second tick on a head unit with 26 MB free. Recording is one clock read, one
 * long write into a fixed array and one short lock; nothing is formatted, sorted or printed until
 * [summary] is asked. No allocation per sample, no I/O, no unbounded map — a name that is not one of
 * the fixed call sites is dropped rather than allowed to grow the heap.
 */
object PerfTrace {
    /** Samples kept per name. At 60 fps this is the last eight seconds; for a once-a-second tick, eight minutes. */
    const val SAMPLE_CAPACITY = 512

    /** Marks kept in total. A run that reconnects ten times still keeps the newest run's whole ladder. */
    const val MARK_CAPACITY = 64

    /** Fixed call sites only: this caps what a typo or a per-stream suffix can cost in memory. */
    private const val MAX_SERIES = 32

    /** One slow tick should not put one line per tick into the file. */
    private const val WARN_INTERVAL_MILLIS = 5_000L

    private const val MARKS_SHOWN = 40

    private const val TAG = "DiPlay-Perf"

    /** The only clock this object reads. Test seam: a fake one makes every number below deterministic. */
    internal var clock: () -> Long = System::nanoTime

    /**
     * Where an over-budget sample is announced. Overridable for the same reason as [clock]: a plain
     * JVM test has no `android.util.Log`, and a timing test should not need a device to run.
     */
    internal var warn: (String) -> Unit = { DiagLog.w(TAG, it) }

    private val lock = Any()
    private val series = HashMap<String, Series>(16)
    private val marks = ArrayDeque<Mark>()
    private val chainClocks = HashMap<String, Long>(4)
    private var droppedSeries = 0L

    /**
     * The first mark's clock, so every mark also carries a time on one shared line.
     *
     * The per-chain gap answers "how long did this step take"; it does not answer "was the first
     * frame after the link came up, or before". Printing both is what stops a reader adding up gaps
     * that belong to different chains.
     */
    private var originNs = 0L

    /**
     * Records one measurement that the caller took itself — a duration whose ends are not in one
     * function, or one that came from a system clock rather than [clock].
     */
    fun record(name: String, millis: Long, warnAboveMs: Long = Long.MAX_VALUE) {
        val value = millis.coerceAtLeast(0L)
        var overrunMs = 0L
        synchronized(lock) {
            val target = series[name] ?: newSeries(name) ?: return
            target.add(value, warnAboveMs)
            // The wall clock is only read on the rare path: this runs once per video frame, and a
            // `currentTimeMillis` there would be a cost the measurement itself pays for.
            if (target.budget != Long.MAX_VALUE && value > target.budget) {
                val now = System.currentTimeMillis()
                if (now - target.lastWarningAt >= WARN_INTERVAL_MILLIS) {
                    target.lastWarningAt = now
                    overrunMs = target.budget
                }
            }
        }
        // Outside the lock: the sink has a lock of its own, and a slow log write must not hold up a
        // frame thread that is only trying to record a number.
        if (overrunMs != 0L) warn("$name took ${value}ms (budget ${overrunMs}ms)")
    }

    /** Times [block], records the result under [name], and returns what the block returned. */
    fun <T> span(name: String, warnAboveMs: Long = Long.MAX_VALUE, block: () -> T): T {
        val started = clock()
        try {
            return block()
        } finally {
            record(name, (clock() - started) / 1_000_000L, warnAboveMs)
        }
    }

    /** Starts a span whose end is in another call. Pair with [end] — the token is the start time. */
    fun begin(): Long = clock()

    fun end(name: String, startedNs: Long, warnAboveMs: Long = Long.MAX_VALUE) {
        record(name, (clock() - startedNs) / 1_000_000L, warnAboveMs)
    }

    /**
     * A point in the run, with the milliseconds since the previous point **on the same chain**.
     *
     * Chains are separate because the things they measure are: `startup` has no business reporting
     * "183 s since the last connection step", and a second connection attempt must not inherit the
     * first one's clock. Returns the gap, or -1 when this is the chain's first mark.
     */
    fun mark(chain: String, name: String): Long {
        val now = clock()
        synchronized(lock) {
            if (originNs == 0L) originNs = now
            val previous = chainClocks[chain]
            chainClocks[chain] = now
            val deltaMs = if (previous == null) -1L else (now - previous) / 1_000_000L
            if (marks.size >= MARK_CAPACITY) marks.removeFirst()
            marks.addLast(Mark(name, deltaMs, (now - originNs) / 1_000_000L))
            return deltaMs
        }
    }

    /** A mark on the `run` chain, for call sites that have only one timeline. */
    fun mark(name: String): Long = mark("run", name)

    /**
     * The block the report prints. Empty runs say so instead of printing a heading with nothing under
     * it — "no samples" and "all zeros" are different answers.
     */
    fun summary(): String {
        val snapshots: List<Snapshot>
        val taken: List<Mark>
        val dropped: Long
        synchronized(lock) {
            snapshots = series.values.map { it.snapshot() }
            taken = marks.toList()
            dropped = droppedSeries
        }
        if (snapshots.isEmpty() && taken.isEmpty()) return "Perf: nothing sampled yet."
        // Built with a StringBuilder rather than buildString: the inline lambda would be compiled into
        // every caller, and the snapshot type it reads is private to this file.
        val out = StringBuilder(1_024)
        out.append("--- Performance (ms; p50/p95 over the last ")
            .append(SAMPLE_CAPACITY).append(" samples, worst p95 first) ---\n")
        for (entry in snapshots.sortedWith(compareByDescending<Snapshot> { it.p95 }.thenByDescending { it.max })) {
            out.append("  ").append(entry.name.padEnd(22))
                .append("n=").append(entry.kept)
                .append(" p50=").append(entry.p50)
                .append(" p95=").append(entry.p95)
                .append(" max=").append(entry.max)
                .append(" sum=").append(entry.totalMs)
            if (entry.budget != Long.MAX_VALUE) out.append(" over=").append(entry.over).append('/').append(entry.seen)
            out.append('\n')
        }
        if (dropped > 0) out.append("  (").append(dropped).append(" timings dropped: name budget ").append(MAX_SERIES).append(")\n")
        if (taken.isNotEmpty()) {
            out.append("  --- marks: +ms since the previous mark on its chain; t=ms since the first mark ---\n")
            val shown = if (taken.size > MARKS_SHOWN) taken.takeLast(MARKS_SHOWN) else taken
            if (taken.size > MARKS_SHOWN) {
                out.append("  (").append(taken.size - MARKS_SHOWN).append(" earlier marks dropped)\n")
            }
            for (mark in shown) {
                out.append("  ")
                out.append((if (mark.deltaMs < 0) "--" else "+${mark.deltaMs}ms").padStart(8))
                out.append("  t=").append(mark.atMs.toString().padStart(6)).append("ms  ").append(mark.name).append('\n')
            }
        }
        return out.toString().trimEnd()
    }

    /** Test seam, and the start of a fresh report after a reconnect storm. */
    fun reset() {
        synchronized(lock) {
            series.clear()
            marks.clear()
            chainClocks.clear()
            droppedSeries = 0L
            originNs = 0L
        }
    }

    /** Null when the name budget is already spent; the drop is counted and reported. */
    private fun newSeries(name: String): Series? {
        if (series.size >= MAX_SERIES) {
            droppedSeries++
            return null
        }
        return Series(name).also { series[name] = it }
    }

    private class Mark(val name: String, val deltaMs: Long, val atMs: Long)

    private class Series(private val name: String) {
        private val samples = LongArray(SAMPLE_CAPACITY)
        private var cursor = 0
        var kept = 0
            private set
        var seen = 0L
            private set
        private var totalMs = 0L
        private var maxMs = 0L
        var budget = Long.MAX_VALUE
            private set
        var over = 0L
            private set
        var lastWarningAt = 0L

        fun add(valueMs: Long, warnAboveMs: Long) {
            if (warnAboveMs < budget) budget = warnAboveMs
            samples[cursor] = valueMs
            cursor = (cursor + 1) % SAMPLE_CAPACITY
            if (kept < SAMPLE_CAPACITY) kept++
            seen++
            totalMs += valueMs
            if (valueMs > maxMs) maxMs = valueMs
            if (budget != Long.MAX_VALUE && valueMs > budget) over++
        }

        fun snapshot(): Snapshot {
            val values = samples.copyOf(kept)
            values.sort()
            return Snapshot(
                name = name,
                kept = kept,
                seen = seen,
                p50 = percentile(values, 50),
                p95 = percentile(values, 95),
                max = maxMs,
                totalMs = totalMs,
                budget = budget,
                over = over,
            )
        }

        /** Nearest-rank: the smallest value at or above the requested share of the run. */
        private fun percentile(sorted: LongArray, percent: Int): Long {
            if (sorted.isEmpty()) return 0L
            val rank = ((percent.toLong() * sorted.size + 99) / 100).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }

    private class Snapshot(
        val name: String,
        val kept: Int,
        val seen: Long,
        val p50: Long,
        val p95: Long,
        val max: Long,
        val totalMs: Long,
        val budget: Long,
        val over: Long,
    )
}
