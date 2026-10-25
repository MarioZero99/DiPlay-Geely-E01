# 车机性能监控与三指下拉 (Quick HUD) 实时遥测 Implementation Plan (加固审查版)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 
创建独立的 `:performance` 模块，针对 Android 5.1 (API 22) 及车载低算力环境进行严格优化，异步安全采集车机资源（瞬时 CPU 负载与频率、系统运行内存与低内存预警、机身存储空间、网络/热点链路模式），并在 CarPlay 投屏的三指下拉快捷栏 (`QuickHudView`) 中自适应呈现，全过程零侵入主投屏性能、离开即停采。

**Architecture:** 
1. **独立模块 `:performance`**：采用自包含 Android Library 架构，单向依赖 AndroidX 核心库与 minSdk 19。包含 `CpuSampler`、`MemorySampler`、`StorageSampler`、`LinkSampler` 四大解耦采样器，通过门面 `PerformanceMonitor` 对外提供异步采样接口 `sampleAsync()`，彻底杜绝 eMMC/sysfs I/O 阻塞主线程。
2. **两阶段 CPU 差分算法**：摒弃直接单次读取 `/proc/stat` 导致计算出开机平均值的缺陷，首次呼出时建立基准采样，300ms 后产出第一帧真实瞬时占用，并在 HUD 展开期间平滑滚动更新。
3. **链路智能感知**：结合既有 `CarHotspotStatus` 与系统网络状态，智能识别 USB 有线投屏、车机热点 AP 模式与外部 Wi-Fi 客户端模式，避免老车机上直接读取 `WifiInfo` 产生空数据的尴尬。
4. **投屏流媒体遥测接口**：在 `shared` 模块的 `VideoStats` 中提供只读不重置的滑动快照（当前帧率、丢帧数、触控延迟），彻底杜绝与每 5 秒诊断日志的计数器冲突。
5. **三指下拉 (`QuickHudView`) 呈现**：在 `QuickHudView` 中嵌入精致紧凑的实时遥测条（Telemetry Strip）。当且仅当 `ExperimentalFeatures.enabled` 为 true 时启用，显式设置事件消费避免误触背景关闭，HUD 关闭（`dismissNow()`）时立即注销所有后台任务。

**Tech Stack:** Kotlin 2.2 / 1.9, Android API 19 (minSdk 19) ~ 37 (目标 Android 5.1 / API 22), Robolectric 4.17, JUnit 4, Linux /proc & /sys sysfs.

---

## Global Constraints

- **最低 API 支持与 5.1 特异性防护**：`minSdk = 19`。所有 API 21+ 系统调用（如 `WifiInfo.frequency`）必须带有 `Build.VERSION.SDK_INT >= 21` 保护与安全回退，严禁触发 `NoSuchMethodError`。
- **主线程零 I/O 阻塞**：车机底层 eMMC 存储读取缓慢，所有文件/proc/sys I/O（含 `StatFs` 与 `/proc/stat` 读取）必须派遣在后台线程执行，主线程仅在回调中刷 UI，严禁任何可能导致 CarPlay 画面/音频掉帧的同步 I/O。
- **只读容错保证**：所有底层读取置于 `runCatching` 中，遇权限限制或文件缺失统一回退为安全默认值（如 `N/A`、`-1`），严禁抛出异常。
- **业务计数器零侵入**：严禁在遥测 FPS 时调用 `VideoStats` 的 `getAndSet(0)`，只能读取原子值或维护独立滑动窗口，确保 5 秒官方诊断日志不受丝毫污染。
- **实验性开关强隔离**：非实验性版本（`ExperimentalFeatures.enabled == false`）必须完全不渲染性能条，与官方稳定版外观及行为完全一致。

---

## File Structure

