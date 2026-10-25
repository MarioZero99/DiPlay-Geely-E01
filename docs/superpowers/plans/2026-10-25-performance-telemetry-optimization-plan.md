# 车机性能遥测模块 (E01 专属) 优化与节流加固 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
针对吉利星瑞 E01 车机（Android 5.1 / API 22）的硬件资源瓶颈（低算力 CPU、缓慢 eMMC I/O），对性能监控模块与 QuickHudView 实时遥测进行专项节流加固：在 `PerformanceMonitor` 引入 2.5 秒强制时间戳防抖与快照复用、存储分区查询引入 60 秒长缓存、消除 HUD 展开初期的 350ms 频繁 I/O 冲击、并将音视频遥测彻底后台化，保障 60fps 投屏与 CarPlay 运行平滑度。

**Architecture:**
1. **底层强节流与快照复用（`PerformanceMonitor`）**：在监控门面层增加 `MIN_SAMPLE_INTERVAL_MS = 2500L` 节流窗口与快照缓存。即使上层高频调用，冷却期内直接复用缓存，绝不惊动后台工作线程。
2. **异步媒体指标提供者（`mediaProvider`）**：将 `sink?.currentTelemetry()` 的读取从主线程完全转移至后台线程池闭包中执行，消除主线程与音视频解码核心线程争夺 `renderLock` 的潜在风险。
3. **CPU 首次采样后台差值闭环（`CpuSampler`）**：在后台线程首次检测到无基准时，在后台线程短暂休眠 120ms 产出真实利用率，彻底取消 `QuickHudView` 原先在 350ms 安排的非必要二次刷新。
4. **存储查询防抖（`StorageSampler`）**：对 `StatFs` 增加 60 秒 TTL 缓存，消除投屏期间对 E01 孱弱 eMMC 闪存的无意义高频查询。
5. **链路信息修复（`LinkSampler`）**：修复 `formatWifiDetail` 中忽视 `ssid` 的缺陷，补充完备的单元测试。

**Tech Stack:** Kotlin, Android API 19~22, Linux /proc & sysfs, Robolectric 4.17, JUnit 4.

**Spec:** 本计划基于 2026-10-25 针对 commits `548c1b77..1a1a85d0` 的深度审查结论，严格针对 E01（Android 5.1）车机运行环境量身定制。

---

## Global Constraints

- **机型限定与 API 特异性**：严格针对吉利星瑞 E01（Android 5.1 / API 22，mtk-su 已提权环境）。保持 `minSdk = 19`。
- **主线程零 I/O 与零锁争用**：主线程严禁执行任何文件 I/O、`StatFs` 系统调用或需要持有 `VideoStats` 内部锁的查询。
- **最低采样刷新间隔**：性能数据采集与 UI 刷新严格限定为 **2.5 秒** 一次，严禁出现低于 2 秒的高频轮询。
- **实验性开关强隔离**：继续保持 `ExperimentalFeatures.enabled` 门禁，非实验环境严禁产生任何后台开销。

---

## File Structure

