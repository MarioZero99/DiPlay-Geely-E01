package com.shilapi.performance

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.performance.model.MediaMetrics
import com.shilapi.performance.model.PerformanceSnapshot
import com.shilapi.performance.sampler.CpuSampler
import com.shilapi.performance.sampler.LinkSampler
import com.shilapi.performance.sampler.MemorySampler
import com.shilapi.performance.sampler.StorageSampler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object PerformanceMonitor {
    const val MIN_SAMPLE_INTERVAL_MS = 2500L

    private val cpuSampler = CpuSampler()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isSampling = AtomicBoolean(false)

    @Volatile
    private var lastSampleTimeMs = 0L

    @Volatile
    private var cachedSnapshot: PerformanceSnapshot? = null

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
        val appContext = context?.applicationContext
        val cpu = cpuSampler.sample()
        val mem = MemorySampler.sample(appContext)
        val storage = StorageSampler.sample()
        val link = LinkSampler.sample(appContext, isWired)

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
     * 若在 2500ms 冷却期内调用，直接向主线程分发最近缓存的快照，绝不重复唤醒后台线程。
     */
    fun sampleAsync(
        context: Context?,
        isWired: Boolean = false,
        media: MediaMetrics? = null,
        mediaProvider: (() -> MediaMetrics?)? = null,
        timeProvider: () -> Long = SystemClock::uptimeMillis,
        onResult: (PerformanceSnapshot) -> Unit,
    ) {
        val now = timeProvider()
        cachedSnapshot?.let { cached ->
            if (now - lastSampleTimeMs < MIN_SAMPLE_INTERVAL_MS) {
                mainHandler.post { onResult(cached) }
                return
            }
        }

        if (!isSampling.compareAndSet(false, true)) {
            cachedSnapshot?.let { mainHandler.post { onResult(it) } }
            return
        }

        val appContext = context?.applicationContext
        executor.execute {
            try {
                val effectiveMedia = mediaProvider?.invoke() ?: media
                val snapshot = sample(appContext, isWired, effectiveMedia)
                cachedSnapshot = snapshot
                lastSampleTimeMs = timeProvider()
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

    fun reset() {
        cpuSampler.reset()
        lastSampleTimeMs = 0L
        cachedSnapshot = null
    }

    fun resetCpuBaseline() {
        reset()
    }
}
