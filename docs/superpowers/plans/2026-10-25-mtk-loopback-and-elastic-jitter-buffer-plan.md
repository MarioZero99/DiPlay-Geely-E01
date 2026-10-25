# 无线 CarPlay 音乐：中间热点断流防护与 MTK ALSA 回环根除计划（金标实车终极落盘版）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 针对吉利 E01 车机（MTK MT6735, Android 5.1 / API 22）在无线中间热点 1~2 秒休眠断流下的音频问题，实现：
1. **无线音乐放行门限锁存机制（Gate Latch）**：冷启动/流重置时队列等满 47 包（44.1kHz 为 44 包）开闸，欠载后等满 104 包（44.1kHz 为 95 包）开闸。开闸后设置 `gateOpened = true`，平时的写入仍走累加 `prebufferBytes` 的标准循环；
2. **开闸后排空兜底起播（Gate-Opened Drain Playback）**：无线音乐开闸后（`gateOpened == true`），若写入达到门限立即 `play()`；若因 15ms 暂存扣留导致 47 包（约 988ms）距 1000ms 门限微小差额，只要 `queue.isEmpty()` 且解码器排空、`prebufferBytes > 0`，立即开声；**未开闸前（`gateOpened == false`）绝对不走此兜底，严防未蓄满被抢跑**；
3. **总库存约 2219ms 由「Java 压缩队列包数 + 现有声卡容量（500~1200ms）」联合构成**，放行后写入声卡的部分自然从队列移出，声卡写满 500~1200ms 后自然按 1.0x 阻塞限速，队列中平稳垫底剩余包数；
4. **15ms 真实声音暂存与余弦淡入淡出严格仅对 `isWireless && audioType == "media"` 生效**（非无线媒体流保持既有整段写入与既有生命周期，绝不扣押导航短提示与电话尾音）；
5. **无线音乐 `writePcm` 写入互斥分离**：仅写入「上一块暂存」与「新块截掉末尾 15ms 的主体」，末尾 15ms 留存暂存，绝不同时走既有全量写入（杜绝尾部重复播放两遍）；
6. **收包时间戳仅由 `submit()` 记录原子值，库存快照仅在收包时间戳变化时改写一次，单线程判定**，严防快照缩水与提前误刹车；
7. **刹车直写声卡通过循环写全（`writeCompletelyToTrack`）确保字节写完再 `pause()`**（仅用于刹车时的淡出与 40ms 静音，绝不用于平时写入）；刹车后清零 `playbackStarted = false`、`gateOpened = false`、`prebufferBytes = 0`、`stagedBytes = 0`；
8. **`isWireless` 明确传入 `AudioRenderer` 独立类构造函数**；
9. **导航流（`alert`/`default`/`compatibility`）触发时，仅通过 `applyMediaGain` 对已经在播的 `media` 轨结合 `minOf` 压至 0.2，导航轨自身绝不调音，保持 1.0 原音，双重压音不衰减至 0.04**；
10. **`hasUnderrun` 只在 `release()` 流销毁时清零，同一条流内的切歌继续保持 2200ms 深度，严禁曲目变化时清空队列切掉新歌开头；有线 USB 严格通过 `wirelessEnabled` 识别并完全旁路零侵入**。

---

## 终极闭环关键技术事实（开闸后排空兜底与平稳起播）

### 1. 47 包暂存扣留与排空兜底起播（Gate-Opened Drain Playback）
* **微观数学**：
  * 48kHz 下 47 包 $= 47 \times 1024 / 48000 \times 1000 \approx 1002.7\text{ms}$；
  * 暂存扣住最后 15ms 后，实际写进声卡的约为 **987.7ms**（约 189,631 字节）；
  * 用户若选择 1000ms 档位，起播门限是 192,000 字节，这微小的 2369 字节（约 12ms）差额会导致 `prebufferBytes >= startThresholdBytes` 差一点无法直接触发；
  * 若直接屏蔽尾包抢跑，这 988ms 就会停在暂停的声卡中一直干等；
