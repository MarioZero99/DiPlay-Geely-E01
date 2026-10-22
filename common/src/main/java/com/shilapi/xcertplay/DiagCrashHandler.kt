package com.shilapi.xcertplay

import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes an uncaught exception into the session log before the process dies.
 *
 * Nothing did this. A crash took every line the stack had not yet flushed and left no record of the
 * exception itself, so the report a driver sent described the run up to the crash and never the crash —
 * the one fact that needs no interpreting. This appends synchronously: [SessionLogFile] keys its state
 * by absolute path, so a new instance writes into the same file the page is writing, and a crash must
 * not go through the page's queue, which a dying process never drains.
 */
object DiagCrashHandler {
    @Volatile private var installed = false
    @Volatile private var logFile: File? = null

    fun install(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            val directory = File(app.filesDir, "logs")
            // SessionLogFile only creates this directory in reset(), and reset() runs only once a session
            // starts — which needs a successful bootstrap. The crash that happens before the first session
            // is the one most worth having, and without this it would be written nowhere at all.
            directory.mkdirs()
            logFile = File(directory, "diplay.log")
            if (installed) return
            installed = true
            Thread.setDefaultUncaughtExceptionHandler(
                handler(Thread.getDefaultUncaughtExceptionHandler()) { thread, error -> record(thread, error) },
            )
        }
    }

    /**
     * The handler this installs, with its two dependencies passed in so the delegation can be tested
     * without touching the process's real handler. The original stack is what matters: a failure while
     * recording must not replace it, and the previous handler must still run.
     */
    fun handler(
        previous: Thread.UncaughtExceptionHandler?,
        onRecord: (Thread, Throwable) -> Unit,
    ): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
        runCatching { onRecord(thread, error) }
        runCatching { com.shilapi.governance.VehicleGovernance.onAppExit() }
        previous?.uncaughtException(thread, error)
    }

    fun format(thread: Thread, error: Throwable, pid: Int, stamp: String): List<String> {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val header = "$stamp  FATAL uncaught ${error.javaClass.name} on ${thread.name} pid=$pid"
        return listOf(header) + trace.lineSequence().toList()
    }

    fun record(thread: Thread, error: Throwable) {
        val target = logFile ?: return
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val lines = format(thread, error, Process.myPid(), stamp)
        SessionLogFile(target).use { log ->
            log.append(lines.first())
            lines.drop(1).forEach(log::append)
        }
    }

    /** Test seam. */
    internal fun recordForTest(thread: Thread, error: Throwable) = record(thread, error)

    /** Test seam. */
    internal fun setLogFileForTest(file: File?) {
        logFile = file
    }
}