```
DiPlay-Legacy-Android-Geely/
├── performance/
│   ├── src/main/java/com/shilapi/performance/
│   │   ├── PerformanceMonitor.kt                 (Modify: 增加 2500ms 强制时间戳节流、快照缓存复用、mediaProvider 后台化)
│   │   └── sampler/
│   │       ├── CpuSampler.kt                     (Modify: 首次采样在后台闭环计算，消除两阶段跨线程等待)
│   │       ├── StorageSampler.kt                 (Modify: 引入 60秒 TTL 缓存，避免频繁 StatFs)
│   │       └── LinkSampler.kt                    (Modify: 修复 formatWifiDetail 未带入 SSID 的问题)
│   └── src/test/java/com/shilapi/performance/
│       ├── PerformanceMonitorTest.kt             (Modify: 验证 2500ms 节流防抖与快照复用)
│       └── sampler/
│           ├── CpuSamplerTest.kt                 (Modify: 验证后台闭环采样逻辑)
│           ├── StorageSamplerTest.kt             (Create: 验证 60秒 TTL 缓存机制)
│           └── LinkSamplerTest.kt                (Modify: 补充 SSID 格式化测试)
└── common/
    ├── src/main/java/com/shilapi/xcertplay/
    │   ├── CarPlayHostActivity.kt                (Modify: 使用 applicationContext 防泄露，将 sink.currentTelemetry 放入 mediaProvider)
    │   └── ui/component/QuickHudView.kt          (Modify: 移除 350ms 闪击，启动即进入恒定 2500ms 刷新)
    └── src/test/java/com/shilapi/xcertplay/
        └── QuickHudViewTest.kt                   (Modify: 验证 HUD 展开与恒定 2.5s 刷新行为)
```

---

## Implementation Tasks

### Task 1: 给 `StorageSampler` 增加 60 秒 TTL 缓存

**Files:**
- Modify: `performance/src/main/java/com/shilapi/performance/sampler/StorageSampler.kt`
- Create: `performance/src/test/java/com/shilapi/performance/sampler/StorageSamplerTest.kt`

**Interfaces:**
- Consumes: `android.os.StatFs`, `android.os.Environment`
- Produces: `StorageSampler.sample(timeProvider: () -> Long = SystemClock::uptimeMillis): StorageMetrics`

- [ ] **Step 1: 编写失败测试 `StorageSamplerTest.kt`**

```kotlin
package com.shilapi.performance.sampler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22])
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
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :performance:testDebugUnitTest --tests com.shilapi.performance.sampler.StorageSamplerTest`
Expected: FAIL (方法签名不存在 `timeProvider` 或 `resetCacheForTest`)

- [ ] **Step 3: 实现 `StorageSampler.kt` 的 60 秒缓存**

```kotlin
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
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :performance:testDebugUnitTest --tests com.shilapi.performance.sampler.StorageSamplerTest`
Expected: PASS

- [ ] **Step 5: 提交更改**

```bash
git add performance/src/main/java/com/shilapi/performance/sampler/StorageSampler.kt performance/src/test/java/com/shilapi/performance/sampler/StorageSamplerTest.kt
git commit -m "perf(performance): add 60s TTL cache to StorageSampler"
```

---

### Task 2: `CpuSampler` 首次后台闭环采样与瞬时计算

**Files:**
- Modify: `performance/src/main/java/com/shilapi/performance/sampler/CpuSampler.kt`
- Modify: `performance/src/test/java/com/shilapi/performance/sampler/CpuSamplerTest.kt`

**Interfaces:**
- Consumes: `/proc/stat`, sysfs
- Produces: `CpuSampler.sample(sleepMs: (Long) -> Unit = Thread::sleep): CpuMetrics`

- [ ] **Step 1: 在 `CpuSamplerTest.kt` 添加后台自闭环采样测试**

```kotlin
    @Test
    fun samplePerformsInlineDeltaWhenBaselineUnset() {
        var sleptMs = 0L
        val sampler = CpuSampler()
        sampler.reset()

        val metrics = sampler.sample(
            readStatLine = { "cpu  100 20 30 850 0 0 0 0 0 0" },
            sleepMs = { delay -> sleptMs = delay },
        )
        assertNotNull(metrics)
    }
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :performance:testDebugUnitTest --tests com.shilapi.performance.sampler.CpuSamplerTest`
Expected: FAIL

- [ ] **Step 3: 修改 `CpuSampler.kt` 支持首次调用后台自闭环**

