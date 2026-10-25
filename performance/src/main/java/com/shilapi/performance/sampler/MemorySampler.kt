package com.shilapi.performance.sampler

import android.app.ActivityManager
import android.content.Context
import com.shilapi.performance.model.MemoryMetrics

object MemorySampler {
    fun sample(context: Context?): MemoryMetrics {
        if (context == null) return MemoryMetrics(0L, 0L, false)
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val total = memInfo.totalMem
                val avail = memInfo.availMem
                val used = (total - avail).coerceAtLeast(0L)
                MemoryMetrics(
                    usedBytes = used,
                    totalBytes = total,
                    isLowMemory = memInfo.lowMemory,
                )
            } else {
                MemoryMetrics(0L, 0L, false)
            }
        }.getOrDefault(MemoryMetrics(0L, 0L, false))
    }
}
