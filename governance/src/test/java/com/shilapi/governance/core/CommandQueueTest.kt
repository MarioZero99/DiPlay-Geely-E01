package com.shilapi.governance.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CommandQueueTest {

    @Test
    fun executesActionsSeriallyWithoutConcurrency() = runBlocking {
        val queue = CommandQueue()
        val runningCount = AtomicInteger(0)
        val maxConcurrency = AtomicInteger(0)
        val completedCount = AtomicInteger(0)

        val jobs = List(10) {
            launch(Dispatchers.Default) {
                queue.execute {
                    val current = runningCount.incrementAndGet()
                    maxConcurrency.updateAndGet { max -> kotlin.math.max(max, current) }
                    delay(20)
                    runningCount.decrementAndGet()
                    completedCount.incrementAndGet()
                }
            }
        }

        jobs.forEach { it.join() }

        assertEquals(10, completedCount.get())
        assertEquals(1, maxConcurrency.get())
    }

    @Test
    fun returnsResultOfAction() = runBlocking {
        val queue = CommandQueue()
        val result = queue.execute {
            "hello"
        }
        assertEquals("hello", result)
    }
}
