package com.shilapi.xcertplay

import java.util.concurrent.atomic.AtomicLong

/**
 * The one place a diagnostic line can go when the caller has no idea whether a session log exists.
 *
 * Transport, media and network threads report on their own schedule: they run before the host page has
 * opened its log, and they outlive it. A line handed to [line] before a writer is attached is held in a
 * bounded buffer and flushed the moment there is one, so the first seconds of a run — the ones that say
 * why it failed — are not the ones thrown away.
 */
object DiagSink {
    /** Lines held before the first writer is attached. */
    const val PENDING_CAPACITY = 256

    private val lock = Any()
    private val pending = ArrayDeque<String>()
    private val overflowed = AtomicLong()

    @Volatile private var writer: ((String) -> Unit)? = null

    fun attach(write: (String) -> Unit) {
        val held: List<String>
        synchronized(lock) {
            writer = write
            held = pending.toList()
            pending.clear()
        }
        // Held lines land after anything a concurrent caller wrote directly. Diagnostics only, and the
        // alternative — flushing before the writer is visible — leaves lines in the buffer forever.
        held.forEach(write)
    }

    fun detach() {
        synchronized(lock) { writer = null }
    }

    fun line(message: String) {
        val target = writer
        if (target != null) {
            target(message)
            return
        }
        synchronized(lock) {
            if (pending.size >= PENDING_CAPACITY) {
                pending.removeFirst()
                overflowed.incrementAndGet()
            }
            pending.addLast(message)
        }
    }

    /** Lines the buffer had to discard because no writer ever appeared. Reported by the digest. */
    fun overflowedLines(): Long = overflowed.get()

    /** Whether a session log is currently listening. Lets the page's wiring be checked. */
    fun isAttached(): Boolean = writer != null

    /** Test seam: forgets the writer and everything held. Public because `:common` tests call it. */
    fun reset() {
        synchronized(lock) {
            writer = null
            pending.clear()
            overflowed.set(0L)
        }
    }
}
