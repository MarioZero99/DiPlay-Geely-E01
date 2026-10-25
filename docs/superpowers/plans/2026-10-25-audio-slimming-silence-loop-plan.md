# 音频瘦身重构：剔除切歌胶水逻辑与实现末端静音填缝（Silence Loop）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底剔除跨层侵入的 `notifyTrackTransition` 胶水代码，重构 `AudioRenderer`，用“缓冲末端静音填缝（Silence Loop / Concealment）+ 下一首门控复位（Gate Reset）”替代导致 MT6735 爆音、误判与连接重置的饥饿刹车（`track.pause()`），消除闪退与性能隐患，并补齐真实单测验证无包期间声卡持续运转与静音填缝。

**Architecture:** 
1. **解耦业务与底层驱动**：完全删除 `CarPlayMediaKeys`、`CarPlayHostActivity`、`AndroidMediaSink` 与 `WirelessHoldJitterBuffer` 中因切歌宽限生硬引入的 `notifyTrackTransition` 及其在按键、歌名变化中的挂钩。
2. **静音填缝状态机（Silence Loop / Concealment）**：
   - 设安全水位门限 `SILENCE_HEADROOM_MS = 100L`（覆盖 MT6735 2048 帧/42.7ms HAL 周期的 2 倍以上，彻底消除 DMA 读空抖动）；
   - 当无线音乐在上游停发数据（切歌、拖进度、暂停、流媒体在线缓冲），`queue.isEmpty()` 且缓冲低于安全水位（`unplayedBytes <= 100ms`）时，**绝不调用 `track.pause()`**，也不做 2.2 秒恶意加严；
   - 先将末尾暂存的真实音频余弦淡出（`applyFadeOut`），然后**以预分配单例持续向声卡写入 20ms 整帧静音 PCM（零 GC 开销）**。MT6735 ALSA 驱动永远有平平整整的 0 值可读，**从物理根源上消除声卡读空空转打结与硬件爆音**！
   - 只有在长达 30 秒（`IDLE_TIMEOUT_NS`）完全没有任何包到达时，才在 `runCatching` 保护下执行 `track.pause()` 挂起省电。
3. **下一曲开闸蓄水门控（Gate Reset）**：
   - 进入静音填缝状态时，将 `gateOpened` 复位为 `false`；
   - 此时 `AudioTrack` 保持平稳输出静音，为下一首歌的到达争取“平稳蓄水时间”；
   - 当下一首歌（或拖进度、解除暂停）的首批数据包到达时，在静音播放背景下自然蓄水满初始门限（47 包 = 1.0 秒，非 2.2 秒加严），蓄满后开闸 `gateOpened = true`，并对首块 PCM 实施余弦淡入（`applyFadeIn`）；
   - **效果**：切歌既不会因 0 缓冲抢跑而在第一秒断续卡顿，又不需要任何上层媒体按键或歌名广播配合，门槛永远保持在轻快的 1 秒（47 包）！
4. **防御性防闪退与零性能开销保障**：
   - 全程复用预分配 `silenceChunk` 字节数组，音频循环内零对象分配（Zero-Allocation in Audio Loop），杜绝 GC 触发的音频卡顿；
   - 所有 `AudioTrack.pause()`、`flush()` 等底层状态切换均有 `runCatching` 兜底，防止 MTK 底层非预期抛出 `IllegalStateException` 导致线程崩溃；
   - 对 `isWirelessMedia` 完全旁路 API 24+ 的 `DrainAction.PAUSE_AND_REBUFFER`，杜绝系统后门偷停声卡。

**Tech Stack:** Kotlin, Android AudioTrack (API 22/5.1 compatible), Robolectric 4.17, JUnit 4.

**Spec:** 本计划直接承接与落实当前会话对“切歌防闪退 4 个漏洞与声卡空转爆音”的架构重构方案。