* **修复**：
  * **平时写入**：必须走现有标准循环，累加 `prebufferBytes += count`，满 `startThresholdBytes` 立即 `startPlayback(track)`；
  * **开闸排空兜底**：在 `maintainPlaybackBuffer()` 中：
    ```kotlin
    if (isWirelessMedia) {
        // 必须已经开闸（47包已蓄满），且队列已空、声卡有有效数据，即使差几毫秒未达门限也立刻开嗓！
        if (!playbackStarted && gateOpened && queue.isEmpty() && prebufferBytes > 0) {
            startPlayback(track)
        }
    } else {
        // 现有逻辑（非无线音乐）：
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }
    ```
  * **安全性**：在等待开闸期间（`gateOpened == false`），此分支绝对不进，47 包没攒够前绝不会被抢跑拉起；开闸后且队列排空时触发，988ms 实打实出声，丝滑顺畅。
  * `writeCompletelyToTrack` 严格仅供刹车两笔写入使用，绝不介入平时的 `writePcm`。

---

## 模块拆解与改动清单

| 模块 / 文件 | 动作 | 核心职责 |
|---|---|---|
| `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` | 修改 | 在 `createMediaSink` 中将 `wirelessEnabled` 透传给 `AndroidMediaSink` |
| `shared/src/main/java/com/shilapi/xcertplay/media/PcmSmoother.kt` | 新建 | PCM 16-bit 严格整帧对齐、同帧同系数、符号位安全的余弦淡出/淡入与动态整帧静音生成 |
| `shared/src/test/java/com/shilapi/xcertplay/media/PcmSmootherTest.kt` | 新建 | 48kHz/44.1kHz 整帧对齐、有效长度 `stagedBytes` 边界淡出、立体声同系数单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/WirelessHoldJitterBuffer.kt` | 新建 | 基于采样率的包数放行控制器：有线/非media为0包，冷启动 47/44 包，欠载后 104/95 包 |
| `shared/src/test/java/com/shilapi/xcertplay/media/WirelessHoldJitterBufferTest.kt` | 新建 | 采样率换算、冷启动 47 包、欠载 104 包、非 media 旁路单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/CompositeMediaGain.kt` | 新建 | 导航流集合跟踪与 `minOf` 增益合成器 |
| `shared/src/test/java/com/shilapi/xcertplay/media/CompositeMediaGainTest.kt` | 新建 | 多流重叠计数、焦点 0 保持 0、0.2 防相乘单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt` | 修改 | 接线：透传 wirelessEnabled；门槛开闸锁存（gateOpened）；平时标准写入累加 prebufferBytes；开闸排空兜底起播；writePcm 仅对无线音乐分支暂存；刹车写满直写（writeCompletelyToTrack）；仅对 media 广播压音 |

---

### Task 1: 实现 `PcmSmoother`（同帧同系数、符号位安全淡入淡出与有效长度边界）

**Files:**
- Create: `shared/src/main/java/com/shilapi/xcertplay/media/PcmSmoother.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/PcmSmootherTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object PcmSmoother {
      const val FADE_MS = 15

      fun fadeFrameCount(sampleRate: Int): Int = sampleRate * FADE_MS / 1000

      fun fadeByteCount(sampleRate: Int, channels: Int): Int =
          fadeFrameCount(sampleRate) * (if (channels >= 2) 4 else 2)

      /** 仅对 offset 到 offset + validLength 的有效整帧实施余弦淡入，同帧左右声道用同一系数 */
      fun applyFadeIn(pcm: ByteArray, offset: Int, validLength: Int, sampleRate: Int, channels: Int)

      /** 仅对 offset 到 offset + validLength 的有效整帧实施余弦淡出，同帧左右声道用同一系数 */
      fun applyFadeOut(pcm: ByteArray, offset: Int, validLength: Int, sampleRate: Int, channels: Int)

      /** 生成按整帧严格对齐的静音字节块 */
      fun createSilence(sampleRate: Int, channels: Int, durationMs: Int = 40): ByteArray
  }
  ```

- [ ] **Step 1: 编写失败测试**
验证立体声下左声道和右声道在同一帧内缩放系数完全一致；验证 `validLength < fadeByteCount` 时的边界安全，确保不修改 `validLength` 之后的字节；测试 16-bit 负数极值（-32768, -100）缩放无翻转。

- [ ] **Step 2: 运行测试确认红灯**
```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.PcmSmootherTest
```

- [ ] **Step 3: 实现 `PcmSmoother.kt`**

- [ ] **Step 4: 运行测试确认绿灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.PcmSmootherTest
```