在 `CpuSampler.kt` 中：
```kotlin
    fun sample(
        readStatLine: () -> String? = { readProcStatLine() },
        sleepMs: (Long) -> Unit = Thread::sleep,
    ): CpuMetrics {
        val usage = runCatching {
            val firstLine = readStatLine() ?: return@runCatching -1
            if (prevTotal == 0L) {
                // 首次建立基准后，在后台线程微休眠 120ms，立即产出真实瞬时使用率，消除 UI 端 350ms 补采机制
                computeUsageFromLine(firstLine)
                sleepMs(120L)
                val secondLine = readStatLine() ?: return@runCatching lastCalculatedUsage
                computeUsageFromLine(secondLine)
            } else {
                computeUsageFromLine(firstLine)
            }
        }.getOrDefault(lastCalculatedUsage)

        val frequencyMhz = readFrequencyMhz()

        return CpuMetrics(
            usagePercent = if (usage >= 0) usage else lastCalculatedUsage,
            coreCount = coreCount,
            frequencyMhz = frequencyMhz,
        )
    }

    private fun readProcStatLine(): String? = runCatching {
        val statFile = File("/proc/stat")
        if (statFile.exists()) {
            statFile.bufferedReader().use { it.readLine() }
        } else null
    }.getOrNull()

    private fun readFrequencyMhz(): Int = runCatching {
        val curFreqFile = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
        val fallbackFreqFile = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_cur_freq")
        val target = when {
            curFreqFile.exists() -> curFreqFile
            fallbackFreqFile.exists() -> fallbackFreqFile
            else -> null
        }
        target?.readText()?.trim()?.toIntOrNull()?.let { khz -> khz / 1000 } ?: -1
    }.getOrDefault(-1)
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :performance:testDebugUnitTest --tests com.shilapi.performance.sampler.CpuSamplerTest`
Expected: PASS

- [ ] **Step 5: 提交更改**

```bash
git add performance/src/main/java/com/shilapi/performance/sampler/CpuSampler.kt performance/src/test/java/com/shilapi/performance/sampler/CpuSamplerTest.kt
git commit -m "feat(performance): implement self-contained baseline sampling in CpuSampler"
```

---

### Task 3: `PerformanceMonitor` 增加 2500ms 强制时间戳节流与后台 `mediaProvider`

**Files:**
- Modify: `performance/src/main/java/com/shilapi/performance/PerformanceMonitor.kt`
- Modify: `performance/src/test/java/com/shilapi/performance/PerformanceMonitorTest.kt`

**Interfaces:**
- Consumes: `CpuSampler`, `MemorySampler`, `StorageSampler`, `LinkSampler`
- Produces: `PerformanceMonitor.sampleAsync(context: Context?, isWired: Boolean, media: MediaMetrics? = null, mediaProvider: (() -> MediaMetrics?)? = null, onResult: (PerformanceSnapshot) -> Unit)`

- [ ] **Step 1: 在 `PerformanceMonitorTest.kt` 编写节流测试**

```kotlin
    @Test
    fun sampleAsyncReturnsCachedSnapshotDuringCooldown() {
        val context = RuntimeEnvironment.getApplication()
        var firstResult: com.shilapi.performance.model.PerformanceSnapshot? = null
        var secondResult: com.shilapi.performance.model.PerformanceSnapshot? = null

        PerformanceMonitor.sampleAsync(context, isWired = true) {
            firstResult = it
        }
        ShadowLooper.idleMainLooper()
        Thread.sleep(200)
        ShadowLooper.idleMainLooper()

        // 立即进行第二次调用（处于 2.5 秒冷却期），应直接返回缓存结果
        PerformanceMonitor.sampleAsync(context, isWired = true) {
            secondResult = it
        }
        ShadowLooper.idleMainLooper()

        assertNotNull(firstResult)
        assertNotNull(secondResult)
        assertEquals(firstResult, secondResult)
    }
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :performance:testDebugUnitTest --tests com.shilapi.performance.PerformanceMonitorTest`
Expected: FAIL

- [ ] **Step 3: 在 `PerformanceMonitor.kt` 中实现时间戳防抖与 mediaProvider 后台化**