```
xcertplay/
├── settings.gradle.kts                               (Modify: include(":performance"))
├── performance/                                      (Create: 独立 Android Library)
│   ├── build.gradle.kts                              (Create: 独立编译脚本 minSdk=19)
│   └── src/
│       ├── main/java/com/shilapi/performance/
│       │   ├── model/
│       │   │   ├── PerformanceSnapshot.kt            (Create: 硬件与网络纯数据类)
│       │   │   └── LinkType.kt                       (Create: USB、车机热点、Wi-Fi 客户端枚举)
│       │   ├── sampler/
│       │   │   ├── CpuSampler.kt                     (Create: 两阶段差分 CPU 采样器)
│       │   │   ├── MemorySampler.kt                  (Create: 物理内存与低内存预警)
│       │   │   ├── StorageSampler.kt                 (Create: 异步存储分区容量计算)
│       │   │   └── LinkSampler.kt                    (Create: 多链路智能感知器)
│       │   └── PerformanceMonitor.kt                 (Create: 异步门面聚合类)
│       └── test/java/com/shilapi/performance/
│           ├── model/
│           │   └── PerformanceSnapshotTest.kt        (Create: 数据格式化单元测试)
│           ├── sampler/
│           │   ├── CpuSamplerTest.kt                 (Create: /proc/stat 两阶段差分测试)
│           │   ├── MemorySamplerTest.kt              (Create: 内存与 LMK 测试)
│           │   └── LinkSamplerTest.kt                (Create: 多链路识别测试)
│           └── PerformanceMonitorTest.kt             (Create: 门面异步采样测试)
├── shared/
│   ├── src/main/java/com/shilapi/xcertplay/media/
│   │   ├── VideoStats.kt                             (Modify: 增加只读不重置的瞬时遥测快照)
│   │   └── AndroidMediaSink.kt                       (Modify: 暴露实时投屏指标接口)
│   └── src/test/java/com/shilapi/xcertplay/media/
│       └── VideoStatsTelemetryTest.kt                (Create: 验证只读帧率不破坏 logIfDue 计数器)
└── common/
    ├── build.gradle.kts                              (Modify: 依赖 project(":performance"))
    ├── src/main/res/values/strings.xml               (Modify: 增加性能监控中英文字符串)
    ├── src/main/res/values-zh/strings.xml            (Modify: 增加中文文案)
    ├── src/main/java/com/shilapi/xcertplay/
    │   ├── ui/component/QuickHudView.kt              (Modify: 集成性能监控条，支持异步刷帧与防误触)
    │   └── CarPlayHostActivity.kt                    (Modify: 绑定性能数据采样并在三指呼出时注入)
    └── src/test/java/com/shilapi/xcertplay/
        └── QuickHudViewTest.kt                       (Modify: 验证实验性功能下性能条显示、防冒泡与隐藏)
```

---

## Implementation Tasks

### Task 1: 创建独立 `:performance` 模块并配置 Gradle 构建

**Files:**
- Create: `performance/build.gradle.kts`
- Modify: `settings.gradle`
- Modify: `common/build.gradle.kts`

**Interfaces:**
- Consumes: None
- Produces: Gradle module `:performance` with Android Library configuration

- [ ] **Step 1: 创建 `performance/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
}

android {
    namespace = "com.shilapi.performance"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 19
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.17")
}
```

- [ ] **Step 2: 在 `settings.gradle` 中增加 `include(":performance")`**

- [ ] **Step 3: 在 `common/build.gradle.kts` 的 dependencies 增加 `implementation(project(":performance"))`**

- [ ] **Step 4: 运行 Gradle 编译测试验证工程配置正确**

Run: `./gradlew :performance:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add settings.gradle performance/build.gradle.kts common/build.gradle.kts
git commit -m "build: add independent :performance module"
```

---

### Task 2: 定义 `:performance` 数据模型与链路类型

**Files:**
- Create: `performance/src/main/java/com/shilapi/performance/model/LinkType.kt`
- Create: `performance/src/main/java/com/shilapi/performance/model/PerformanceSnapshot.kt`
- Create: `performance/src/test/java/com/shilapi/performance/model/PerformanceSnapshotTest.kt`

**Interfaces:**
- Consumes: None
- Produces: `enum class LinkType`, `data class PerformanceSnapshot`, `CpuMetrics`, `MemoryMetrics`, `StorageMetrics`, `LinkMetrics`, `MediaMetrics`

- [ ] **Step 1: 编写测试用例 `PerformanceSnapshotTest.kt` 验证多链路与指标文本输出**