- [ ] **Step 5: 提交代码**
```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/PcmSmoother.kt shared/src/test/java/com/shilapi/xcertplay/media/PcmSmootherTest.kt
git commit -m "audio: implement PcmSmoother with stereo coherence and validLength boundaries"
```

---

### Task 2: 实现 `WirelessHoldJitterBuffer`（包数蓄水放行控制器）

**Files:**
- Create: `shared/src/main/java/com/shilapi/xcertplay/media/WirelessHoldJitterBuffer.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/WirelessHoldJitterBufferTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class WirelessHoldJitterBuffer(
      val isWireless: Boolean,
      val audioType: String,
      val sampleRate: Int = 48000,
      val initialHoldMs: Int = 1000,
      val escalatedHoldMs: Int = 2200,
  ) {
      var hasUnderrun: Boolean = false
          private set

      fun targetHoldPackets(): Int {
          if (!isWireless || audioType != "media") return 0
          val ms = if (hasUnderrun) escalatedHoldMs else initialHoldMs
          val packetsPerSec = sampleRate.toDouble() / 1024.0
          return Math.ceil(ms * packetsPerSec / 1000.0).toInt() // 48kHz: 47包 / 104包; 44.1kHz: 44包 / 95包
      }

      fun onUnderrunDetected() {
          if (isWireless && audioType == "media") hasUnderrun = true
      }

      fun onStreamDestroyed() {
          hasUnderrun = false
      }

      fun isReadyToDrain(currentQueuePackets: Int): Boolean =
          currentQueuePackets >= targetHoldPackets()
  }
  ```

- [ ] **Step 1: 编写失败测试**
覆盖：未欠载时 48kHz 为 47 包、欠载后为 104 包；44.1kHz 未欠载 44 包、欠载后 95 包；非 media 流为 0 包；有线模式为 0 包。

- [ ] **Step 2: 运行测试确认红灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.WirelessHoldJitterBufferTest
```

- [ ] **Step 3: 实现 `WirelessHoldJitterBuffer.kt`**

- [ ] **Step 4: 运行测试确认绿灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.WirelessHoldJitterBufferTest
```

- [ ] **Step 5: 提交代码**
```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/WirelessHoldJitterBuffer.kt shared/src/test/java/com/shilapi/xcertplay/media/WirelessHoldJitterBufferTest.kt
git commit -m "audio: implement WirelessHoldJitterBuffer with cold-start 47 and escalated 104 packets"
```

---

### Task 3: 实现 `CompositeMediaGain`（仅限媒体流的 minOf 压音合成器）

**Files:**
- Create: `shared/src/main/java/com/shilapi/xcertplay/media/CompositeMediaGain.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/CompositeMediaGainTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class CompositeMediaGain(val duckFactor: Float = 0.2f) {
      private val activeNavigationStreams = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
      private var externalFocusGain = 1.0f

      @Synchronized
      fun setFocusGain(gain: Float): Float {
          externalFocusGain = gain.coerceIn(0f, 1f)
          return effectiveGain()
      }

      @Synchronized
      fun onStreamStarted(type: Int, isNavigation: Boolean): Float {
          if (isNavigation) activeNavigationStreams.add(type)
          return effectiveGain()
      }

      @Synchronized
      fun onStreamStopped(type: Int): Float {
          activeNavigationStreams.remove(type)
          return effectiveGain()
      }

      fun effectiveGain(): Float {
          if (externalFocusGain == 0f) return 0f
          val duck = if (activeNavigationStreams.isNotEmpty()) duckFactor else 1.0f
          return minOf(externalFocusGain, duck)
      }
  }
  ```

- [ ] **Step 1: 编写失败测试**
覆盖：导航集合跟踪、先停一条不回弹、焦点为 0 保持 0、0.2 防相乘。

- [ ] **Step 2: 运行测试确认红灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.CompositeMediaGainTest
```

- [ ] **Step 3: 实现 `CompositeMediaGain.kt`**

- [ ] **Step 4: 运行测试确认绿灯**
```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.CompositeMediaGainTest
```

- [ ] **Step 5: 提交代码**
```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/CompositeMediaGain.kt shared/src/test/java/com/shilapi/xcertplay/media/CompositeMediaGainTest.kt
git commit -m "audio: implement CompositeMediaGain for navigation ducking"
```

---

### Task 4: `CarPlayHostActivity` 与 `AndroidMediaSink` 全链路接线与验证

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`