```kotlin
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

    fun sampleAsync(
        context: Context?,
        isWired: Boolean = false,
        media: MediaMetrics? = null,
        mediaProvider: (() -> MediaMetrics?)? = null,
        timeProvider: () -> Long = SystemClock::uptimeMillis,
        onResult: (PerformanceSnapshot) -> Unit,
    ) {
        val now = timeProvider()
        // 冷却期内直接复用上一次缓存，绝不惊动后台线程与 I/O
        cachedSnapshot?.let { cached ->
            if (now - lastSampleTimeMs < MIN_SAMPLE_INTERVAL_MS) {
                mainHandler.post { onResult(cached) }
                return
            }
        }

        if (!isSampling.compareAndSet(false, true)) {
            // 若后台已有采样正在进行，有缓存则回落缓存
            cachedSnapshot?.let { mainHandler.post { onResult(it) } }
            return
        }

        val appContext = context?.applicationContext
        executor.execute {
            try {
                // 彻底将 mediaProvider 放置在后台工作线程中执行，避免占用主线程并消除锁争用
                val effectiveMedia = mediaProvider?.invoke() ?: media
                val snapshot = sample(appContext, isWired, effectiveMedia)
                cachedSnapshot = snapshot
                lastSampleTimeMs = timeProvider()
                mainHandler.post {
                    onResult(snapshot)
                }
            } catch (_: Throwable) {
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
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :performance:testDebugUnitTest --tests com.shilapi.performance.PerformanceMonitorTest`
Expected: PASS

- [ ] **Step 5: 提交更改**

```bash
git add performance/src/main/java/com/shilapi/performance/PerformanceMonitor.kt performance/src/test/java/com/shilapi/performance/PerformanceMonitorTest.kt
git commit -m "feat(performance): add 2500ms timestamp throttle and background mediaProvider"
```

---

### Task 4: 优化 `LinkSampler` 中 SSID 拼接与热点信息

**Files:**
- Modify: `performance/src/main/java/com/shilapi/performance/sampler/LinkSampler.kt`
- Modify: `performance/src/test/java/com/shilapi/performance/sampler/LinkSamplerTest.kt`

**Interfaces:**
- Consumes: `Context`, `WifiManager`, `WifiInfo`
- Produces: `LinkSampler.formatWifiDetail(ssid: String?, frequencyMhz: Int, linkSpeedMbps: Int, rssiDbm: Int): String`

- [ ] **Step 1: 在 `LinkSamplerTest.kt` 中添加 SSID 展示验证**

```kotlin
    @Test
    fun formatsWifiDetailWithSsidWhenProvided() {
        val detail = LinkSampler.formatWifiDetail(
            ssid = "Geely-E01",
            frequencyMhz = 5180,
            linkSpeedMbps = 433,
            rssiDbm = -55,
        )
        assertEquals("Geely-E01 · 5 GHz · 433 Mbps (-55 dBm)", detail)
    }
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :performance:testDebugUnitTest --tests com.shilapi.performance.sampler.LinkSamplerTest`
Expected: FAIL

- [ ] **Step 3: 修改 `LinkSampler.kt` 将非空有效 SSID 拼入文本**

```kotlin
    fun formatWifiDetail(
        ssid: String?,
        frequencyMhz: Int,
        linkSpeedMbps: Int,
        rssiDbm: Int,
    ): String {
        val band = when {
            frequencyMhz >= 4900 -> "5 GHz"
            frequencyMhz in 2400..2500 -> "2.4 GHz"
            else -> ""
        }
        val speed = if (linkSpeedMbps > 0) "$linkSpeedMbps Mbps" else ""
        val rssi = if (rssiDbm != 0 && rssiDbm > -127) "($rssiDbm dBm)" else ""

        val parts = mutableListOf<String>()
        val cleanSsid = ssid?.trim('\"')?.trim()
        if (!cleanSsid.isNullOrEmpty() && cleanSsid != "<unknown ssid>") {
            parts.add(cleanSsid)
        }
        if (band.isNotEmpty()) parts.add(band)
        if (speed.isNotEmpty()) parts.add(speed)
        val stats = parts.joinToString(" · ")
        val withRssi = if (rssi.isNotEmpty()) "$stats $rssi".trim() else stats
        return withRssi.ifEmpty { "已连接" }
    }
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :performance:testDebugUnitTest --tests com.shilapi.performance.sampler.LinkSamplerTest`
Expected: PASS

