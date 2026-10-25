package com.shilapi.performance.sampler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StorageSamplerTest {

    @Test
    fun cachesStorageMetricsWithinTtl() {
        var currentTime = 1000L
        StorageSampler.resetCacheForTest()

        val first = StorageSampler.sample(timeProvider = { currentTime })
        assertNotNull(first)

        // 时间步进 10 秒（未达 60 秒 TTL），应返回缓存对象
        currentTime += 10_000L
        val second = StorageSampler.sample(timeProvider = { currentTime })
        assertEquals(first.totalBytes, second.totalBytes)
        assertEquals(first.usedBytes, second.usedBytes)
    }
}
