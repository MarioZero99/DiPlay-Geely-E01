package com.shilapi.xcertplay.media

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchDispatchQueueTest {

    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun backlogKeepsLatestMoveAndEveryPressRelease() {
        val worker = Worker()
        val sent = mutableListOf<String>()
        var coalesced = 0
        val queue = TouchDispatchQueue<String>(worker, onCoalesced = { coalesced++ }) { value, _ -> sent += value }
        val session = Any()
        listOf("down", "move1", "move2", "pointerDown", "move3", "move4", "pointerUp", "up", "cancel")
            .forEach { assertTrue(queue.offer(it, session, it.startsWith("move"))) }
        assertEquals(1, worker.tasks.size)
        worker.run()
        assertEquals(listOf("down", "move2", "pointerDown", "move4", "pointerUp", "up", "cancel"), sent)
        assertEquals(2, coalesced)
    }

    @Test fun distinctSessionsNeverCoalesceOneAnother() {
        val worker = Worker()
        val sent = mutableListOf<Int>()
        val queue = TouchDispatchQueue<Int>(worker) { value, _ -> sent += value }
        val a = String(charArrayOf('s'))
        val b = String(charArrayOf('s'))
        assertFalse(a === b)
        queue.offer(1, a, true)
        queue.offer(2, b, true)
        worker.run()
        assertEquals(listOf(1, 2), sent)
    }

    @Test fun waitIsMeasuredFromTheEntryThatActuallyGoesOut() {
        val worker = Worker()
        var time = 100L
        val sent = mutableListOf<Pair<Int, Long>>()
        val queue = TouchDispatchQueue<Int>(worker, nanoTime = { time }) { value, wait -> sent += value to wait }
        val session = Any()
        queue.offer(1, session, true)
        time = 150
        queue.offer(2, session, true)
        time = 200
        worker.run()
        assertEquals(listOf(2 to 50L), sent)
    }

    @Test fun workArrivingDuringSendIsDrainedByTheSameTask() {
        val worker = Worker()
        val sent = mutableListOf<Int>()
        val session = Any()
        lateinit var queue: TouchDispatchQueue<Int>
        queue = TouchDispatchQueue(worker) { value, _ -> sent += value; if (value == 2) queue.offer(3, session, false) }
        queue.offer(2, session, false)
        worker.run()
        assertEquals(listOf(2, 3), sent)
        assertEquals(0, worker.tasks.size)
    }

    @Test fun sendFailureDoesNotStrandFollowingTouches() {
        val worker = Worker()
        val sent = mutableListOf<Int>()
        val queue = TouchDispatchQueue<Int>(worker) { value, _ -> if (value == 1) error("transport closed") else sent += value }
        val session = Any()
        queue.offer(1, session, false)
        queue.offer(2, session, false)
        worker.run()
        queue.offer(3, session, false)
        worker.run()
        assertEquals(listOf(2, 3), sent)
    }

    @Test fun rejectedExecutorReturnsFalseAndKeepsNothing() {
        var reject = true
        val sent = mutableListOf<Int>()
        val queue = TouchDispatchQueue<Int>(Executor { if (reject) throw RejectedExecutionException() else it.run() }) { v, _ -> sent += v }
        assertFalse(queue.offer(1, Any(), false))
        reject = false
        assertTrue(queue.offer(2, Any(), false))
        assertEquals(listOf(2), sent)
    }

    @Test fun clearDropsPendingWork() {
        val worker = Worker()
        val sent = mutableListOf<Int>()
        val queue = TouchDispatchQueue<Int>(worker) { v, _ -> sent += v }
        queue.offer(1, Any(), false)
        queue.clear()
        worker.run()
        assertTrue(sent.isEmpty())
    }

    @Test fun capSacrificesAnOldMoveButNeverAPress() {
        val worker = Worker()
        val sent = mutableListOf<Int>()
        val queue = TouchDispatchQueue<Int>(worker) { v, _ -> sent += v }
        val session = Any()
        assertTrue(queue.offer(1, session, true))                 // 最老的一条，是 MOVE
        repeat(63) { assertTrue(queue.offer(it + 10, session, false)) }
        assertTrue(queue.offer(999, session, false))              // 满了：牺牲 1
        assertFalse(queue.offer(1000, session, false))            // 满了且只剩按下抬起：拒收
        worker.run()
        assertFalse(sent.contains(1))
        assertTrue(sent.contains(999))
    }
}
