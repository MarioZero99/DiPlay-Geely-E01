package com.shilapi.xcertplay

/**
 * Fits a report into a byte budget without spending it on repetition.
 *
 * The field reports were four near-identical runs of "started, looked for a USB device, found none":
 * the budget went on saying the same thing four times, and the line that explained the failure was
 * never in the file. So the budget is spent in a fixed order — signal lines from every run first, then
 * the newest run in full, then older runs — and every run's consecutive repeats cost one line and a
 * count instead of one line each. Whatever does not fit is stated, because a report that quietly loses
 * a line reads exactly like a run where the line never happened.
 *
 * The kept lines are then put back in the order they happened, run by run, because a reader has to be
 * able to read the report top to bottom.
 */
internal object DiagnosticDigest {
    /** How much this digest keeps. Saving and uploading keep every retained line instead of cutting here. */
    const val BUDGET_BYTES = 1024 * 1024

    private const val HEADER_KEEP_FRACTION = 0.25

    /** Room kept for the closing "N lines omitted" note, which is not a log line. */
    private const val NOTE_RESERVE = 96

    /** Lines a reader must not have to hunt for: the ones that say a step failed or never ran. */
    private val SIGNAL = Regex(
        "(?i)(^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+)?(STEP |ERROR |FATAL )" +
            "|failed|denied|rejected|refused|retry|unavailable|timeout|exception|cannot|unable" +
            "|dropped=[1-9]|underruns=\\+[1-9]|recoveries=[1-9]|not present|no such",
    )

    /** Periodic counters: worth the newest of each, not the history of all. */
    private val STAT = Regex("(?i)\\bstats\\b|\\brx=|\\bfps=")

    /** Every line on disk is prefixed by the writer's timestamp; it is not part of a counter's identity. */
    private val TIMESTAMP_PREFIX = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+")

    fun isSignal(line: String): Boolean = SIGNAL.containsMatchIn(line)

    /** Consecutive repeats become one line and a count. */
    fun collapseRuns(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var index = 0
        while (index < lines.size) {
            var end = index
            while (end + 1 < lines.size && lines[end + 1] == lines[index]) end++
            val repeats = end - index + 1
            out.add(if (repeats == 1) lines[index] else "${lines[index]} (×$repeats)")
            index = end + 1
        }
        return out
    }

    fun compose(header: String, sections: List<DiagnosticSection>, budgetBytes: Int = BUDGET_BYTES): String {
        val headerBudget = (budgetBytes * HEADER_KEEP_FRACTION).toInt().coerceAtLeast(1)
        val keptHeader = header.toByteArray(Charsets.UTF_8).let { bytes ->
            if (bytes.size <= headerBudget) header else String(bytes, 0, safeSplit(bytes, headerBudget), Charsets.UTF_8)
        }
        val used = keptHeader.toByteArray(Charsets.UTF_8).size
        if (sections.isEmpty()) return keptHeader

        // One running index across the sections, in read order — oldest run first, which is the order
        // REPORT_NAMES lists them. A per-section index would restart at every run, and sorting on it would
        // group every run's first line together, then every run's second, reading the timestamps backwards.
        val candidates = ArrayList<Ranked>()
        val newestStat = HashMap<String, Int>()
        var sequence = 0
        sections.forEachIndexed { run, section ->
            for (line in collapseRuns(section.lines)) {
                val kind = when {
                    isSignal(line) -> 1
                    section.newest -> 2
                    else -> 3
                }
                val stat = if (STAT.containsMatchIn(line) && kind != 1) statKind(line) else null
                if (stat != null && sequence > newestStat.getOrDefault(stat, -1)) newestStat[stat] = sequence
                candidates.add(Ranked(kind, sequence, run, stat, line))
                sequence++
            }
        }
        // One counter line per kind, the newest: a steady run's earlier samples say nothing the last one
        // does not, and the history of them is what displaces the lines that do explain a failure.
        val ordered = candidates
            .filter { it.stat == null || newestStat[it.stat] == it.sequence }
            // Newest first within a tier: when a budget is short the most recent failure is the one asked about.
            .sortedWith(compareBy({ it.kind }, { -it.sequence }))

        // Each run's label and the closing note are bytes in the report too. Reserving them here is what
        // keeps the result inside the cap instead of one note over it.
        val labels = sections.indices.filter { sections[it].lines.isNotEmpty() }.sumOf { byteSize(label(sections[it].name)) + 1 }
        var room = budgetBytes - used - NOTE_RESERVE - labels
        if (room <= 0) return keptHeader
        var omitted = 0
        val kept = ArrayList<Ranked>()
        for (entry in ordered) {
            val cost = byteSize(entry.line) + 1
            if (cost > room) { omitted++; continue }
            room -= cost
            kept.add(entry)
        }
        if (kept.isEmpty()) return keptHeader
        kept.sortBy { it.sequence }

        return buildString {
            append(keptHeader)
            append('\n')
            var currentRun = -1
            for (entry in kept) {
                if (entry.run != currentRun) {
                    if (currentRun != -1) appendLine()
                    appendLine(label(sections[entry.run].name))
                    currentRun = entry.run
                }
                appendLine(entry.line)
            }
            if (omitted > 0) appendLine("--- $omitted lines omitted to fit $budgetBytes bytes ---")
        }
    }

    private fun label(name: String): String = "--- $name ---"

    /** The counter's identity, without the timestamp the writer put in front of every line. */
    private fun statKind(line: String): String =
        TIMESTAMP_PREFIX.replace(line, "").substringBefore('=').take(24)

    private fun byteSize(line: String): Int = line.toByteArray(Charsets.UTF_8).size

    private class Ranked(
        val kind: Int,
        val sequence: Int,
        val run: Int,
        val stat: String?,
        val line: String,
    )

    /** Largest prefix of [bytes] that ends on a character boundary. */
    private fun safeSplit(bytes: ByteArray, limit: Int): Int {
        var end = limit.coerceAtMost(bytes.size)
        while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) end--
        return end
    }
}
