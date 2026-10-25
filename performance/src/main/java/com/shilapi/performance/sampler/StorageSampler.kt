package com.shilapi.performance.sampler

import android.os.Environment
import android.os.StatFs
import com.shilapi.performance.model.StorageMetrics

object StorageSampler {
    @Volatile
    private var cachedTotalBytes = 0L

    fun sample(): StorageMetrics {
        return runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val total = if (cachedTotalBytes > 0L) cachedTotalBytes else {
                val calculated = totalBlocks * blockSize
                cachedTotalBytes = calculated
                calculated
            }
            val available = availableBlocks * blockSize
            val used = (total - available).coerceAtLeast(0L)
            StorageMetrics(usedBytes = used, totalBytes = total)
        }.getOrDefault(StorageMetrics(0L, 0L))
    }
}
