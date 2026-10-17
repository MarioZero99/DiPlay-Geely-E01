package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.orchestration.AttemptConfig
import com.shilapi.xcertplay.orchestration.AttemptEnvironment
import com.shilapi.xcertplay.orchestration.AttemptRecord
import com.shilapi.xcertplay.orchestration.AttemptTracker
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.encodeAttempt
import com.shilapi.xcertplay.orchestration.newHexId
import com.shilapi.xcertplay.transport.WirelessGap
import java.io.File
import java.io.IOException

/**
 * `events.jsonl`: one line per attempt, written when the attempt reaches its outcome.
 *
 * Separate from the session log on purpose. A session log is reset by every connect, carries the
 * periodic lines, and is squeezed into a byte budget when the report is saved — all three of which
 * lose exactly the attempt that is being asked about. This file is never reset, holds nothing but
 * outcomes, and is small enough that five hundred attempts are a fraction of the space the session
 * log spends on one run.
 *
 * Written synchronously. A record is a few hundred bytes and the process may be killed the moment a
 * failure is reported, which is the one moment the line matters; the session log's executor has
 * already stopped accepting work by then.
 */
internal object AttemptJournal {
    const val EVENTS = "events.jsonl"

    private const val ROTATED = "events.1.jsonl"

    /** Two hundred kilobytes is roughly five hundred attempts; the report reads the newest of them. */
    private const val MAX_BYTES = 256 * 1024

    private const val PREFS = "diplay_attempts"
    private const val INSTALL = "install"

    private fun directory(context: Context): File = File(context.filesDir, "logs")

    /**
     * The installation this record came from, drawn once and resettable by clearing the preference.
     *
     * Not a device identity: no MAC, no Android ID, nothing the phone or the head unit would recognise.
     * It exists so two lines from the same car can be seen to be from the same car, which is the whole
     * of what a report from a fleet of a few dozen needs.
     */
    @Synchronized
    fun installId(context: Context): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.getString(INSTALL, null)?.let { return it }
        val drawn = newHexId()
        preferences.edit().putString(INSTALL, drawn).apply()
        return drawn
    }

    fun environment(context: Context): AttemptEnvironment {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val versionCode = info?.versionCode ?: 0
        return AttemptEnvironment(
            install = installId(context),
            version = info?.versionName ?: "unknown",
            versionCode = versionCode,
            model = Build.MODEL ?: "unknown",
            api = Build.VERSION.SDK_INT,
        )
    }

    /** The settings a new attempt starts with, read where the attempt itself reads them. */
    fun config(context: Context): AttemptConfig = AttemptConfig(
        fps = AirPlayPersistence.loadFps(context),
        hevc = AirPlayPersistence.loadHevcEnabled(context),
        scaleTenths = AirPlayPersistence.loadDisplayScaleTenths(context),
    )

    /**
     * A tracker for an attempt that is about to start.
     *
     * Built here rather than in the activity so that the clocks, the installation id and the setting
     * reads all come from one place: two attempts, however they were started, then describe themselves
     * the same way.
     */
    fun trackerFor(context: Context, generation: Int, retry: Int): AttemptTracker {
        val wireless = AirPlayPersistence.loadWirelessEnabled(context)
        return AttemptTracker(
            id = newHexId(),
            environment = environment(context),
            transport = if (wireless) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
            mode = if (wireless) AirPlayPersistence.loadWirelessHotspotMode(context) else null,
            hop = if (wireless) DiPlayPreferences.bluetoothHop(context) else null,
            config = config(context),
            generation = generation,
            retry = retry,
            uptime = { SystemClock.elapsedRealtime() },
            wallClock = { System.currentTimeMillis() },
        )
    }

    /** A connect the page refused before it started. The gap is the reason, and the only fact there is. */
    fun writeRefusal(context: Context, gap: WirelessGap) {
        trackerFor(context, generation = 0, retry = 0).apply { block(gap) }.record?.let { write(context, it) }
    }

    /**
     * Appends one record, rotating first when the file has grown past its cap.
     *
     * Rotation keeps exactly one older file. A failure that matters is in the newest file or the one
     * before it; older ones are not worth the room, and the report says how many lines a save left out
     * rather than pretending nothing was dropped.
     */
    @Synchronized
    fun write(context: Context, record: AttemptRecord) {
        val line = encodeAttempt(record)
        val directory = directory(context)
        val file = File(directory, EVENTS)
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("no log directory")
            if (file.length() + line.length + 1 > MAX_BYTES) {
                val rotated = File(directory, ROTATED)
                rotated.delete()
                file.renameTo(rotated)
            }
            file.appendText(line + "\n")
        } catch (error: IOException) {
            // The record is gone, and the report says so: a report that cannot tell "nothing happened"
            // from "the evidence was dropped" reads as a quiet run.
            DiagnosticCounters.noteLogWriteFailure()
        }
    }

    /** Every record on disk, oldest first — the rotated file, then the current one. */
    fun lines(context: Context): List<String> {
        val directory = directory(context)
        return listOf(ROTATED, EVENTS)
            .map { File(directory, it) }
            .filter { it.isFile }
            .flatMap { file -> runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList()) }
            .filter { it.isNotBlank() }
    }
}