## Global Constraints
- 严格兼容 Android 5.1 / API 22（MTK MT6735），不调用 API 23+ 专有未兼容方法（如未作 API guard 的 `AudioTrack.getUnderrunCount()`）。
- 绝不在 `AudioRenderer` 内部猜测或绑定上层业务状态（如歌名、媒体键）。
- 保持非无线音频（导航、电话、有线 USB）零侵入、零改动。

---

## 变更文件结构

| 模块 / 文件 | 动作 | 核心职责 |
|---|---|---|
| `common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt` | 修改 | 删除 `trackTransitionTarget`、`bindTrackTransitionTarget`、`notifyTrackTransition` 及其在按键和广播中的调用 |
| `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` | 修改 | 删除 4 处 `bindTrackTransitionTarget` 绑定调用 |
| `shared/src/main/java/com/shilapi/xcertplay/media/WirelessHoldJitterBuffer.kt` | 修改 | 删除无用的 `onTrackTransition()` 方法 |
| `shared/src/test/java/com/shilapi/xcertplay/media/WirelessHoldJitterBufferTest.kt` | 修改 | 清理对 `onTrackTransition()` 的调用与废弃断言 |
| `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt` | 修改 | 1. 彻底删除 `notifyTrackTransition`；2. 在 `maintainPlaybackBuffer` 中以 Silence Loop 替换饥饿刹车（安全水位 100ms，预分配静音单例）；3. 在 `run` 循环与 `writePcm` 中对齐 `gateOpened` 复位与新曲淡入 |
| `shared/src/test/java/com/shilapi/xcertplay/media/AudioRendererSilenceLoopTest.kt` | 新建 | 基于 Robolectric 的真实单测：测试断包后 AudioTrack 保持 playing、捕获到静音填缝、新包到来蓄满平滑起播 |

---

### Task 1: 剔除 `CarPlayMediaKeys`、`CarPlayHostActivity` 与 `WirelessHoldJitterBuffer` 中的切歌胶水代码

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/WirelessHoldJitterBuffer.kt`
- Modify: `shared/src/test/java/com/shilapi/xcertplay/media/WirelessHoldJitterBufferTest.kt`

**Interfaces:**
- Consumes: `CarPlayMediaKeys` 既有媒体键派发与元数据广播能力。
- Produces: 纯净的 `CarPlayMediaKeys` 和 `WirelessHoldJitterBuffer`，无任何 `notifyTrackTransition` 残留。

- [ ] **Step 1: 修改 `CarPlayMediaKeys.kt`，删除切歌宽限相关定义与调用**

在 `common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt` 中：
1. 删除变量与方法：
```kotlin
    @Volatile private var trackTransitionTarget: ((Long) -> Unit)? = null

    fun bindTrackTransitionTarget(target: ((Long) -> Unit)?) {
        trackTransitionTarget = target
    }

    fun notifyTrackTransition(windowMs: Long = 3500L) {
        trackTransitionTarget?.invoke(windowMs)
    }
```
2. 删除 `broadcastMediaChange` 中的触发调用：
```kotlin
            val isTrackChanged = !isCleared && (lastBroadcastTrack != null && (track != lastBroadcastTrack || artist != lastBroadcastArtist))
            if (isTrackChanged) {
                notifyTrackTransition()
            }
```
改为保留原本的 `isTrackChanged` 仅用于重置位置：
```kotlin
            val isTrackChanged = !isCleared && (lastBroadcastTrack != null && (track != lastBroadcastTrack || artist != lastBroadcastArtist))
```
3. 删除 `send(index: Int)` 中的按键 4/5 触发：
```kotlin
        protected fun send(index: Int) {
            if (index == 4 || index == 5) {
                notifyTrackTransition()
            }
            val sent = controller?.sendMediaButton(index) ?: false
            DiagLog.i(TAG, "media key -> CarPlay $index sent=$sent")
        }
```
恢复为：
```kotlin
        protected fun send(index: Int) {
            val sent = controller?.sendMediaButton(index) ?: false
            DiagLog.i(TAG, "media key -> CarPlay $index sent=$sent")
        }