**Wiring Implementation Details:**

1. **`CarPlayHostActivity.kt` 透传 `wirelessEnabled`**：
   在 `createMediaSink` 中增加并传递：
   ```kotlin
   isWireless = wirelessEnabled
   ```
   并在 `AndroidMediaSink` 主构造函数增加 `private val isWireless: Boolean = false`。

2. **`AudioRenderer` 构造接收 `isWireless`**：
   修改 `AudioRenderer` 类构造，接收 `private val isWireless: Boolean`。在 `AndroidMediaSink.audioRenderer()` 创建实例时传入 `this.isWireless`。

3. **无线音乐门槛放行锁存（Gate Latch）**：
   在 `AudioRenderer` 中维护 `private var gateOpened = false`。
   在 `AudioRenderer.run()` 循环顶部：
   ```kotlin
   val isWirelessMedia = isWireless && format.audioType == "media"
   if (isWirelessMedia && !playbackStarted && !gateOpened) {
       if (!jitterBuffer.isReadyToDrain(queue.size)) {
           Thread.sleep(AUDIO_POLL_MILLIS)
           continue
       }
       gateOpened = true // 达到 47 包（欠载后 104 包）开闸锁存！
   }
   ```
   开闸后正常 `queue.poll()` -> 解码 -> `writePcm`。平时的写入必须走标准循环并累加 `prebufferBytes += count`，满 `startThresholdBytes` 立即 `startPlayback(track)`。

4. **开闸后排空兜底起播与有线尾包保护**：
   在 `maintainPlaybackBuffer()` 中修改行 1110-1113：
   ```kotlin
   if (isWirelessMedia) {
       // 无线音乐：必须已开闸（47/104包蓄满），队列已空且声卡有声音，即使差十几毫秒也兜底起播！
       if (!playbackStarted && gateOpened && queue.isEmpty() && prebufferBytes > 0) {
           startPlayback(track)
       }
   } else {
       // 非无线音乐（有线/提示音）：保留既有 500ms 尾包超时抢跑
       if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
           System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
           startPlayback(track)
       }
   }
   ```

5. **`writePcm` 分支隔离写入与 15ms 暂存**：
   在 `AudioRenderer` 中预分配 `stagingBuffer = ByteArray(PcmSmoother.fadeByteCount(format.sampleRate, format.channels))`，维护 `var stagedBytes = 0`。
   修改 `writePcm(data, offset, length)`：
   * **分支 A（非无线音乐）**：`!isWirelessMedia`，直接执行原有的标准写循环，累加 `prebufferBytes`，零暂存；
   * **分支 B（无线音乐）**：`isWirelessMedia`：
     * 若 `!fadeApplied`：调用 `PcmSmoother.applyFadeIn(data, offset, minOf(length, PcmSmoother.fadeByteCount(...)), ...)`，置 `fadeApplied = true`；
     * 若 `stagedBytes > 0`：先将 `stagingBuffer[0 until stagedBytes]` 调用标准循环写入 `track`（仅通过循环内部累加实际写入的 `count` 到 `written` 与 `prebufferBytes`）；
     * 拆分新数据：
       * 若 $L \le \text{fadeBytes}$：整帧拷入 `stagingBuffer`，`stagedBytes = L`；
       * 若 $L > \text{fadeBytes}$：前 $L - \text{fadeBytes}$ 走标准循环写入 `track`（累加 written 与 prebufferBytes），最后 `fadeBytes` 拷入 `stagingBuffer`，`stagedBytes = fadeBytes`。
     * 若 `prebufferBytes >= startThresholdBytes`：执行 `startPlayback(track)`。

6. **实现 `writeCompletelyToTrack`（专用于刹车直写）**：
   ```kotlin
   private fun writeCompletelyToTrack(data: ByteArray, offset: Int, length: Int) {
       var written = 0
       while (written < length && running) {
           val count = track?.write(data, offset + written, length - written) ?: break
           if (count <= 0) break
           written += count
           bufferProgress.written(count)
       }
   }
   ```