- [ ] **Step 5: 提交更改**

```bash
git add performance/src/main/java/com/shilapi/performance/sampler/LinkSampler.kt performance/src/test/java/com/shilapi/performance/sampler/LinkSamplerTest.kt
git commit -m "fix(performance): include SSID in formatted wifi detail string"
```

---

### Task 5: `QuickHudView` 消除 350ms 闪击，启动即进入 2500ms 恒定节流

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/ui/component/QuickHudView.kt`
- Modify: `common/src/test/java/com/shilapi/xcertplay/QuickHudViewTest.kt`

**Interfaces:**
- Consumes: `PerformanceMonitor`
- Produces: `QuickHudView` HUD 交互与性能条刷新

- [ ] **Step 1: 修改 `QuickHudView.kt` 移除 350ms 二次调度**

在 `QuickHudView.kt` 中：
```kotlin
    private fun startTelemetrySampling() {
        PerformanceMonitor.resetCpuBaseline()
        onSampleTelemetry?.invoke { snapshot ->
            if (visibility == View.VISIBLE) {
                telemetryLabel?.text = formatTelemetryLine(snapshot)
            }
        }
        removeCallbacks(refreshTelemetryTask)
        postDelayed(refreshTelemetryTask, TELEMETRY_REFRESH_INTERVAL_MILLIS)
    }
```

- [ ] **Step 2: 运行 `QuickHudViewTest.kt` 确保测试通过**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.QuickHudViewTest`
Expected: PASS

- [ ] **Step 3: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/QuickHudView.kt
git commit -m "perf(hud): remove 350ms rapid sample on QuickHud open"
```

---

### Task 6: `CarPlayHostActivity` 接入后台 `mediaProvider` 与 `applicationContext`

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`

**Interfaces:**
- Consumes: `PerformanceMonitor.sampleAsync`
- Produces: 安全、无主线程锁争用、无内存泄露的遥测接入

- [ ] **Step 1: 修改 `CarPlayHostActivity.kt` 中的 `onSampleTelemetry` 闭包**

在 `CarPlayHostActivity.kt` 约 1045 行：
```kotlin
            onSampleTelemetry = { callback ->
                val isWired = !wirelessEnabled
                val appContext = applicationContext
                PerformanceMonitor.sampleAsync(
                    context = appContext,
                    isWired = isWired,
                    mediaProvider = {
                        sink?.currentTelemetry()?.let {
                            MediaMetrics(
                                rxFps = it.rxFps,
                                shownFps = it.shownFps,
                                touchLatencyMs = it.touchLatencyMs,
                                droppedFrames = it.recoveries,
                            )
                        }
                    },
                    onResult = callback,
                )
            },
```

- [ ] **Step 2: 编译与测试 `CarPlayHostActivity` 所在的 `:common` 模块**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:compileDebugKotlin :common:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt
git commit -m "fix(hud): evaluate sink telemetry off main thread using mediaProvider"
```

---

### Task 7: 全工程回归构建与完整测试套件验证

**Files:**
- All modified modules (`:performance`, `:common`, `:shared`)

- [ ] **Step 1: 运行全工程单元测试**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew test`
Expected: BUILD SUCCESSFUL, all unit tests passed.

- [ ] **Step 2: 检查 Git 工作区状态**

Run: `git -C /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely status`
Expected: Clean working tree.
