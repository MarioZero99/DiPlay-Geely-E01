package com.shilapi.performance.sampler

import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.shilapi.performance.model.StorageMetrics

object StorageSampler {
    const val STORAGE_CACHE_TTL_MS = 60_000L

    @Volatile
    private var lastSampleTime = 0L

    @Volatile
    private var cachedStorage = StorageMetrics(0L, 0L)

    fun sample(timeProvider: () -> Long = SystemClock::uptimeMillis): StorageMetrics {
        val now = timeProvider()
        if (cachedStorage.totalBytes > 0L && now - lastSampleTime < STORAGE_CACHE_TTL_MS) {
            return cachedStorage
        }

        return runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val total = totalBlocks * blockSize
            val available = availableBlocks * blockSize
            val used = (total - available).coerceAtLeast(0L)
            lastSampleTime = now
            StorageMetrics(usedBytes = used, totalBytes = total).also {
                cachedStorage = it
            }
        }.getOrDefault(cachedStorage)
    }

    fun resetCacheForTest() {
        lastSampleTime = 0L
        cachedStorage = StorageMetrics(0L, 0L)
    }
}
