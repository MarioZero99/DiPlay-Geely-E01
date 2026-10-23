package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class NowPlayingArtworkQueueTest {

    @Test
    fun sameIdReplacementDiscardsEarlierVersionBeforeWorkerRuns() {
        val workerTasks = mutableListOf<Runnable>()
        val manualWorker = Executor { workerTasks.add(it) }
        val mainTasks = mutableListOf<Runnable>()
        val manualMain = Executor { mainTasks.add(it) }
        val delivered = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val queue = NowPlayingArtworkQueue<String>(
            worker = manualWorker,
            main = manualMain,
            decode = { bytes: ByteArray -> String(bytes) },
            publish = { _: Any, _: Int, img: String? -> if (img != null) delivered.add(img) },
            discard = { img: String -> discarded.add(img) },
        )
        val session = queue.newSession()
        queue.submit(session, 1, "V1".toByteArray())
        queue.submit(session, 1, "V2".toByteArray())

        while (workerTasks.isNotEmpty()) workerTasks.removeAt(0).run()
        while (mainTasks.isNotEmpty()) mainTasks.removeAt(0).run()

        assertEquals(listOf("V2"), delivered)
    }

    @Test
    fun maxPendingEvictionDropsOldestPendingRequest() {
        val workerTasks = mutableListOf<Runnable>()
        val manualWorker = Executor { workerTasks.add(it) }
        val mainTasks = mutableListOf<Runnable>()
        val manualMain = Executor { mainTasks.add(it) }
        val delivered = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val queue = NowPlayingArtworkQueue<String>(
            worker = manualWorker,
            main = manualMain,
            decode = { bytes: ByteArray -> String(bytes) },
            publish = { _: Any, _: Int, img: String? -> if (img != null) delivered.add(img) },
            discard = { img: String -> discarded.add(img) },
        )
        val session = queue.newSession()
        for (i in 1..5) {
            queue.submit(session, i, "Item$i".toByteArray())
        }

        while (workerTasks.isNotEmpty()) workerTasks.removeAt(0).run()
        while (mainTasks.isNotEmpty()) mainTasks.removeAt(0).run()

        // MAX_PENDING is 4, so Item1 was evicted before decoding started
        assertEquals(listOf("Item2", "Item3", "Item4", "Item5"), delivered)
    }

    @Test
    fun inFlightDecodedImageDiscardedWhenSessionClearedBeforeDelivery() {
        val sameThread = Executor { it.run() }
        val mainTasks = mutableListOf<Runnable>()
        val manualMain = Executor { mainTasks.add(it) }
        val delivered = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val queue = NowPlayingArtworkQueue<String>(
            worker = sameThread,
            main = manualMain,
            decode = { bytes: ByteArray -> String(bytes) },
            publish = { _: Any, _: Int, img: String? -> if (img != null) delivered.add(img) },
            discard = { img: String -> discarded.add(img) },
        )
        val session = queue.newSession()
        queue.submit(session, 1, "DiscardMe".toByteArray())

        // Worker finished and decoded image into ready, but delivery hasn't run yet
        queue.clear()

        // Discard must have been invoked for the ready decoded image
        assertEquals(listOf("DiscardMe"), discarded)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun clearsStateOnNewSession() {
        val sameThread = Executor { it.run() }
        val delivered = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val queue = NowPlayingArtworkQueue<String>(
            worker = sameThread,
            main = sameThread,
            decode = { bytes: ByteArray -> String(bytes) },
            publish = { _: Any, _: Int, img: String? -> if (img != null) delivered.add(img) },
            discard = { img: String -> discarded.add(img) },
        )
        val session1 = queue.newSession()
        val session2 = queue.newSession()
        // Submitting with session1 should be ignored
        queue.submit(session1, 1, "Stale".toByteArray())
        assertEquals(emptyList<String>(), delivered)

        queue.submit(session2, 2, "Fresh".toByteArray())
        assertEquals(listOf("Fresh"), delivered)
    }
}
