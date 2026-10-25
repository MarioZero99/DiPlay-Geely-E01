package com.shilapi.performance

import com.shilapi.performance.model.LinkType
import com.shilapi.performance.model.MediaMetrics
import com.shilapi.performance.model.PerformanceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PerformanceMonitorTest {
    @Test
    fun sampleReturnsNonNullSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val snapshot = PerformanceMonitor.sample(context, isWired = false, media = null)
        assertNotNull(snapshot)
        assertNotNull(snapshot.cpu)
        assertNotNull(snapshot.memory)
        assertNotNull(snapshot.storage)
        assertNotNull(snapshot.link)
    }

    @Test
    fun sampleAsyncDispatchesResultToCallback() {
        val context = RuntimeEnvironment.getApplication()
        PerformanceMonitor.reset()
        var receivedLinkType: LinkType? = null

        PerformanceMonitor.sampleAsync(context, isWired = true, media = null) { snapshot ->
            receivedLinkType = snapshot.link.type
        }

        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && receivedLinkType == null) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }

        assertEquals(LinkType.USB_WIRED, receivedLinkType)
    }

    @Test
    fun sampleAsyncReturnsCachedSnapshotDuringCooldown() {
        val context = RuntimeEnvironment.getApplication()
        PerformanceMonitor.reset()
        var firstResult: PerformanceSnapshot? = null
        var secondResult: PerformanceSnapshot? = null

        var currentTime = 10_000L
        PerformanceMonitor.sampleAsync(
            context,
            isWired = true,
            timeProvider = { currentTime },
        ) {
            firstResult = it
        }

        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && firstResult == null) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertNotNull(firstResult)

        // 步进 1000ms（小于 2500ms 冷却期），再次调用
        currentTime += 1000L
        PerformanceMonitor.sampleAsync(
            context,
            isWired = true,
            timeProvider = { currentTime },
        ) {
            secondResult = it
        }
        ShadowLooper.idleMainLooper()

        assertNotNull(secondResult)
        assertEquals(firstResult, secondResult)
    }

    @Test
    fun sampleAsyncInvokesMediaProviderInBackground() {
        val context = RuntimeEnvironment.getApplication()
        PerformanceMonitor.reset()
        var providerCalled = false
        var receivedSnapshot: PerformanceSnapshot? = null

        PerformanceMonitor.sampleAsync(
            context = context,
            isWired = false,
            mediaProvider = {
                providerCalled = true
                MediaMetrics(
                    rxFps = 60f,
                    shownFps = 60f,
                    touchLatencyMs = 25L,
                    droppedFrames = 0,
                )
            },
        ) {
            receivedSnapshot = it
        }

        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && receivedSnapshot == null) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }

        assertNotNull(receivedSnapshot)
        assertTrue(providerCalled)
        assertEquals(60f, receivedSnapshot?.media?.shownFps ?: 0f, 0.1f)
    }
}
