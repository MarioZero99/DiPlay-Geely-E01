package com.shilapi.performance

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.performance.model.MediaMetrics
import com.shilapi.performance.model.PerformanceSnapshot
import com.shilapi.performance.sampler.CpuSampler
import com.shilapi.performance.sampler.LinkSampler
import com.shilapi.performance.sampler.MemorySampler
import com.shilapi.performance.sampler.StorageSampler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object PerformanceMonitor {
    private val cpuSampler = CpuSampler()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isSampling = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "perf-sampler-worker").apply { isDaemon = true }
    }

    /**
     * 同步采样，主要用于测试或后台任务。
     */
    fun sample(
        context: Context?,
        isWired: Boolean = false,
        media: MediaMetrics? = null,
    ): PerformanceSnapshot {
        val cpu = cpuSampler.sample()
        val mem = MemorySampler.sample(context)
        val storage = StorageSampler.sample()
        val link = LinkSampler.sample(context, isWired)

        return PerformanceSnapshot(
            cpu = cpu,
            memory = mem,
            storage = storage,
            link = link,
            media = media,
        )
    }

    /**
     * 异步采样（车机安全），彻底移出主线程，杜绝 I/O 阻塞投屏或音频。
     * 若前一次采样仍在排队计算中，则跳过本次触发，防止车机瞬时拥堵。
     */
    fun sampleAsync(
        context: Context?,
        isWired: Boolean = false,
        media: MediaMetrics? = null,
        onResult: (PerformanceSnapshot) -> Unit,
    ) {
        if (!isSampling.compareAndSet(false, true)) {
            return
        }

        executor.execute {
            try {
                val snapshot = sample(context, isWired, media)
                mainHandler.post {
                    onResult(snapshot)
                }
            } catch (_: Throwable) {
                // 绝不抛出任何未捕获异常
            } finally {
                isSampling.set(false)
            }
        }
    }

    fun resetCpuBaseline() {
        cpuSampler.reset()
    }
}
