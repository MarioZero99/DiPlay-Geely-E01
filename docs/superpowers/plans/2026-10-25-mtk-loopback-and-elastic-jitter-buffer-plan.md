# MTK ALSA 回环根除与弹性抗干扰音频缓冲实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底根除吉利 E01（MTK MT6735, Android 5.1）车机在中间热点 1~2 秒断流下的 ALSA DMA 回环（歌词重复播放与金属爆音），实现「起播 1000ms 秒开 + 水桶容量 2500ms + 遇喷包自动升至 2000ms 深度抗抖 + 车机本地即时导航压音」的高可靠流媒体音频链路。

**Architecture:**
1. **MTK ALSA 防回绕核心（Zero Injection & Pause Rebuffer）**：修复 `drainAction` 在 API 22 上不暂停的历史缺陷。当音频队列抽干时，持续向 `AudioTrack` 注入纯 0 静音（Zero Padding），强制覆盖底层的 ALSA 环形缓冲区脏数据，并在断流确定时显式执行 `track.pause()` 锁住硬件 DMA 指针，彻底杜绝回环与歌词重复。
2. **PCM 平滑淡入淡出（`PcmSmoother`）**：在断流发生与恢复时，分别执行 15ms 余弦平滑淡出与淡入，消灭阶跃电平导致的破音与爆音。
3. **弹性抗干扰缓冲（`ElasticAudioBuffer`）**：起播门限维持在用户期望的 500ms/1000ms（保证秒开），但硬件水桶容量开辟至 2500ms。当中间热点从 1~2 秒休眠中醒来并井喷积压包时，瞬间吞下喷包，常态深度自然充盈至 2000ms，构建持续防抖护盾。切歌时立即 Flush 清空旧歌，无缝重置起播水位。
4. **车机端本地即时压音（`LocalDuckingController`）**：针对 2000ms 缓冲导致的导航压音滞后问题，当车机接收到导航流（`audioType == "speech"`）首包时，直接在车机端将音乐 `AudioTrack` 硬件增益压低至 0.2，导航结束即时恢复，彻底打破 2 秒时差。

**Tech Stack:** Kotlin, JUnit 4, Android 5.1 (API 22) 兼容, ALSA Audio HAL 规范, Gradle `:shared:testDebugUnitTest`.

---

## 计划依据（全部来自实车日志与源码事实）

### 1. 实车运行日志证据（Scratch Logs）
扫视日志文件：`scratch_logs/DiPlay-20261020-092330-826.txt`、`scratch_logs/DiPlay-20261022-180352-504.txt`（吉利 E01 / alps E01，Android 5.1）：

| 事实与现象 | 日志原文 / 历史记录 | 根本技术机理 |
|---|---|---|
| **常态推流包率** | `Receive: audio ... readMaxMs=395...405`（稳态每 5 秒收 234 包） | iPhone 严格以 **21.4ms 一包（1.0x 真实倍速）** 推送 RTP，绝不提前加速下发未来音频。 |
| **恶性网络断流** | `09:28:30.679 ... seqForwardGaps=36 ... readMaxMs=1143 interArrivalMaxMs=1144` | 中间热点与 2.4G 空口出现整机网络线程假死，单次停摆高达 **1144ms**。 |
| **重启播从未生效** | `rebuffers=0`（日志中数百行 stats 统计，欠载重缓冲次数永远为 0） | 现存代码在 Android 5.1 上从未触发过真正的 Rebuffer，任由底层裸奔。 |
| **AudioTrack 阻塞** | `v0.4.5-geely.md:28` 记录 `maxWriteMs` 飙升至 637ms | 传统方案中直接把硬件 `AudioTrack` 设大，导致阻塞写入，进一步加剧断流。 |

### 2. 源码致命缺陷证据（Source Code Analysis）

#### 证据 A：`AudioBufferProgress.kt` 第 32-36 行
```kotlin
internal fun drainAction(drained: Boolean, platformReportsUnderruns: Boolean, alreadyCounted: Boolean): DrainAction {
    if (!drained) return DrainAction.NONE
    if (!platformReportsUnderruns) return if (alreadyCounted) DrainAction.NONE else DrainAction.COUNT
    return DrainAction.PAUSE_AND_REBUFFER
}
```
* **缺陷**：在吉利 E01（Android 5.1 / API 22）上，`platformReportsUnderruns = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)` 必然是 **`false`**！
* **致命后果**：当音乐缓冲被抽干（`drained == true`）时，代码只返回了 `DrainAction.COUNT`，**从未执行 `DrainAction.PAUSE_AND_REBUFFER`**！
* `AudioTrack` 保持在 `PLAYING` 状态，Java 无法写入新数据，MTK ALSA 驱动的 DMA 硬件读取指针在环形缓冲区空转，重新读取上一圈未清空的残留内存，**导致歌词重复回环**。