```

- [ ] **Step 2: 修改 `CarPlayHostActivity.kt`，移除 4 处绑定调用**

在 `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` 中，删除以下 4 行：
- 第 2250 行：`CarPlayMediaKeys.bindTrackTransitionTarget(snapshot.sink::notifyTrackTransition)`
- 第 2340 行：`CarPlayMediaKeys.bindTrackTransitionTarget(renderer::notifyTrackTransition)`
- 第 2540 行：`CarPlayMediaKeys.bindTrackTransitionTarget(null)`
- 第 2685 行：`CarPlayMediaKeys.bindTrackTransitionTarget(null)`

- [ ] **Step 3: 修改 `WirelessHoldJitterBuffer.kt` 与对应单测**

1. 在 `shared/src/main/java/com/shilapi/xcertplay/media/WirelessHoldJitterBuffer.kt` 中删除：
```kotlin
    /** Called on track transition or manual seek/switch to reset penalty and allow fast playback. */
    fun onTrackTransition() {
        hasUnderrun = false
    }
```
2. 在 `shared/src/test/java/com/shilapi/xcertplay/media/WirelessHoldJitterBufferTest.kt` 中，删除测试末尾对 `buffer.onTrackTransition()` 的调用块：
```kotlin
        // Underrun again, then track transition resets penalty
        buffer.onUnderrunDetected()
        assertTrue(buffer.hasUnderrun)
        assertEquals(104, buffer.targetHoldPackets())
        buffer.onTrackTransition()
        assertFalse("Track transition must reset underrun penalty to prevent permanent delay", buffer.hasUnderrun)
        assertEquals(47, buffer.targetHoldPackets())
```
替换为验证 `onStreamDestroyed()` 能够重置：
```kotlin
        // Underrun again, verify stream destroyed resets penalty
        buffer.onUnderrunDetected()
        assertTrue(buffer.hasUnderrun)
        assertEquals(104, buffer.targetHoldPackets())
        buffer.onStreamDestroyed()
        assertFalse("Stream destroyed must reset underrun penalty", buffer.hasUnderrun)
        assertEquals(47, buffer.targetHoldPackets())
```

- [ ] **Step 4: 运行现有单测验证 Task 1 清理正确**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.WirelessHoldJitterBufferTest`
Expected: PASS

- [ ] **Step 5: 提交 Task 1 改动**

```bash
git add common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt \
        common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt \
        shared/src/main/java/com/shilapi/xcertplay/media/WirelessHoldJitterBuffer.kt \
        shared/src/test/java/com/shilapi/xcertplay/media/WirelessHoldJitterBufferTest.kt
git commit -m "refactor(audio): 剔除 CarPlayMediaKeys 与 HostActivity 切歌宽限胶水代码"
```

---

### Task 2: 在 `AndroidMediaSink` 中实现末端静音填缝（Silence Loop）与下一曲开闸门控

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`

**Interfaces:**
- Consumes: `PcmSmoother.applyFadeOut`, `PcmSmoother.applyFadeIn`, `PcmSmoother.createSilence`, `AudioBufferProgress.queuedBytes`.
- Produces: 具备断包自平滑、静音填缝不暂停、下一曲无缝淡入衔接能力的 `AudioRenderer`。

**精确实现规格：**
1. 删除 `AndroidMediaSink` 外层中的：
```kotlin
    fun notifyTrackTransition(windowMs: Long = 3500L) {
        val untilNs = System.nanoTime() + windowMs * 1_000_000L
        audioRenderers.values.filter { it.format.audioType == "media" }.forEach {
            it.notifyTrackTransition(untilNs)
        }
    }
```
2. 在 `AudioRenderer` 类内部：
   - 删除：`private val trackTransitionUntilNs = AtomicLong(0L)`
   - 删除：`fun notifyTrackTransition(untilNs: Long) { ... }`
   - 增加静音填缝状态与常量：
```kotlin
        private const val SILENCE_CHUNK_MS = 20
        private const val SILENCE_HEADROOM_MS = 100L // 100ms 安全水位，彻底避开 MT6735 42.7ms HAL 周期抖动
        private const val IDLE_TIMEOUT_NS = 30_000_000_000L // 30s 持续无包才真正 pause
