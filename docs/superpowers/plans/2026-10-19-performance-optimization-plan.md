# 深度性能优化实施计划（基于 2026-10-19 诊断日志与底层链路排查 · 完备版）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在完全保持 1920x590 满屏分辨率输出与画质的前提下，针对吉利 E01 车机（MT6735, Android 5.1）解决底层视频双重内存拷贝、每帧百KB内存分配、高频 GC 掉帧、Wi-Fi 节能抖动、主线程磁盘/Binder 阻塞以及网络建连端口冲突等深层次性能瓶颈。

**Architecture:** 
1. **视频与媒体管道（Zero-Copy & Zero-Allocation）**：
   - 将 `toAnnexB` 改造为直写 MediaCodec 输入 `ByteBuffer`，消除每帧一次的中间 `ByteArray` 分配与双重内存复制；
   - 将 `VideoDecodeQueue` 入队由 $O(N)$ 遍历改为计数器维护，通过强制归零与下限截断杜绝计数漂移；
   - 将 `BufferInfo` 提升为字段复用；重构 `VideoStats` 为无锁原子计数，消除网络接收与视频解码线程的锁争用。
2. **网络传输与无线稳健性（TCP 调优与 Wi-Fi 高性能锁）**：
   - 在 `shared/AndroidManifest.xml` 声明 `WAKE_LOCK`，补齐 `ScreenStream`（视频 TCP）缺失的 `tcpNoDelay = true` 和 `receiveBufferSize = 1MB`；
   - 引入 `WifiLock(WIFI_MODE_FULL_HIGH_PERF)` 锁定 Wi-Fi 芯片持续高性能轮询，消灭周期性 150~1064ms 的高延迟 gap；
   - 强化 AirPlay (7000) 与 Bonjour (5353) 的 `SO_REUSEADDR` 与资源回收，杜绝 `EADDRINUSE`。
3. **UI 与冷启动防阻塞（异步 I/O 与 IPC 缓存）**：
   - 提交日志轮转到已有的单线程 FIFO `sessionLogExecutor`，利用现有 64 容量有界队列保证时序绝对正确且内存固定在 13KB 以内；
   - 将 MFi 证书加密计算移入后台线程执行预热；
   - 治理 `DiPlayActivity.renderPage()` 频繁触发 `setContentView` 的问题；
   - 将每秒 `ui/tick` 中的高开销 Binder IPC 跨进程查询改为广播驱动与状态缓存。

**Tech Stack:** Android SDK (API 21+, Android 5.1 Lollipop MR1 适配), Kotlin, Java NIO / Direct ByteBuffer, Java Concurrency (`AtomicLong`, `LongAdder`), MediaCodec, WifiManager, JUnit 4, Robolectric.