```kotlin
package com.shilapi.performance.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceSnapshotTest {
    @Test
    fun formatsSummaryLineCorrectlyForWifi() {
        val snapshot = PerformanceSnapshot(
            cpu = CpuMetrics(usagePercent = 25, coreCount = 4, frequencyMhz = 1500),
            memory = MemoryMetrics(usedBytes = 1024L * 1024 * 1200, totalBytes = 1024L * 1024 * 2048, isLowMemory = false),
            storage = StorageMetrics(usedBytes = 1024L * 1024 * 1024 * 18, totalBytes = 1024L * 1024 * 1024 * 32),
            link = LinkMetrics(type = LinkType.WIFI_CLIENT, detail = "5 GHz · 433 Mbps (-48 dBm)"),
            media = MediaMetrics(rxFps = 30.0f, shownFps = 30.0f, touchLatencyMs = 82, droppedFrames = 0),
        )
        assertEquals("CPU: 25% (4核 @ 1.5GHz)", snapshot.cpuText)
        assertEquals("RAM: 1.2 GB / 2.0 GB (58%)", snapshot.memoryText)
        assertEquals("链路: Wi-Fi · 5 GHz · 433 Mbps (-48 dBm)", snapshot.linkText)
        assertEquals("FPS: 30.0 · 延迟: 82ms", snapshot.mediaText)
    }

    @Test
    fun formatsSummaryLineCorrectlyForUsb() {
        val snapshot = PerformanceSnapshot(
            cpu = CpuMetrics(usagePercent = -1, coreCount = 4, frequencyMhz = 0),
            memory = MemoryMetrics(usedBytes = 0, totalBytes = 0, isLowMemory = true),
            storage = StorageMetrics(usedBytes = 0, totalBytes = 0),
            link = LinkMetrics(type = LinkType.USB_WIRED, detail = "NCM / AOA"),
            media = null,
        )
        assertEquals("链路: 有线连接 (NCM / AOA)", snapshot.linkText)
        assertEquals("投屏: 待机", snapshot.mediaText)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :performance:testDebugUnitTest`
Expected: 编译报错

- [ ] **Step 3: 编写 `LinkType.kt` 和 `PerformanceSnapshot.kt`**

```kotlin
package com.shilapi.performance.model

enum class LinkType {
    USB_WIRED,
    CAR_HOTSPOT,
    WIFI_CLIENT,
    UNKNOWN,
}

data class CpuMetrics(
    val usagePercent: Int, // 0..100, -1 = 计算中或不可用
    val coreCount: Int,
    val frequencyMhz: Int, // <=0 = 不可用
)

data class MemoryMetrics(
    val usedBytes: Long,
    val totalBytes: Long,
    val isLowMemory: Boolean,
)

data class StorageMetrics(
    val usedBytes: Long,
    val totalBytes: Long,
)

data class LinkMetrics(
    val type: LinkType,
    val detail: String,
)

data class MediaMetrics(
    val rxFps: Float,
    val shownFps: Float,
    val touchLatencyMs: Long,
    val droppedFrames: Int,
)

data class PerformanceSnapshot(
    val cpu: CpuMetrics,
    val memory: MemoryMetrics,
    val storage: StorageMetrics,
    val link: LinkMetrics,
    val media: MediaMetrics? = null,
) {
    val cpuText: String
        get() {
            val usage = if (cpu.usagePercent >= 0) "${cpu.usagePercent}%" else "..."
            val freq = if (cpu.frequencyMhz > 0) {
                if (cpu.frequencyMhz >= 1000) String.format(java.util.Locale.US, "%.1fGHz", cpu.frequencyMhz / 1000.0)
                else "${cpu.frequencyMhz}MHz"
            } else "N/A"
            return "CPU: $usage (${cpu.coreCount}核 @ $freq)"
        }

    val memoryText: String
        get() {
            if (memory.totalBytes <= 0) return "RAM: N/A"
            val usedGb = memory.usedBytes / (1024.0 * 1024 * 1024)
            val totalGb = memory.totalBytes / (1024.0 * 1024 * 1024)
            val pct = (memory.usedBytes * 100 / memory.totalBytes).toInt()
            val warn = if (memory.isLowMemory) " [低内存警告]" else ""
            return String.format(java.util.Locale.US, "RAM: %.1f GB / %.1f GB (%d%%)%s", usedGb, totalGb, pct, warn)
        }

    val storageText: String
        get() {
            if (storage.totalBytes <= 0) return "机身存储: N/A"
            val usedGb = storage.usedBytes / (1024.0 * 1024 * 1024)
            val totalGb = storage.totalBytes / (1024.0 * 1024 * 1024)
            val pct = (storage.usedBytes * 100 / storage.totalBytes).toInt()
            return String.format(java.util.Locale.US, "机身存储: %.1f GB / %.1f GB (%d%%)", usedGb, totalGb, pct)
        }

    val linkText: String
        get() = when (link.type) {
            LinkType.USB_WIRED -> "链路: 有线连接 (${link.detail})"
            LinkType.CAR_HOTSPOT -> "链路: 车机热点 · ${link.detail}"
            LinkType.WIFI_CLIENT -> "链路: Wi-Fi · ${link.detail}"
            LinkType.UNKNOWN -> "链路: ${link.detail}"
        }

    val mediaText: String
        get() {
            if (media == null) return "投屏: 待机"
            val touch = if (media.touchLatencyMs >= 0) " · 延迟: ${media.touchLatencyMs}ms" else ""
            return String.format(java.util.Locale.US, "FPS: %.1f%s", media.shownFps, touch)
        }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :performance:testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add performance/src/
git commit -m "feat(performance): add LinkType and PerformanceSnapshot models"
```