#### 证据 B：`AndroidMediaSink.kt` 第 1050-1076 行
```kotlin
while (written < length && running) {
    val count = track.write(data, offset + written, writeLength)
    ...
}
```
* **缺陷**：当外部网络断流、没有新数据传入时，`AudioTrack.write()` 停止调用。
* **致命后果**：没有静音填充（Zero Injection）。MTK 驱动在无数据可写时不会主动填充静音，硬件指针继续循环消费脏内存。

#### 证据 C：`MediaAudioBuffer.kt` 第 38 行
```kotlin
val capacity = (bytesPerSecond * (sanitize(mediaMillis) + HEADROOM_MILLIS) / 1000).toInt()
```
* **缺陷**：缓冲总容量直接绑死在 `起播水位 + 200ms`。
* **致命后果**：当设置 500ms 时容量仅 700ms，中间热点醒来喷出的 1~2 秒积压包无法被完全容纳。

---

## 全局约束

- **严守 Android 5.1 / API 22 兼容性**：不使用 API 23+ 的 `WRITE_NON_BLOCKING`，不使用 API 24+ 的 `AudioTrack.getUnderrunCount()`。
- **严格隔离音轨**：所有缓冲扩大与平滑只针对 `audioType == "media"`，通话、Siri、导航提示（`speech`/`alert`）必须保持 10~20ms 极低延迟通道。
- **纯 Kotlin TDD**：核心算法与状态机必须解耦为纯 Kotlin 组件，单测通过后方可接线。
- **性能与轻量**：禁止在音频循环中创建高频临时对象，避免 GC 引起的卡顿。

---

## 文件结构与改动清单

| 文件 | 动作 | 职责 |
|---|---|---|
| `shared/src/main/java/com/shilapi/xcertplay/media/AudioBufferProgress.kt` | 修改 | 修复 `drainAction` 在 API 22 上的判定，明确区分抽干动作 |
| `shared/src/test/java/com/shilapi/xcertplay/media/AudioBufferProgressTest.kt` | 修改 | 更新 API 22 抽干逻辑单元测试 |
| `shared/src/main/java/com/shilapi/xcertplay/media/PcmSmoother.kt` | 新建 | PCM 16-bit 快速余弦淡入与淡出平滑器（纯 Kotlin） |
| `shared/src/test/java/com/shilapi/xcertplay/media/PcmSmootherTest.kt` | 新建 | 淡入淡出曲线与边界单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/ElasticAudioBuffer.kt` | 新建 | 弹性双级缓冲状态机（起播 500/1000ms，容量 2500ms，吞喷包，瞬态 Flush） |
| `shared/src/test/java/com/shilapi/xcertplay/media/ElasticAudioBufferTest.kt` | 新建 | 弹性缓冲与喷包吞吐单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/LocalDuckingController.kt` | 新建 | 车机端即时导航压音控制器（拦截 speech 流即刻压音） |
| `shared/src/test/java/com/shilapi/xcertplay/media/LocalDuckingControllerTest.kt` | 新建 | 即时压音与恢复单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt` | 修改 | 接入静音注入（Zero Injection）、PcmSmoother、ElasticAudioBuffer 与 LocalDucking |

---

### Task 1: 修复 `AudioBufferProgress` 抽干判定，破除 API 22 禁令

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AudioBufferProgress.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/AudioBufferProgressTest.kt`

**Interfaces:**
- Produces: `drainAction(drained: Boolean, platformReportsUnderruns: Boolean, alreadyCounted: Boolean): DrainAction`
  - 当 `drained == true` 时，无论是否 `platformReportsUnderruns`，首次抽干均必须返回 `DrainAction.PAUSE_AND_REBUFFER`，允许上层主动执行暂停与重填，打破导致 MTK 空转的根因。

- [ ] **Step 1: 编写失败测试**
在 `shared/src/test/java/com/shilapi/xcertplay/media/AudioBufferProgressTest.kt` 中修改测试用例：
```kotlin
@Test
fun `api 22 without underrun counter still triggers pause and rebuffer on drain`() {
    // 之前 API 22 会回退到 COUNT，导致底层不断流空转。修复后必须触发 PAUSE_AND_REBUFFER
    assertEquals(
        DrainAction.PAUSE_AND_REBUFFER,
        drainAction(drained = true, platformReportsUnderruns = false, alreadyCounted = false)
    )
}
```