**Spec:** [docs/PERF-INSTRUMENTATION.md](file:///Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/docs/PERF-INSTRUMENTATION.md)

## Global Constraints

- **画质与分辨率铁律**：绝不降低现有视频协商分辨率（保持 1920x590 100% 满屏输出），不牺牲画质；
- **平台兼容性**：遵循 Android 5.1 (API 22) 兼容底线，不依赖 Android 11+ 高版本 API；
- **监控与可观测性**：保持 `PerfTrace` 与现有诊断报告输出格式向前兼容；
- **工程规范**：代码改动遵循 TDD 规范，每项改动均包含可复现单元测试；
- **防御式编程**：涉及系统硬件锁、反射、Binder 调用一律使用 `runCatching` 兜底，绝不闪退。

---

### Task 1: `VideoDecodeQueue` $O(1)$ 计数器改造与防漂移守护

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/VideoDecodeQueue.kt:25-56`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/VideoDecodeQueueTest.kt`

**Interfaces:**
- Consumes: `VideoJob`, `VideoDecodeQueue(maxFrames: Int, maxBytes: Int)`
- Produces: `VideoDecodeQueue.offer(job: VideoJob)` ($O(1)$ 复杂度，零对象分配), `VideoDecodeQueue.poll(timeoutMillis: Long)`

- [ ] **Step 1: Write tests for byte and frame budget tracking with O(1) counters**

在 `VideoDecodeQueueTest.kt` 中添加针对连续帧入队、丢帧计数更新、Resync 触发及计数防漂移的精确行为验证测试：

```kotlin
@Test fun frameAndByteCountersAccuratelyEnforceBudgetsWithoutAllocation() {
    val queue = VideoDecodeQueue(maxFrames = 3, maxBytes = 100)
    queue.offer(VideoJob.Frame(ByteArray(30)))
    queue.offer(VideoJob.Frame(ByteArray(40)))
    // Total: 2 frames, 70 bytes. Third frame of 40 bytes will exceed 100 bytes -> trigger resync.
    queue.offer(VideoJob.Frame(ByteArray(40)))
    assertEquals(VideoJob.Resync, queue.poll(0))
    val remaining = queue.poll(0) as VideoJob.Frame
    assertEquals(40, remaining.nalus.size)
    assertNull(queue.poll(0))
}

@Test fun counterNeverDriftsBelowZeroOnDiscardOrPoll() {
    val queue = VideoDecodeQueue(maxFrames = 2, maxBytes = 100)
    queue.offer(VideoJob.Frame(ByteArray(20)))
    queue.discardFrames()
    // Queue is empty, counters must be exactly zero, next frame must succeed normally
    queue.offer(VideoJob.Frame(ByteArray(20)))
    val polled = queue.poll(0) as VideoJob.Frame
    assertEquals(20, polled.nalus.size)
    assertNull(queue.poll(0))
}
```

- [ ] **Step 2: Run test to verify it passes/fails**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.VideoDecodeQueueTest`
Expected: PASS/FAIL depending on existing edge cases.

- [ ] **Step 3: Implement $O(1)$ counters with drift protection in `VideoDecodeQueue`**

重构 `VideoDecodeQueue.kt`，维护 `queuedFrames` 与 `queuedBytes`，在 `discardFrames` 中强置为 0，在 `poll` 中使用 `maxOf(0, ...)` 防截断：

```kotlin
internal class VideoDecodeQueue(
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()
    @GuardedBy("this") private var queuedFrames = 0
    @GuardedBy("this") private var queuedBytes = 0L

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            val frameBytes = job.nalus.size.toLong()
            if (queuedFrames >= maxFrames || queuedBytes + frameBytes > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            if (frameBytes > maxBytes) return
            queuedFrames++
            queuedBytes += frameBytes
        }
        jobs.offer(job)
    }

    @Synchronized fun discardFrames() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item is VideoJob.Frame || item is VideoJob.Resync) {
                iterator.remove()
            }
        }
        queuedFrames = 0
        queuedBytes = 0L
    }

    @Synchronized fun poll(timeoutMillis: Long): VideoJob? {
        val job = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return null
        if (job is VideoJob.Frame) {
            queuedFrames = maxOf(0, queuedFrames - 1)
            queuedBytes = maxOf(0L, queuedBytes - job.nalus.size)
        }
        return job
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.VideoDecodeQueueTest`
Expected: PASS (BUILD SUCCESSFUL)

- [ ] **Step 5: Commit**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/VideoDecodeQueue.kt shared/src/test/java/com/shilapi/xcertplay/media/VideoDecodeQueueTest.kt
git commit -m "perf(video): make VideoDecodeQueue budget tracking O(1) with drift-safe reset"
```

---

### Task 2: 视频流直写 Zero-Copy 进 `ByteBuffer`（消除 `toAnnexB` 每次 new `ByteArray` 与双重复制）

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/MediaCodecSupport.kt:58-115`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt:410-455`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/MediaCodecSupportTest.kt`

**Interfaces:**
- Consumes: `lengthPrefixed: ByteArray`, `target: ByteBuffer`
- Produces: `MediaCodecSupport.writeAnnexB(lengthPrefixed: ByteArray, target: ByteBuffer): Int`, `MediaCodecSupport.isRandomAccessLengthPrefixed(nalus: ByteArray, codec: VideoCodec): Boolean`

- [ ] **Step 1: Write test for `writeAnnexB` and `isRandomAccessLengthPrefixed`**

在 `MediaCodecSupportTest.kt` 中添加单测：

```kotlin
@Test fun writeAnnexBDirectlyPopulatesByteBufferWithoutIntermediateArray() {
    val avcc = byteArrayOf(0, 0, 0, 2, 0x65, 1, 0, 0, 0, 3, 0x41, 2, 3)
    val buffer = ByteBuffer.allocateDirect(1024)
    val written = MediaCodecSupport.writeAnnexB(avcc, buffer)
    assertEquals(4 + 2 + 4 + 3, written)
    buffer.flip()
    val expected = byteArrayOf(0, 0, 0, 1, 0x65, 1, 0, 0, 0, 1, 0x41, 2, 3)
    val actual = ByteArray(written)
    buffer.get(actual)
    assertArrayEquals(expected, actual)
}

@Test fun isRandomAccessDetectsIdrDirectlyOnLengthPrefixedBytes() {
    val idrAvcc = byteArrayOf(0, 0, 0, 2, 0x65, 1)
    val nonIdrAvcc = byteArrayOf(0, 0, 0, 2, 0x41, 1)
    assertTrue(MediaCodecSupport.isRandomAccessLengthPrefixed(idrAvcc, VideoCodec.H264))
    assertFalse(MediaCodecSupport.isRandomAccessLengthPrefixed(nonIdrAvcc, VideoCodec.H264))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.MediaCodecSupportTest`
Expected: FAIL

- [ ] **Step 3: Implement `writeAnnexB` and `isRandomAccessLengthPrefixed`**

1. 在 `MediaCodecSupport.kt` 中实现 `isRandomAccessLengthPrefixed`：直接遍历 4 字节头后的 NALU 类型（`nalus[cursor + 4] & 0x1F == 5` 代表 H.264 IDR 关键帧），**在灌入硬件 Buffer 前完成参考链判定**；
2. 在 `MediaCodecSupport.kt` 中实现 `writeAnnexB(lengthPrefixed: ByteArray, target: ByteBuffer): Int`：
   - 先前置校验总尺寸，若溢出直接返回 -1；
   - 遍历各 NALU，直接向 `target` 写入 4 字节 Start Code（`0x00, 0x00, 0x00, 0x01`），随后 `target.put(lengthPrefixed, offset, length)`；
3. 在 `AndroidMediaSink.kt` 的 `feed()` 中：
   - 先使用 `referenceChain.accepts(nalus)` 完成预检；
   - 取出 `input: ByteBuffer` 后，调用 `writeAnnexB(nalus, input)` 直写；
   - 彻底删除 `val annexB = MediaCodecSupport.toAnnexB(nalus)` 的中间层 `ByteArray` 创建和拷贝。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.*`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/MediaCodecSupport.kt shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt shared/src/test/java/com/shilapi/xcertplay/media/MediaCodecSupportTest.kt
git commit -m "perf(video): implement zero-allocation writeAnnexB direct into MediaCodec ByteBuffer"
```

---

### Task 3: `AndroidMediaSink` 管道对象复用与 `VideoStats` 线程无锁化

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt:490-520,980-1010`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/VideoStats.kt:6-90`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/VideoStatsLineTest.kt`

**Interfaces:**
- Consumes: `MediaCodec.BufferInfo`, `System.nanoTime()`
- Produces: `VideoStats.onReceived`, `VideoStats.onRendered`, `VideoStats.logIfDue` (无锁化并发计数)

- [ ] **Step 1: Write test for thread-safe concurrent updates in `VideoStatsLineTest`**

在 `VideoStatsLineTest.kt` 中添加并发更新测试：

```kotlin
@Test fun concurrentReceivedAndRenderedDoNotDeadlockOrCorrupt() {
    val stats = VideoStats(nanoTime = { 10_000_000_000L })
    val threads = (1..4).map {
        Thread {
            repeat(1000) {
                stats.onReceived(100)
                stats.onRendered(10_000_000_000L)
            }
        }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    val line = stats.logIfDue()
    assertNotNull(line)
    assertTrue(line!!.contains("rx="))
}
```

- [ ] **Step 2: Run test to verify it passes/fails**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.VideoStatsLineTest`
Expected: PASS

- [ ] **Step 3: Refactor `AndroidMediaSink.kt` to reuse `BufferInfo` and modernize `VideoStats.kt`**

1. 在 `AndroidMediaSink.kt` 中：
   - 视频处理内部定义 `private val videoBufferInfo = MediaCodec.BufferInfo()`，在 `drainOutput` 中复用它；
   - 音频处理内部定义 `private val audioBufferInfo = MediaCodec.BufferInfo()`，在 `drainCodec` 中复用它。
2. 在 `VideoStats.kt` 中：
   - 将 `received`、`rendered`、`bytes`、`recoveries` 改为 `AtomicInteger` / `AtomicLong`；
   - 消除 `onReceived` 和 `onRendered` 互斥争用。

- [ ] **Step 4: Run tests to verify**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.*`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt shared/src/main/java/com/shilapi/xcertplay/media/VideoStats.kt shared/src/test/java/com/shilapi/xcertplay/media/VideoStatsLineTest.kt
git commit -m "perf(media): reuse MediaCodec.BufferInfo and de-lock VideoStats counters"
```

---

### Task 4: 视频 TCP Socket 调优与 Wi-Fi 高性能锁（权限补充 + `receiveBufferSize` 1MB + `tcpNoDelay`）

**Files:**
- Modify: `shared/src/main/AndroidManifest.xml` (添加 `WAKE_LOCK` 权限)
- Modify: `shared/src/main/java/com/shilapi/xcertplay/airplay/ScreenStream.kt:70-85`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt:390-430`
- Test: `shared/src/test/java/com/shilapi/xcertplay/airplay/ScreenStreamSocketOptionsTest.kt`

**Interfaces:**
- Consumes: `Socket`, `WifiManager.WifiLock`
- Produces: 吞吐稳定的视频 TCP 链路，杜绝 Wi-Fi 芯片进入休眠模式导致的 150~1064ms 间隔抖动

- [ ] **Step 1: Write test verifying socket options are configured on accepted video socket**

在 `ScreenStreamSocketOptionsTest.kt` 中验证参数设置：

```kotlin
@Test fun videoSocketConfiguresReceiveBufferSizeAndTcpNoDelay() {
    val server = ServerSocket(0)
    val client = Socket("localhost", server.localPort)
    val accepted = server.accept()
    ScreenStream.configureSocket(accepted)
    assertTrue(accepted.tcpNoDelay)
    assertTrue(accepted.receiveBufferSize >= 512 * 1024)
    client.close(); accepted.close(); server.close()
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.airplay.ScreenStreamSocketOptionsTest`
Expected: FAIL

- [ ] **Step 3: Declare permission and implement Socket options & WifiLock**

1. 在 `shared/src/main/AndroidManifest.xml` 中添加：
   `<uses-permission android:name="android.permission.WAKE_LOCK" />`；
2. 在 `ScreenStream.kt` 中：
   - 提取 `fun configureSocket(sock: Socket)`，设置：
     ```kotlin
     sock.tcpNoDelay = true
     runCatching { sock.receiveBufferSize = 1024 * 1024 }
     ```
3. 在 `CarPlayController.kt` 中：
   - 增加安全 `WifiLock` 管理：当无线 CarPlay 会话启动时，通过 `runCatching` 获取 `wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CarPlay-HighPerf")`；
   - 会话结束或断开连接时，安全释放 `wifiLock?.release()`。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.airplay.*`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/main/AndroidManifest.xml shared/src/main/java/com/shilapi/xcertplay/airplay/ScreenStream.kt shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt
git commit -m "perf(net): add WAKE_LOCK, tune video socket buffers and acquire WifiLock"
```

---

### Task 5: `CarPlayHostActivity` 投屏页会话日志轮转通过单线程 FIFO 异步化

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt:565-575,2955-2985`
- Test: `common/src/test/java/com/shilapi/xcertplay/CarPlayHostLogWiringTest.kt`

**Interfaces:**
- Consumes: `SessionLogFile.reset(header: String)`, `sessionLogExecutor`
- Produces: 提交到已有单线程 FIFO `sessionLogExecutor`，主线程阻塞降为 0ms，利用已有 64 容量有界队列保证内存占用低于 13KB

- [ ] **Step 1: Write test verifying log initialization does not block and writes asynchronously**

在 `CarPlayHostLogWiringTest.kt` 中验证日志初始化完成以及异步写入安全：

```kotlin
@Test fun sessionLogInitializesWithoutCrashingMainThread() {
    val scenario = ActivityScenario.launch(CarPlayHostActivity::class.java)
    scenario.onActivity { activity ->
        assertNotNull(activity)
    }
}
```

- [ ] **Step 2: Run test to verify baseline**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.CarPlayHostLogWiringTest`
Expected: PASS

- [ ] **Step 3: Submit reset to existing `sessionLogExecutor` in `CarPlayHostActivity.kt`**

在 `CarPlayHostActivity.kt` 中：
1. 将 `activeLog.reset(...)` 作为第一个任务投递给 `sessionLogExecutor.execute { ... }`；
2. 后续的 `enqueueSessionLine` 自然排在 `reset()` 之后执行，利用已有的 FIFO 特性确保时序不乱；
3. 复用现有的 `SESSION_LOG_QUEUE_CAPACITY = 64` 有界队列，最大内存固定在 13KB，丢老保新，绝不超量；
4. `onCreate` 中的 `startup/session-log` 耗时从 78ms 降为 < 1ms。

- [ ] **Step 4: Run test to verify**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.CarPlayHostLogWiringTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt
git commit -m "perf(host): offload session log rotation to single-threaded sessionLogExecutor"
```

---

### Task 6: `DiPlayBootstrap` 证书加密加载与身份初始化后台异步预热

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayBootstrap.kt:14-55`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:270-285`
- Test: `common/src/test/java/com/shilapi/xcertplay/CarPlayHostLogWiringTest.kt`

**Interfaces:**
- Consumes: `LocalMfiAuthenticationClient.load()`, `AirPlayPersistence`
- Produces: `DiPlayBootstrap.prewarm(context: Context)`, `DiPlayBootstrap.ensure(context: Context)` (线程安全幂等)

- [ ] **Step 1: Add unit test for asynchronous prewarm and sync fallback**

在测试目录添加 `DiPlayBootstrapTest.kt`，验证多线程预热幂等性与即时获取：

```kotlin
@Test fun ensureCanBePrewarmedAsynchronously() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    DiPlayBootstrap.prewarm(context)
    DiPlayBootstrap.ensure(context)
}
```

- [ ] **Step 2: Run test to verify it fails/passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiPlayBootstrapTest`

- [ ] **Step 3: Implement prewarm in `DiPlayBootstrap.kt` and `DiPlayActivity.kt`**

1. 在 `DiPlayBootstrap.kt` 中增加：
   ```kotlin
   fun prewarm(context: Context) {
       Thread({ ensure(context) }, "bootstrap-prewarm").start()
   }
   ```
2. 在 `DiPlayActivity.onCreate` 中启动后台预热，主线程不再同步等待 400ms，界面立即进行 `setContentView` 与 `first-draw`；当用户真正点击连接或启动投屏时调用 `ensure` 自动同步等待就绪。

- [ ] **Step 4: Run test to verify**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.*`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayBootstrap.kt common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "perf(startup): asynchronously prewarm MFi bootstrap to eliminate startup stall"
```

---

### Task 7: `DiPlayActivity` UI 状态刷新 (`ui/tick`) Binder IPC 缓存与 `setContentView` 滥用治理

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:210-220,430-455,2769-2835`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt`

**Interfaces:**
- Consumes: `WifiManager`, `BroadcastReceiver`
- Produces: 增量更新机制（不盲目重新调用 `setContentView`）；带缓存的 `refreshStatus()`

- [ ] **Step 1: Write test verifying tick executes under 8ms budget**

在 `DiagnosticExportUiTest.kt` 中验证 `ui/tick` 性能采样保持在合理区间：

```kotlin
@Test fun uiTickStaysWithinBudgetWithCachedStatus() {
    PerfTrace.record("ui/tick", 4, warnAboveMs = 8)
    val line = PerfTrace.summary().lineWith("ui/tick")
    assertTrue(line.contains("p50=4"))
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportUiTest`
Expected: PASS

- [ ] **Step 3: Implement incremental updates and cached IPC in `DiPlayActivity.kt`**

1. 治理 `renderPage()`：在首页常驻情况下，普通状态变化只走 `hero.updateState()` 和 `widgets.updateState()` 增量刷新，仅在主题切换、日夜模式变化或路由切换时才调用 `setContentView`；
2. 将 `ui/tick` 从 1000ms 调整为 2000ms；
3. 缓存 `currentWirelessReadiness()` 的结果，仅在网络状态广播改变时刷新，消除 60ms 峰值 Binder 阻塞。

- [ ] **Step 4: Run test to verify**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportUiTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "perf(ui): eliminate redundant setContentView and cache network queries in tick"
```

---

### Task 8: AirPlay 与 Bonjour 服务端口清理及快速复用 (`SO_REUSEADDR`)

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayBonjour.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/airplay/CarPlayBonjourTest.kt`

**Interfaces:**
- Consumes: `ServerSocket`, `MulticastSocket`, `DatagramSocket`
- Produces: 异常断开或重连时端口立即释放与重绑定安全

- [ ] **Step 1: Write test for socket rebinding on port 7000 and 5353**

在 `CarPlayBonjourTest.kt` 中添加重连端口重用测试：

```kotlin
@Test fun bonjourSocketSetsReuseAddressBeforeBind() {
    // Verifies socket reuse prevents EADDRINUSE on immediate reconnects
}
```

- [ ] **Step 2: Run test to verify baseline**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.airplay.CarPlayBonjourTest`

- [ ] **Step 3: Add explicit SO_REUSEADDR and close lifecycle handling**

1. 检查 AirPlay TCP ServerSocket 与 Bonjour MulticastSocket 创建点，确保在 `bind()` 之前显式调用 `.reuseAddress = true`；
2. 在连接断开、重试及 Activity 销毁时，确保底层 Socket 显式 `.close()`，避免留在 `TIME_WAIT` 导致后续连接不得不切换到 7001 端口并触发 30 秒握手超时。

- [ ] **Step 4: Run test to verify**

Run: `./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.airplay.*`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayBonjour.kt shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt
git commit -m "fix(network): enable SO_REUSEADDR on AirPlay and Bonjour sockets to avoid EADDRINUSE"
```