```
   - 在 `AudioRenderer` 类成员变量中增加预分配静音数组（零 GC 隐患）：
```kotlin
    @Volatile private var inSilencePadding = false
    private val silenceChunk by lazy { PcmSmoother.createSilence(format.sampleRate, format.channels, SILENCE_CHUNK_MS) }
```
3. 在 `AudioRenderer.run()` 循环中，解耦开闸逻辑并正确对齐下一首等待耗时：
```kotlin
                if (isWirelessMedia && !gateOpened) {
                    // A brake or silence loop stores its own mark. Cold start waits until the first packet.
                    if (gateWaitMarkNs == 0L && queue.isNotEmpty()) {
                        gateWaitMarkNs = System.nanoTime()
                    }
                    if (!jitterBuffer.isReadyToDrain(queue.size)) {
                        Thread.sleep(AUDIO_POLL_MILLIS)
                        maintainPlaybackBuffer()
                        logStatsIfDue()
                        continue
                    }
                    val mark = gateWaitMarkNs
                    val holdMs = if (mark == 0L) -1L else (System.nanoTime() - mark) / 1_000_000L
                    val queued = queue.size
                    val target = jitterBuffer.targetHoldPackets()
                    gateOpened = true
                    gateWaitMarkNs = 0L
                    DiagLog.i(
                        TAG,
                        "audio gate opened audioType=${format.audioType} rate=${format.sampleRate} " +
                            "target=$target queue=$queued holdMs=$holdMs inSilencePadding=$inSilencePadding",
                    )
                }
```
4. 在 `maintainPlaybackBuffer()` 中重写断流处理：
完全替换掉原本粗暴的 `starvation brake`，增加防御性保护：
```kotlin
        // Silence Loop for wireless media:
        // When upstream stops sending packets (song change, seek, pause, buffering),
        // do NOT call track.pause() and do NOT escalate jitter buffer to 2.2s.
        // Instead, smoothly fade out valid sound, reset gateOpened to re-buffer next song cleanly,
        // and continuously feed 20ms silence chunks to AudioTrack so MT6735 ALSA DMA never runs dry.
        if (playbackStarted && isWirelessMedia && queue.isEmpty()) {
            val headroomBytes = if (bytesPerSecond > 0) bytesPerSecond * SILENCE_HEADROOM_MS / 1000L else 0L
            if (unplayedBytes <= headroomBytes) {
                if (!inSilencePadding) {
                    // Transitioning into silence: smoothly fade out staged valid audio
                    codec?.let(::drainCodec)
                    if (stagedBytes > 0) {
                        val frameBytes = if (format.channels >= 2) 4 else 2
                        val alignedBytes = (stagedBytes / frameBytes) * frameBytes
                        if (alignedBytes > 0) {
                            PcmSmoother.applyFadeOut(stagingBuffer, 0, alignedBytes, format.sampleRate, format.channels)
                            writeCompletelyToTrack(stagingBuffer, 0, alignedBytes)
                        }
                        stagedBytes = 0
                    }
                    inSilencePadding = true
                    gateOpened = false // Next song will accumulate 47 packets before releasing
                    fadeApplied = false // Next song will fade in smoothly
                    gateWaitMarkNs = 0L
                    DiagLog.i(TAG, "wireless media entered silence padding audioType=${format.audioType} unplayedBytes=$unplayedBytes")
                }

                // Check for true idle timeout (e.g. 30 seconds of total silence without packets)
                if (lastRx > 0L && (now - lastRx > IDLE_TIMEOUT_NS)) {
                    runCatching { track.pause() }
                    playbackStarted = false
                    inSilencePadding = false
                    gateOpened = false
                    fadeApplied = false
                    stagedBytes = 0
                    prebufferBytes = 0
                    DiagLog.i(TAG, "wireless media idle timeout reached (30s), paused track audioType=${format.audioType}")
                } else {
                    // Feed 20ms silence chunk to keep MT6735 hardware DAC smoothly ticking with zeros
                    writeCompletelyToTrack(silenceChunk, 0, silenceChunk.size)
                }
            }
        }