- [ ] **Step 2: 运行单测确认红灯**
```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.AudioBufferProgressTest
```

- [ ] **Step 3: 实现修改**
在 `shared/src/main/java/com/shilapi/xcertplay/media/AudioBufferProgress.kt` 中修改：
```kotlin
internal fun drainAction(drained: Boolean, platformReportsUnderruns: Boolean, alreadyCounted: Boolean): DrainAction {
    if (!drained) return DrainAction.NONE
    if (alreadyCounted) return DrainAction.NONE
    return DrainAction.PAUSE_AND_REBUFFER
}
```

- [ ] **Step 4: 运行单测确认绿灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.AudioBufferProgressTest
```

- [ ] **Step 5: 提交代码**
```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/AudioBufferProgress.kt shared/src/test/java/com/shilapi/xcertplay/media/AudioBufferProgressTest.kt
git commit -m "audio: enable PAUSE_AND_REBUFFER on API 22 to prevent MTK ALSA empty spin"
```

---

### Task 2: 实现 PCM 平滑淡入淡出组件（`PcmSmoother`）

**Files:**
- Create: `shared/src/main/java/com/shilapi/xcertplay/media/PcmSmoother.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/PcmSmootherTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class PcmSmoother(val sampleRate: Int = 48000, val channels: Int = 2, val fadeMillis: Int = 15) {
      fun applyFadeIn(pcm: ByteArray, offset: Int, length: Int)
      fun applyFadeOut(pcm: ByteArray, offset: Int, length: Int)
      fun generateSilence(durationMs: Int): ByteArray
  }
  ```

- [ ] **Step 1: 编写失败测试**
在 `shared/src/test/java/com/shilapi/xcertplay/media/PcmSmootherTest.kt` 中测试淡入淡出的数学缩放与纯 0 静音生成：
```kotlin
package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class PcmSmootherTest {
    @Test
    fun `fade in scales amplitude from zero to unity across channels`() {
        val smoother = PcmSmoother(48000, 2, fadeMillis = 1) // 48 samples * 4 bytes = 192 bytes
        val buffer = ByteArray(192) { 0x7F } // non-zero samples
        smoother.applyFadeIn(buffer, 0, buffer.size)
        // First sample (stereo 16-bit) should be scaled down near 0
        val firstSampleLeft = (buffer[0].toInt() and 0xFF) or (buffer[1].toInt() shl 8)
        assertEquals(0, firstSampleLeft)
    }

    @Test
    fun `generate silence produces zero filled array`() {
        val smoother = PcmSmoother(48000, 2)
        val silence = smoother.generateSilence(20) // 20ms
        assertEquals(48 * 20 * 4, silence.size)
        assertTrue(silence.all { it == 0.toByte() })
    }
}
```

- [ ] **Step 2: 运行单测确认红灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.PcmSmootherTest
```

- [ ] **Step 3: 实现 `PcmSmoother.kt`**
按照 16-bit PCM 立体声帧结构，使用余弦平滑因子实施无内存分配的原地就地缩放与静音生成。

- [ ] **Step 4: 运行单测确认绿灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.PcmSmootherTest
```

- [ ] **Step 5: 提交代码**
```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/PcmSmoother.kt shared/src/test/java/com/shilapi/xcertplay/media/PcmSmootherTest.kt
git commit -m "audio: implement PcmSmoother for pop-free fade in fade out and silence generation"
```

---

### Task 3: 实现弹性水桶缓冲区模型（`ElasticAudioBuffer`）

**Files:**
- Create: `shared/src/main/java/com/shilapi/xcertplay/media/ElasticAudioBuffer.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/ElasticAudioBufferTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class ElasticAudioBuffer(
      val sampleRate: Int = 48000,
      val channels: Int = 2,
      val initialStartMs: Int = 1000,
      val maxCapacityMs: Int = 2500,
      val expandedTargetMs: Int = 2000
  ) {
      val initialStartBytes: Int
      val capacityBytes: Int
      var currentTargetMs: Int = initialStartMs
          private set

      fun observeBurst(burstBytes: Int)
      fun onUnderrun()
      fun onTrackSwitch(): Int
  }
  ```

- [ ] **Step 1: 编写失败测试**
覆盖“秒开起播”、“突发喷包后自动提档至 2000ms”、“切歌瞬间清空重置回 1000ms”状态机逻辑。

- [ ] **Step 2: 运行单测确认红灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.ElasticAudioBufferTest
```

