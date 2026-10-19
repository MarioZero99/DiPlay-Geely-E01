package com.shilapi.xcertplay

import org.robolectric.RuntimeEnvironment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiPlayBootstrapTest {
    @Test
    fun prewarmDoesNotDeadlockWithConcurrentEnsure() {
        val context = RuntimeEnvironment.getApplication()
        val latch = CountDownLatch(1)
        DiPlayBootstrap.prewarm(context)
        Thread {
            runCatching { DiPlayBootstrap.ensure(context) }
            latch.countDown()
        }.start()

        val finishedInTime = latch.await(2, TimeUnit.SECONDS)
        assert(finishedInTime) { "DiPlayBootstrap.ensure deadlocked with prewarm!" }
    }
}