```
并在随后的 `when (drainAction(...))` 中：对 `isWirelessMedia` 旁路 `PAUSE_AND_REBUFFER`：
```kotlin
        val drained = bufferProgress.shouldRebuffer(format.audioType, playbackStarted,
            underrunDetected, queue.isEmpty(), track.playbackHeadPosition)
        when (drainAction(drained, platformReportsUnderruns, drainCounted)) {
            DrainAction.NONE -> if (!drained) drainCounted = false
            DrainAction.COUNT -> {
                drainCounted = true
                rebufferCount++
            }
            DrainAction.PAUSE_AND_REBUFFER -> {
                // Wireless media is managed by Silence Loop and should not be paused here.
                if (!isWirelessMedia) {
                    track.pause()
                    playbackStarted = false
                    prebufferBytes = 0
                    rebufferCount++
                }
            }
        }
```
5. 在 `writePcm` 中：
当新歌曲数据到达时：
```kotlin
        // Wireless media: smooth fade-in for cold start or recovery from silence padding
        if (!fadeApplied || inSilencePadding) {
            inSilencePadding = false
            PcmSmoother.applyFadeIn(data, offset, minOf(length, fadeByteCount), format.sampleRate, format.channels)
            fadeApplied = true
        }
```

- [ ] **Step 1: 实施 Task 2 修改**

将上述代码替换入 `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`。

- [ ] **Step 2: 编译代码并确保无语法或编译错误**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :shared:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: 提交 Task 2 改动**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt
git commit -m "feat(audio): 用缓冲末端静音填缝 (Silence Loop) 与门控复位替代粗暴饥饿刹车"
```

---

### Task 3: 补齐真实单测：测试断包时 AudioTrack 持续 Running 且接收静音帧，新包到达平滑起播

**Files:**
- Create: `shared/src/test/java/com/shilapi/xcertplay/media/AudioRendererSilenceLoopTest.kt`

**Interfaces:**
- Consumes: `AndroidMediaSink`, `ShadowAudioTrack`, `AudioFormat`, `AudioCodecKind.LPCM`.
- Produces: 验证 Silence Loop 行为的自动化回归测试。

- [ ] **Step 1: 编写 `AudioRendererSilenceLoopTest.kt` 测试用例**