7. **单线程无竞争快照与刹车时序**：
   * `submit()` 仅通过 `lastArrivalNs.set(now)` 记录原子时间戳；
   * 在 `maintainPlaybackBuffer()` 中维护 `snapshotLastRx` 与 `snapshotBufferedMs`，**仅当 `lastRx != snapshotLastRx` 时更新快照**；
   * 当 `playbackStarted && isWirelessMedia && lastRx > 0L && (now - lastRx > (snapshotBufferedMs + 50L) * 1_000_000L)` 触发断流刹车：
     1. 调用 `codec?.let(::drainCodec)` 排空解码器；
     2. 若 `stagedBytes > 0`：调用 `PcmSmoother.applyFadeOut(stagingBuffer, 0, stagedBytes, ...)`，并通过 `writeCompletelyToTrack(stagingBuffer, 0, stagedBytes)` 完整写进声卡；
     3. 生成整帧静音 `val silence = PcmSmoother.createSilence(format.sampleRate, format.channels, 40)`，通过 `writeCompletelyToTrack(silence, 0, silence.size)` 完整写进声卡；
     4. 立即调用 `track.pause()` 叫停底层 DMA 硬件；
     5. **核心清零：`playbackStarted = false`、`gateOpened = false`、`fadeApplied = false`、`stagedBytes = 0`、`prebufferBytes = 0`**；
     6. 调用 `jitterBuffer.onUnderrunDetected()`（升档至 104 包）；
     7. **严禁调用 `track.flush()`**。

8. **仅对媒体流执行即时压音广播**：
   * 识别导航流：`AudioChannelMapper.map(..).channel == AudioChannel.NAVIGATION`；
   * 导航流开始调用 `compositeGain.onStreamStarted(type, isNav)`，结束调用 `onStreamStopped(type)`；
   * **仅对 `media` 音频轨广播**：
     `audioRenderers.values.filter { it.format.audioType == "media" }.forEach { it.applyMediaGain(effectiveGain) }`；导航流自身音轨不调音，保持 1.0；
   * 焦点变化接入 `compositeGain.setFocusGain(gain)` 并广播媒体轨。

9. **保留流销毁时的 flush**：
   在 `AudioRenderer.release()` 中保留既有的 `track.flush()` 和 `jitterBuffer.onStreamDestroyed()`。

- [ ] **Step 1: 编写接线测试用例**
- [ ] **Step 2: 修改 `CarPlayHostActivity.kt` 与 `AndroidMediaSink.kt`**
- [ ] **Step 3: 运行全量单元测试确认全部通过**
```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely
./gradlew :shared:testDebugUnitTest
```
- [ ] **Step 4: 提交代码**
```bash
git add common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt
git commit -m "audio: wire rock-solid wireless hold jitter buffer, starvation brake, and composite ducking"
```

---

## 终态实车行为验收

1. **开声平滑无阶跃**：预缓冲首段直接余弦淡入，同帧左右声道系数绝对一致，**首毫秒绝对无电平跳变杂音**。
2. **放行锁存与排空兜底起播无死角**：达到 47 包（约 1003ms）开闸锁存，扣留 15ms 后写进声卡约 988ms，队列排空瞬间立即开嗓，**绝不单包卡死，绝不差 12ms 哑音**。
3. **MTK 回环彻底消灭**：断流时，单线程墙上时钟精准等声音放完，排空解码器 -> 循环写满淡出有效字节 -> 循环写满 40ms 整帧静音 -> 立即 pause，**绝无歌词重复，绝无破音**。
4. **流隔离绝对安全**：15ms 暂存与扣包只作用于无线音乐，电话、Siri、导航短提示 100% 保持既有整段写入与低延迟直通，绝不扣押尾音。
5. **导航即时压音**：导航开口瞬间音乐立刻降至 0.2，播报结束即刻恢复，导航音轨保持 1.0 原音，彻底解决 2 秒长延迟下的压音滞后与音量衰竭。
6. **有线 USB 模式零影响**：通过 `wirelessEnabled` 准确隔离，有线模式完全旁路扣包状态机，继续走既有链路，保证原汁原味低延迟。
