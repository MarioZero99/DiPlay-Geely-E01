package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

class NowPlayingArtworkQueueTest {
    @Test
    fun discardsOlderPendingRequestsWhenNewerArrives() {
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
        val session = queue.newSession()
        queue.submit(session, 1, "First".toByteArray())
        queue.submit(session, 2, "Second".toByteArray())
        assertEquals(listOf("First", "Second"), delivered)
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