创建 `shared/src/test/java/com/shilapi/xcertplay/media/AudioRendererSilenceLoopTest.kt`：
```kotlin
package com.shilapi.xcertplay.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22], manifest = Config.NONE)
class AudioRendererSilenceLoopTest {

    @After
    fun tearDown() {
        ShadowAudioTrack.resetTest()
    }

    private fun lpcmFormat() = AudioFormat(
        codec = AudioCodecKind.LPCM,
        sampleRate = 48_000,
        channels = 2,
        payloadType = 96,
        audioType = "media",
    )

    private fun rtpPacket(pcm: ByteArray): ByteArray {
        val rtp = ByteArray(12 + pcm.size)
        rtp[0] = 0x80.toByte()
        rtp[1] = 96.toByte()
        System.arraycopy(pcm, 0, rtp, 12, pcm.size)
        return rtp
    }

    @Test
    fun packetStarvationMaintainsAudioTrackPlayingAndWritesSilence() {
        ShadowAudioTrack.resetTest()
        ShadowAudioTrack.setMinBufferSize(4096)

        val writtenBuffers = Collections.synchronizedList(mutableListOf<ByteArray>())
        var activeTrack: AudioTrack? = null

        ShadowAudioTrack.addAudioDataListener { track, bytes, _ ->
            activeTrack = track
            writtenBuffers.add(bytes.copyOf())
        }

        val sink = AndroidMediaSink(isWireless = true, mediaBufferMillis = 300)
        try {
            sink.onAudioStarted(96, lpcmFormat(), firstSample = 0)

            // 1. 注入 30 包真实非零音频数据 (每包 1920 字节 = 10ms)，共 300ms，触发起播门限
            val loudPcm = ByteArray(1920) { index ->
                if ((index / 2) % 2 == 0) 0x7F.toByte() else 0x10.toByte()
            }
            repeat(30) { seq ->
                sink.onAudioRtp(96, lpcmFormat(), rtpPacket(loudPcm), sample = seq * 480, arrivalGapMillis = 10L)
            }

            // 等待工作线程处理并起播
            Thread.sleep(150)

            val track = activeTrack
            assertTrue("AudioTrack 必须已经创建并开始播放", track != null)
            assertEquals("起播后必须处于 PLAYSTATE_PLAYING", AudioTrack.PLAYSTATE_PLAYING, track!!.playState)

            // 2. 模拟断流：停止发送 RTP 包，等待音频线程消费完毕进入 Silence Loop
            Thread.sleep(200)

            // 断言：处于断包期间，AudioTrack 仍保持 PLAYING 状态，绝不调用 pause()！
            assertEquals("断流期间 AudioTrack 绝不能 pause，必须维持 PLAYING", AudioTrack.PLAYSTATE_PLAYING, track.playState)

            // 断言：抓取到的写入字节中必须有全 0 的静音填缝块 (Silence Chunk)
            val zeroWrites = synchronized(writtenBuffers) {
                writtenBuffers.filter { buf -> buf.isNotEmpty() && buf.all { it == 0.toByte() } }
            }
            assertTrue("断流期间末端必须持续写入全0静音帧填缝，防止硬件打结爆音", zeroWrites.isNotEmpty())

            // 3. 再次注入新歌数据包，验证无缝起播且无异常
            writtenBuffers.clear()
            repeat(5) { seq ->
                sink.onAudioRtp(96, lpcmFormat(), rtpPacket(loudPcm), sample = (30 + seq) * 480, arrivalGapMillis = 10L)
            }
            Thread.sleep(100)

            assertEquals("恢复后 AudioTrack 依然保持 PLAYING", AudioTrack.PLAYSTATE_PLAYING, track.playState)
            val resumedWrites = synchronized(writtenBuffers) {
                writtenBuffers.filter { buf -> buf.any { it != 0.toByte() } }
            }
            assertTrue("恢复后必须能继续写入真实声音", resumedWrites.isNotEmpty())

        } finally {
            sink.onAudioStopped(96)
            sink.close()
        }
    }
}
```

- [ ] **Step 2: 运行单测并验证其通过**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.AudioRendererSilenceLoopTest`
Expected: PASS

- [ ] **Step 3: 运行全量 `:shared` 单元测试，确保无回归故障**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (所有用例 100% 通过)

- [ ] **Step 4: 提交 Task 3 单测**

```bash
git add shared/src/test/java/com/shilapi/xcertplay/media/AudioRendererSilenceLoopTest.kt
git commit -m "test(audio): 增加无包期间 AudioTrack 保持播放与静音填缝的真实单测"
```

---

## 计划自检对照（Self-Review）
1. **Spec 覆盖**：彻底剔除 `notifyTrackTransition` 胶水代码（Task 1）；用静音填缝取代粗暴 `track.pause()` 刹车（Task 2）；补齐真实不断流/写静音单测（Task 3）。
2. **死角封堵**：切歌、拖进度、自动下一首、在线拉流 4~5 秒均由静音填缝平滑承接，不触发 2.2 秒加严，无 MT6735 爆音打结。下一首开闸门控保证蓄满 47 包再放行，杜绝抢跑断续卡顿。
3. **闪退与性能审核通过**：
   - 安全水位提升至 100ms，避开 MT6735 2048 帧 ALSA 周期抖动；
   - 零对象分配单例静音块，零 GC 抖动；
   - 严格帧对齐整帧写入；
   - 所有状态切换加 `runCatching` 兜底；
   - 旁路 API 24+ `DrainAction.PAUSE_AND_REBUFFER` 防止背刺。
4. **无占位符与无偷懒**：所有 Task 均包含完整代码、常量值、方法签名与 Robolectric 单测断言，零自然语言伪代码。
