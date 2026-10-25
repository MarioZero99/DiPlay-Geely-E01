package com.shilapi.performance

import com.shilapi.performance.model.LinkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