---

### Task 3: 实现两阶段差分 CPU、异步存储、内存及多链路采样器

**Files:**
- Create: `performance/src/main/java/com/shilapi/performance/sampler/CpuSampler.kt`
- Create: `performance/src/main/java/com/shilapi/performance/sampler/MemorySampler.kt`
- Create: `performance/src/main/java/com/shilapi/performance/sampler/StorageSampler.kt`
- Create: `performance/src/main/java/com/shilapi/performance/sampler/LinkSampler.kt`
- Create: `performance/src/main/java/com/shilapi/performance/PerformanceMonitor.kt`
- Create: `performance/src/test/java/com/shilapi/performance/sampler/CpuSamplerTest.kt`
- Create: `performance/src/test/java/com/shilapi/performance/sampler/LinkSamplerTest.kt`

**Interfaces:**
- Consumes: `Context`, `PerformanceSnapshot`
- Produces: `PerformanceMonitor.sampleAsync(context, isWired, mediaProvider, onResult)`

- [ ] **Step 1: 编写 `CpuSamplerTest.kt` 验证两阶段差分算法**

```kotlin
package com.shilapi.performance.sampler

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuSamplerTest {
    @Test
    fun firstSampleReturnsNegativeOneAndArmsBaseline() {
        val sampler = CpuSampler()
        val line1 = "cpu  100 20 30 850 0 0 0 0 0 0"
        val first = sampler.computeUsageFromLine(line1)
        assertEquals(-1, first) // 第一次采样作为基准，返回 -1 表示计算中
    }

    @Test
    fun secondSampleCalculatesInstantaneousDelta() {
        val sampler = CpuSampler()
        val line1 = "cpu  100 20 30 850 0 0 0 0 0 0" // total 1000, idle 850
        val line2 = "cpu  200 40 60 900 0 0 0 0 0 0" // total 1200, idle 900 -> totalDelta 200, idleDelta 50 -> 75%
        sampler.computeUsageFromLine(line1)
        val usage = sampler.computeUsageFromLine(line2)
        assertEquals(75, usage)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :performance:testDebugUnitTest`
Expected: FAIL

- [ ] **Step 3: 实现各采样器**
  - `CpuSampler`: 实现两阶段时间片差分，并附带核心数和当前主频探测（`/sys/devices/system/cpu/...`）。
  - `MemorySampler`: 调用 `ActivityManager.MemoryInfo` 获取物理内存与 lowMemory 标志。
  - `StorageSampler`: 通过 `StatFs` 获取 data 目录空间。
  - `LinkSampler`: 智能识别有线/热点/Wi-Fi，附带 `Build.VERSION.SDK_INT >= 21` 频段保护。
  - `PerformanceMonitor`: 内部维护单线程 `ExecutorService`，提供 `sampleAsync(context, isWired, mediaProvider, callback)`，彻底移出主线程。

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :performance:testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add performance/src/
git commit -m "feat(performance): implement asynchronous samplers with 2-phase CPU and link detection"
```

---

### Task 4: 改造 `VideoStats` 暴露只读滑动窗口遥测，保护诊断日志

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/VideoStats.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`
- Create: `shared/src/test/java/com/shilapi/xcertplay/media/VideoStatsTelemetryTest.kt`

