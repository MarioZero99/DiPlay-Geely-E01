package com.shilapi.xcertplay.media

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Serialises touch packets on [executor] while keeping at most one unsent MOVE per session.
 *
 * A single-thread executor with an unbounded queue turns a producer faster than the consumer into
 * unbounded backlog: a drag queues one task per MotionEvent and the cursor falls further behind the
 * longer the finger stays down. Coalescing the *unsent* MOVE is the one loss this link can afford —
 * intermediate trajectory points are worth less than the end point arriving on time. Presses,
 * releases and pointer boundaries are never dropped, because dropping one leaves the phone holding
 * a finger that is no longer there.
 *
 * [send] receives the packet and how long it waited in the queue, and runs outside the lock.
 */
internal class TouchDispatchQueue<T>(
    private val executor: Executor,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onCoalesced: () -> Unit = {},
    private val send: (T, Long) -> Unit,
) {
    private class Entry<T>(val value: T, val session: Any, val move: Boolean, val queuedNs: Long)

    private val entries = ArrayDeque<Entry<T>>()
    private val lock = Any()
    private var scheduled = false

    /** Queues [value]; false means it was refused — the executor is gone, or nothing was left to drop. */
    fun offer(value: T, session: Any, move: Boolean): Boolean {
        var coalesced = false
        val accepted = synchronized(lock) {
            val last = entries.peekLast()
            if (move && last != null && last.move && last.session === session) {
                entries.removeLast()
                coalesced = true
            }
            // A producer that never lets go must not grow this without bound. Only a MOVE may be
            // sacrificed to make room: a press or a release is worth more than any position.
            if (entries.size >= MAX_ENTRIES) {
                val victim = entries.firstOrNull { it.move } ?: return@synchronized false
                entries.remove(victim)
            }
            entries.addLast(Entry(value, session, move, nanoTime()))
            if (!scheduled) {
                scheduled = true
                try {
                    executor.execute { drain() }
                } catch (_: RejectedExecutionException) {
                    entries.clear()
                    scheduled = false
                    return@synchronized false
                }
            }
            true
        }
        // Outside the lock: this calls into another object, and holding this lock while doing so
        // invites a lock-order inversion nobody will remember six months from now.
        if (coalesced) onCoalesced()
        return accepted
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                entries.pollFirst() ?: run { scheduled = false; return }
            }
            // One failed packet must not strand everything queued behind it.
            runCatching { send(next.value, (nanoTime() - next.queuedNs).coerceAtLeast(0L)) }
        }
    }

    /**
     * Drops everything queued.
     *
     * Deliberately leaves [scheduled] alone: the drain task may still be sitting on the executor, and
     * clearing the flag here would let a second drain start while the first is still running — two
     * threads sending touches is the exact reordering this class exists to prevent. A drain that
     * finds nothing sets the flag itself and exits.
     */
    fun clear() = synchronized(lock) { entries.clear() }

    private companion object {
        /** Well above any real backlog at 60 Hz; this bounds a runaway producer, nothing else. */
        const val MAX_ENTRIES = 64
    }
}