- [ ] **Step 3: 实现 `ElasticAudioBuffer.kt`**

- [ ] **Step 4: 运行单测确认绿灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.ElasticAudioBufferTest
```

- [ ] **Step 5: 提交代码**
```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/ElasticAudioBuffer.kt shared/src/test/java/com/shilapi/xcertplay/media/ElasticAudioBufferTest.kt
git commit -m "audio: implement ElasticAudioBuffer with burst absorption and instant track-switch flush"
```

---

### Task 4: 实现车机本地即时导航压音控制器（`LocalDuckingController`）

**Files:**
- Create: `shared/src/main/java/com/shilapi/xcertplay/media/LocalDuckingController.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/LocalDuckingControllerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class LocalDuckingController(
      val duckedGain: Float = 0.2f,
      val normalGain: Float = 1.0f,
      val onGainChanged: (Float) -> Unit
  ) {
      fun onNavigationStreamActive(active: Boolean)
  }
  ```

- [ ] **Step 1: 编写失败测试**
验证当导航音频流被通知激活时，立即回调 `0.2f`；当导航流注销时，立即回调 `1.0f`。

- [ ] **Step 2: 运行单测确认红灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.LocalDuckingControllerTest
```

- [ ] **Step 3: 实现 `LocalDuckingController.kt`**

- [ ] **Step 4: 运行单测确认绿灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.LocalDuckingControllerTest
```

- [ ] **Step 5: 提交代码**
```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/LocalDuckingController.kt shared/src/test/java/com/shilapi/xcertplay/media/LocalDuckingControllerTest.kt
git commit -m "audio: implement LocalDuckingController for instant navigation ducking"
```

---

### Task 5: `AndroidMediaSink` 全链路接线与静音注入（Zero Injection）

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/airplay/AudioStream.kt`

**Key Wiring Steps:**
1. **扩大 UDP 接收套接字**：在 `AudioStream` 绑定端口时，增加 `socket.receiveBufferSize = 512 * 1024`（512KB），确保中间热点醒来时的井喷数据包不被内核丢弃。
2. **静音注入（Zero Injection 防回绕核心）**：在 `maintainPlaybackBuffer` 检测到 `drained == true` 时：
   - 执行 `PcmSmoother.applyFadeOut`；
   - 持续写入 `PcmSmoother.generateSilence(20)`，向底层 ALSA 驱动冲刷写入纯 0，强制抹平所有上一圈残留的脏内存；
   - 执行 `track.pause()` 锁住底层硬件 DMA。
3. **喷包吞吐与水桶充盈**：接入 `ElasticAudioBuffer`，起播门限按 1000ms 起算，容量预留 2500ms，热点喷包时顺畅吃进，常态深度自然充盈至 2000ms。
4. **即时压音接线**：当 `onAudioStarted` 收到 `format.audioType == "speech"` 时，调用 `localDucking.onNavigationStreamActive(true)`；在 `onAudioStopped` 时调用 `false`。
5. **切歌即刻清空（Flush）**：当媒体音频流重启或切换时，清空积压队列，重置 `ElasticAudioBuffer` 起播门限为 1000ms。

- [ ] **Step 1: 编写接线测试用例**
- [ ] **Step 2: 运行测试验证**
- [ ] **Step 3: 实施接线修改**
- [ ] **Step 4: 运行全量单元测试**
```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely
./gradlew :shared:testDebugUnitTest
```
- [ ] **Step 5: 提交代码**
```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt shared/src/main/java/com/shilapi/xcertplay/airplay/AudioStream.kt
git commit -m "audio: wire zero-injection, elastic buffer and local ducking into AndroidMediaSink"
```

---

## 验证与验收标准

1. **MTK 回环阻断验收**：在实车上模拟或回放断流，当音频抽干时，喇叭只有自然柔和的静音，**绝对不出现上一句歌词的重播**。
2. **切歌体感验收**：切歌响应在 1.0 秒内开嗓，切歌瞬间旧歌声音戛然而止（零拖尾）。
3. **抗抖验收**：中间热点在 1.5 秒以内的偶发网络停顿，在首次喷包充盈后，后续播放完全平稳无断音。
4. **导航压音验收**：高德/百度地图语音播报发声的第 1 毫秒，音乐音量即刻压低，语音结束即刻平滑回升。