**Interfaces:**
- Consumes: None
- Produces: `VideoStats.readCurrentTelemetry(): VideoTelemetrySnapshot` (只读，绝不重置 counters)

- [ ] **Step 1: 编写 `VideoStatsTelemetryTest.kt` 验证只读遥测不破坏 logIfDue 的 5 秒统计周期**

```kotlin
package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VideoStatsTelemetryTest {
    @Test
    fun readingTelemetryDoesNotResetDiagnosticCounters() {
        var now = 1_000_000_000L
        val stats = VideoStats(nanoTime = { now })
        stats.onReceived(1024)
        stats.onRendered(now)

        val telemetry = stats.readCurrentTelemetry()
        assertNotNull(telemetry)

        // 模拟 5 秒窗口到达，验证 logIfDue 依然能拿到完整的 1 帧统计
        now += 5_000_000_000L
        val line = stats.logIfDue()
        assertNotNull(line)
        org.junit.Assert.assertTrue("logIfDue 必须保留统计帧，未被遥测清空", line!!.contains("rx=0.2fps"))
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :shared:testDebugUnitTest`
Expected: FAIL

- [ ] **Step 3: 实现 `VideoStats.readCurrentTelemetry()` 与 `AndroidMediaSink.currentTelemetry()`**
  - 使用当前采样点与上一个采样点的只读差值，严禁调用 `getAndSet(0)`。

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :shared:testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/
git commit -m "feat(media): provide non-destructive readCurrentTelemetry in VideoStats"
```

---

### Task 5: 在 `QuickHudView` 中集成性能遥测条，防误触与异步生命周期管理

**Files:**
- Modify: `common/src/main/res/values/strings.xml`
- Modify: `common/src/main/res/values-zh/strings.xml`
- Modify: `common/src/main/java/com/shilapi/xcertplay/ui/component/QuickHudView.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`
- Modify: `common/src/test/java/com/shilapi/xcertplay/QuickHudViewTest.kt`

**Interfaces:**
- Consumes: `PerformanceMonitor.sampleAsync()`, `ExperimentalFeatures.enabled`
- Produces: UI component inside `QuickHudView`

- [ ] **Step 1: 编写 `QuickHudViewTest` 增加对性能遥测条存在性、防误触与生命周期的断言测试**

```kotlin
@Test
fun telemetryBarVisibleOnlyWhenExperimentalEnabled() {
    ExperimentalFeatures.override(true)
    val view = hud()
    view.open()
    assertTrue(hasText(view, "CPU:"))

    ExperimentalFeatures.override(false)
    val viewProd = hud()
    viewProd.open()
    assertFalse(hasText(viewProd, "CPU:"))
}

@Test
fun clickingTelemetryBarDoesNotDismissHud() {
    ExperimentalFeatures.override(true)
    val view = hud()
    view.open()
    val telemetryView = findTelemetryView(view)
    telemetryView?.performClick()
    assertEquals(0, dismissals) // 点击性能条不应误触背景蒙层关闭
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.QuickHudViewTest`
Expected: FAIL

- [ ] **Step 3: 在 `QuickHudView` 中构建 `telemetryBar`**
  - 设置 `isClickable = true` 防点击事件穿透到蒙层。
  - 在 `open()` 时触发初次异步采样；并在 300ms 后触发第二阶段采样（呈现瞬时 CPU）；如果 HUD 持续处于打开状态，每 1.5 秒异步刷新一次。
  - 在 `dismissNow()` 时调用 `removeCallbacks`，停止一切后台采样，清理资源。
  - 当 `ExperimentalFeatures.enabled == false` 时，不构建该 View。

- [ ] **Step 4: 在 `CarPlayHostActivity.kt` 中注入异步数据源与有线/热点状态**

- [ ] **Step 5: 运行 `QuickHudViewTest` 验证通过**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.QuickHudViewTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add common/
git commit -m "feat(hud): integrate asynchronous performance telemetry bar into QuickHudView"
```

---

### Task 6: 完整构建验证与回归检查

**Files:**
- Entire repository

- [ ] **Step 1: 运行全量单元测试**

Run: `./gradlew :performance:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest`
Expected: All tests PASS

- [ ] **Step 2: 运行既有测试确保核心流程无退化**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.ExperimentalFeaturesUiTest`
Expected: PASS

- [ ] **Step 3: 运行完整 assembleDebug 构建**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`
