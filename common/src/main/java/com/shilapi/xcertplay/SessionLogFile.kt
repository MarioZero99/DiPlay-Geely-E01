package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(
    val file: File,
    /** When "now" is, so a test can place runs on days of its own instead of the machine's. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Closeable {
    private val state = stateFor(file)
    private var closed = false
    fun reset(header: String) = synchronized(state) {
        if (!closed) {
            file.parentFile?.mkdirs()
            archive()
            purge()
            file.writeText("")
            state.length = 0L
            append(header)
        }
    }
    fun append(line: String) = synchronized(state) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        runCatching {
            val payload = (safe + "\n").toByteArray(Charsets.UTF_8)
            if (lengthBytes() > MAX_BYTES) {
                archive()
                file.writeText("")
                state.length = 0L
            }
            file.appendBytes(payload)
            state.length += payload.size
        }.onFailure {
            // A full or read-only volume used to look exactly like a quiet run.
            DiagnosticCounters.noteLogWriteFailure()
        }
        Unit
    }
    private fun lengthBytes(): Long {
        if (state.length < 0L) {
            state.length = if (file.isFile) file.length() else 0L
        }
        return state.length
    }

    /**
     * Moves the run that just ended into the archive of the day it ran on.
     *
     * The archive is named by the date the run started (its header line) rather than by its place in
     * a queue of eight files: a report is asked for about a day, so the day has to be something a
     * file name can say. One run that outgrew [MAX_BYTES] is written as further parts of the same
     * day, so a busy day keeps every line and every file stays capped.
     */
    private fun archive() {
        if (!file.exists() || file.length() == 0L) return
        val day = dayOf(file)
        val parts = archivesOf(file.parentFile, day).size
        val suffix = if (parts == 0) "" else "-${parts + 1}"
        runCatching { file.copyTo(File(file.parentFile, "$ARCHIVE_PREFIX$day$suffix$LOG_SUFFIX"), overwrite = true) }
            .onFailure { DiagnosticCounters.noteLogWriteFailure() }
    }

    /**
     * Drops whole days past [KEEP_DAYS], so months of driving stay a bounded amount of disk.
     *
     * Runs at every start. A run that ended before retention began is archived first and deleted
     * here by the same rule as any other old day.
     */
    private fun purge() {
        val oldest = dayString(clock() - (KEEP_DAYS - 1) * DAY_MILLIS)
        val now = clock()
        file.parentFile?.listFiles()?.forEach { candidate ->
            if (!candidate.isFile) return@forEach
            val name = candidate.name
            val day = archiveDay(name)
            if (day != null) {
                if (day < oldest) candidate.delete()
            } else if (LEGACY_ARCHIVE.matches(name) && now - candidate.lastModified() > KEEP_DAYS * DAY_MILLIS) {
                // The count-based archives of older builds are never read again; let them age out
                // on the same seven-day rule instead of sitting there for good.
                candidate.delete()
            }
        }
    }

    /** Today's archive parts, earliest first; the run in progress is not one of them. */
    private fun archivesOf(directory: File?, day: String): List<File> {
        if (directory == null) return emptyList()
        return directory.listFiles()
            ?.filter { it.isFile && archiveDay(it.name) == day }
            ?.sortedBy { archivePart(it.name) }
            .orEmpty()
    }

    /** The day an archive file is named for, or null when the name is not one of ours. */
    private fun archiveDay(name: String): String? = ARCHIVE_NAME.find(name)?.groupValues?.get(1)

    /** Part 1 has no suffix; later parts say so, and sort after it. */
    private fun archivePart(name: String): Int =
        ARCHIVE_NAME.find(name)?.groupValues?.get(3)?.toIntOrNull() ?: 1

    /** The day a run started on: its header line, or the file's own time when there is no header. */
    private fun dayOf(log: File): String {
        val first = runCatching { log.bufferedReader().use { it.readLine() } }.getOrNull()
        first?.let { line -> DAY_PATTERN.find(line)?.value?.let { return it } }
        return dayString(log.lastModified())
    }

    override fun close() = synchronized(state) { closed = true }

    /**
     * One log file can outlive one instance: an activity is recreated while the previous
     * session's writer is still draining. Both hold a [SessionLogFile] for the same path, so
     * they share the monitor and the byte count — otherwise the newcomer's truncate leaves the
     * old writer's cached size over [MAX_BYTES], and its next append rotates and wipes the
     * fresh file.
     */
    private class State {
        /** Byte length of the file. Negative until the first read. */
        var length = -1L
    }

    companion object {
        const val MAX_BYTES = 512 * 1024L

        /** Whole days kept on disk; today counts as one of them. */
        const val KEEP_DAYS = 7

        private const val CURRENT_NAME = "diplay.log"
        private const val ARCHIVE_PREFIX = "diplay-"
        private const val LOG_SUFFIX = ".log"
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        /** A date anywhere in a line: the header's `started` stamp, or an ordinary line's prefix. */
        private val DAY_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

        /** `diplay-2026-11-10.log` and its later parts `diplay-2026-11-10-2.log`. */
        private val ARCHIVE_NAME = Regex("""^diplay-(\d{4}-\d{2}-\d{2})(-(\d+))?\.log$""")

        /** `previous.log` and `previous-N.log`, written by the builds that kept eight runs. */
        private val LEGACY_ARCHIVE = Regex("""previous(-\d+)?\.log""")

        /**
         * The files a report may read: today's earlier runs, earliest first, then the run in progress.
         *
         * Nothing older, on purpose. The report is what a driver sends about today; the retained
         * days are on disk for the seven-day window and are not silently mixed in.
         */
        fun reportFiles(directory: File, nowMillis: Long = System.currentTimeMillis()): List<File> {
            val today = dayString(nowMillis)
            val archived = directory.listFiles()
                ?.filter { it.isFile && ARCHIVE_NAME.find(it.name)?.groupValues?.get(1) == today }
                ?.sortedBy { file ->
                    ARCHIVE_NAME.find(file.name)?.groupValues?.get(3)?.toIntOrNull() ?: 1
                }
                .orEmpty()
            val current = File(directory, CURRENT_NAME).takeIf { it.isFile }
            return archived + listOfNotNull(current)
        }

        private fun dayString(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

        /** Keyed by absolute path. Bounded by the number of distinct log paths, which is one. */
        private val states = ConcurrentHashMap<String, State>()
        private fun stateFor(file: File): State =
            states.computeIfAbsent(file.absolutePath) { State() }
    }
}
