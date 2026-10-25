# 无线 CarPlay 音乐：中间热点断流防护与 MTK ALSA 回环根除计划（严密落地终版）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 针对吉利 E01 车机（MTK MT6735, Android 5.1 / API 22）在无线中间热点 1~2 秒休眠断流下的音频问题，实现：
1. **无线音乐 1000ms 起播，欠载后在 Java 压缩队列扣包至 2200ms**（利用声卡 500~700ms 天然阻塞机制将纵深常驻在压缩队列中，声卡容量不盲目扩大，严防写死死锁）；
2. **最后一包到达时刻快照 + 墙上时钟精准判定断流**（严防半路提前刹车导致旧声音残留）；
3. **排空解码器 + 15ms 暂存真实声音淡出 + 40ms 整帧静音冲刷 + 立即 `track.pause()` 刹车**（绝不调用 `flush()`，但在流彻底销毁 `release()` 时保留 `flush()`）；
4. **针对无线音乐单独屏蔽 500ms 尾包抢跑**；
5. **基于 `AudioChannel.NAVIGATION` 与计数器的本地即时压音（取 `minOf` 杜绝 0.04 极小音量），并与焦点增益安全合成**；
6. **有线 USB 模式严格通过 `wirelessEnabled` 识别并完全旁路，维持现有配置与起播逻辑零侵入**。

---

## 终版六大关键技术事实与边界（全部采纳并闭环）

### 1. 无线标志透传与多流模式隔离
* **调用点透传**：在 `CarPlayHostActivity.kt:2092` 的 `createMediaSink` 中，**必须传入 `isWireless = wirelessEnabled`**（严禁使用恒为 true 的 `wirelessHotspotMode != null`）。
* **多流严格隔离**：扣包状态机只对 `isWireless && format.audioType == "media"` 生效。通话、Siri、导航提示的 `targetHoldPackets()` 恒为 0，即刻出声。
* **有线模式零侵入**：有线模式（`!isWireless`）继续走用户偏好的 300 / 500 / 1000ms 及现有的 500ms 尾包抢跑。

### 2. 2200ms 扣包与常态保持机理
* **不扩大声卡**：硬件 `AudioTrack` 维持现有的 500ms~700ms 缓冲区；
* **扣包起播**：在 `playbackStarted == false` 时，若未达到目标包数（按 `sampleRate / 1024` 精准计算，48kHz 约 105 包，44.1kHz 约 95 包），不调用 `startPlayback()`；
* **常态保持**：一旦 `startPlayback()` 开播，声卡放满 500~700ms 后，底层 `track.write()` 会按 1.0x 真实流速自然阻塞，**多出来的约 1.5 秒音频包将天然稳定留存在 192 包容量的 Java 压缩队列中**，形成恒定的 2200ms 防护纵深。

### 3. 墙上时钟断流判定公式（严防半路提前刹车）
* 在收到每个音频包时，更新快照：
  * $t_0$：收包墙上时钟 `System.nanoTime()`；
  * $T_{\text{snapshot}}$：当前管线内总库存时长（压缩队列中包总时长 + 当时声卡剩余时长）；
* **断流刹车判据**：
  $$\text{System.nanoTime()} - t_0 > (T_{\text{snapshot}} + 50\text{ms}) \times 10^6$$
  **严禁在判定过程中使用动态缩小的剩余库存去比较**，确保声音真真正正播到最后 0ms 时才执行刹车，声卡内残留旧数据降为 0。

### 4. 刹车执行顺序与前瞻 15ms 暂存
* **刹车执行顺序**：
  1. 停止从压缩队列取包；
  2. 调用 `codec?.let(::drainCodec)` **排空解码器中已经解出的残留帧**；
  3. 取出前瞻暂存的 15ms 真实声音，执行 `PcmSmoother.applyFadeOut` 并写入声卡；
  4. 写入整帧对齐的 40ms 纯 0 静音（`bytesPerSecond * 40 / 1000`）；
  5. 立即调用 `track.pause()` 叫停底层 DMA 硬件；
  6. 重置 `playbackStarted = false`、`fadeApplied = false`；
  7. **严禁在此处调用 `track.flush()`**（仅在 `release()` 拆流时保留 `flush()`）。

### 5. 整帧对齐与符号扩展安全（PcmSmoother）
* **整帧对齐**：帧大小 `frameBytes = channels * 2`。淡出/淡入样本数统一按 `sampleRate * 15 / 1000` 计算，字节数强制按 `fadeSamples * frameBytes` 截取，严禁半帧切割导致左右声道颠倒；
* **符号位安全**：
  ```kotlin
  val low = pcm[pos].toInt() and 0xFF
  val high = pcm[pos + 1].toInt() // 保留符号位
  val sample = (high shl 8) or low
  val scaled = (sample * factor).toInt()
  pcm[pos] = (scaled and 0xFF).toByte()
  pcm[pos + 1] = ((scaled shr 8) and 0xFF).toByte()
  ```
* **单例暂存复用**：`AudioRenderer` 内预分配固定大小的 `stagingBuffer = ByteArray(48000 * 2 * 2 * 15 / 1000)`，零 GC 分配。
* **开声只淡入一次**：移除 `writePcm` 中原有的旧 `applyFadeIn`，统一由 `PcmSmoother` 处理。

### 6. 导航即时压音（流集合与 minOf 合成）
* 识别标准：`AudioChannelMapper.map(audioType, payloadType, mode).channel == AudioChannel.NAVIGATION`；
* 状态维护：`activeNavStreams: ConcurrentHashMap.KeySet<Int>`；
* 合成算法：
  ```kotlin
  val duckFactor = if (activeNavStreams.isNotEmpty()) 0.2f else 1.0f
  val effectiveGain = if (focusGain == 0f) 0f else minOf(focusGain, duckFactor)
  ```
  音乐停止不会解开导航压音，多条导航重叠先停一条不回弹，焦点为 0 保持 0，避免 0.04 蚊子音。

---

## 模块拆解与改动清单

| 模块 / 文件 | 动作 | 核心职责 |
|---|---|---|
| `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` | 修改 | 在 `createMediaSink` 中将 `wirelessEnabled` 透传给 `AndroidMediaSink` |
| `shared/src/main/java/com/shilapi/xcertplay/media/PcmSmoother.kt` | 新建 | PCM 16-bit 严格整帧对齐、符号位安全的余弦淡出/淡入与动态静音生成 |
| `shared/src/test/java/com/shilapi/xcertplay/media/PcmSmootherTest.kt` | 新建 | 48kHz/44.1kHz 整帧对齐、正负样本极值淡入淡出单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/WirelessHoldJitterBuffer.kt` | 新建 | 基于包率与采样率的动态扣包状态机：有线及非媒体流零扣包，无线音乐 1000ms 起播，欠载扣包 2200ms |
| `shared/src/test/java/com/shilapi/xcertplay/media/WirelessHoldJitterBufferTest.kt` | 新建 | 48k/44.1k 包数换算、非 media 旁路、有线旁路单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/CompositeMediaGain.kt` | 新建 | 基于导航流活跃 Set 与 `minOf` 的增益合成器 |
| `shared/src/test/java/com/shilapi/xcertplay/media/CompositeMediaGainTest.kt` | 新建 | 多流重叠计数、焦点 0 保持 0、0.2 防相乘单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt` | 修改 | 接线：透传 wirelessEnabled、快照墙上时钟刹车、排空解码器+暂存淡出+静音刹车、屏蔽无线尾包抢跑、导航集合即时压音 |

---

### Task 1: 实现 `PcmSmoother`（符号位安全淡入淡出与整帧对齐）

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

      fun applyFadeIn(pcm: ByteArray, offset: Int, length: Int, sampleRate: Int, channels: Int)
      fun applyFadeOut(pcm: ByteArray, offset: Int, length: Int, sampleRate: Int, channels: Int)
      fun createSilence(sampleRate: Int, channels: Int, durationMs: Int = 40): ByteArray
  }
  ```

- [ ] **Step 1: 编写失败测试**
验证 48kHz 与 44.1kHz 下计算出的字节数均为帧大小（4 字节）的整数倍；验证负样本（-32768, -100）经余弦缩放后平滑趋近 0，无符号反转溢出。

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
git commit -m "audio: implement PcmSmoother with frame alignment and signed safety"
```

---

### Task 2: 实现 `WirelessHoldJitterBuffer`（动态包率扣包控制器）

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

      /** 按采样率与 AAC 1024 样本/包换算目标包数 */
      fun targetHoldPackets(): Int {
          if (!isWireless || audioType != "media") return 0
          val ms = if (hasUnderrun) escalatedHoldMs else initialHoldMs
          val packetsPerSec = sampleRate.toDouble() / 1024.0
          return Math.ceil(ms * packetsPerSec / 1000.0).toInt()
      }

      fun onUnderrunDetected() {
          if (isWireless && audioType == "media") hasUnderrun = true
      }

      fun onStreamDestroyed() {
          hasUnderrun = false
      }

      fun isReadyToPlay(currentQueuePackets: Int): Boolean =
          currentQueuePackets >= targetHoldPackets()
  }
  ```

- [ ] **Step 1: 编写失败测试**
覆盖：`audioType == "telephony"` 目标为 0；有线 `isWireless == false` 目标为 0；无线 48kHz media 初始为 47 包，欠载后为 104 包；无线 44.1kHz 正确按采样率计算。

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
git commit -m "audio: implement WirelessHoldJitterBuffer with sampleRate pacing"
```

---

### Task 3: 实现 `CompositeMediaGain`（导航活跃集合与 minOf 合成器）

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
覆盖：多条导航流重叠（先停一条不回弹）、非导航流停止不影响、焦点为 0 保持 0、0.2 与 0.2 合成为 0.2（非 0.04）。

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
git commit -m "audio: implement CompositeMediaGain with stream tracking and minOf"
```

---

### Task 4: `CarPlayHostActivity` 与 `AndroidMediaSink` 全链路断流刹车与接线

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

2. **`AndroidMediaSink.kt` 屏蔽无线音乐 500ms 尾包抢跑**：
   修改行 1110-1113：
   ```kotlin
   // 仅对非无线音乐保留尾包超时抢跑；无线音乐坚决按包数扣留！
   val isWirelessMedia = isWireless && format.audioType == "media"
   if (!isWirelessMedia && !playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
       System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
       startPlayback(track)
   }
   ```

3. **15ms 真实声音前瞻暂存（复用单一缓冲区）**：
   在 `AudioRenderer` 中：
   * 预分配 `stagingBuffer = ByteArray(PcmSmoother.fadeByteCount(format.sampleRate, format.channels))`；
   * `var stagedBytes = 0`；
   * 每当解码出长度为 $L$ 的新 PCM 时：
     * 若 `stagedBytes > 0`：先将 `stagingBuffer` 内的旧数据写入 `track.write()`；
     * 若 $L \le \text{fadeBytes}$：全量拷贝至 `stagingBuffer`，`stagedBytes = L`；
     * 若 $L > \text{fadeBytes}$：写入新 PCM 的前 $L - \text{fadeBytes}$，最后 `fadeBytes` 拷入 `stagingBuffer`。

4. **快照墙上时钟断流判定与刹车执行顺序**：
   * 在收包时记录：
     * `lastPacketArrivalNs = now`；
     * `snapshotBufferedMs = (queue.size * 1024L * 1000L / format.sampleRate) + (bufferProgress.queuedBytes(track.playbackHeadPosition) * 1000L / bytesPerSecond)`；
   * 在 `maintainPlaybackBuffer()` 中：
     * 当 `playbackStarted == true && isWirelessMedia && (System.nanoTime() - lastPacketArrivalNs > (snapshotBufferedMs + 50L) * 1_000_000L)`：
       1. 停止从队列拉取新包；
       2. 调用 `codec?.let(::drainCodec)` 排空解码器中的残存数据；
       3. 对 `stagingBuffer` 调用 `PcmSmoother.applyFadeOut` 并写入 `track.write`；
       4. 写入整帧静音 `PcmSmoother.createSilence(format.sampleRate, format.channels, 40)`；
       5. 累加上述写入字节至 `bufferProgress.written()`；
       6. 立即调用 `track.pause()` 叫停底层 DMA 硬件；
       7. 置 `playbackStarted = false`，`fadeApplied = false`，`stagedBytes = 0`；
       8. 调用 `jitterBuffer.onUnderrunDetected()`（升档至 2200ms）；
       9. **严禁调用 `track.flush()`**（仅在 `release()` 销毁时保留 `flush()`）。

5. **扣包起播开嗓**：
   在 `run()` 循环中：若 `playbackStarted == false` 且 `jitterBuffer.isReadyToPlay(queue.size) == false`，则不消费队列，等待包数蓄满。蓄满后首包调用 `startPlayback`，并在第一段数据执行 `PcmSmoother.applyFadeIn`。移除原有旧的 `applyFadeIn`。

6. **本地导航即时压音接线**：
   * 在 `AndroidMediaSink` 持有 `CompositeMediaGain`；
   * 在 `onAudioStarted(type, format)` 中：
     ```kotlin
     val isNav = AudioChannelMapper.map(format.audioType, format.payloadType, AudioChannelMappingMode.AUTOMOTIVE_BUS).channel == AudioChannel.NAVIGATION
     val gain = compositeGain.onStreamStarted(type, isNav)
     applyEffectiveGain(gain)
     ```
   * 在 `onAudioStopped(type)` 中：
     ```kotlin
     val gain = compositeGain.onStreamStopped(type)
     applyEffectiveGain(gain)
     ```
   * 在 `onExternalAudioFocus(change)` 中调用 `compositeGain.setFocusGain(gain)` 并下发。

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
git commit -m "audio: wire final starvation brake, staging fade, and composite ducking"
```

---

## 终态实车行为验收

1. **MTK 回环彻底消灭**：断流时，墙上时钟精准等到最后一刻，排空解码器 -> 15ms 真实声音淡出 -> 40ms 整帧静音冲刷 -> 立即 pause，**绝无歌词重复，绝无破音**。
2. **扣包 2200ms 成功蓄水**：中间热点醒来喷包时，数据在 Java 压缩队列积攒至目标包数（约 105 包）后平滑淡入起播，声卡不发生写满死锁，常态深度从此稳固在 2200ms。
3. **后续播放稳过 2 秒断流**：由于内存备有 2200ms 纵深，后续 1~2 秒以内的热点休眠，喇叭完全如丝顺滑、零断音。
4. **导航即时压音**：导航开口瞬间音乐立刻降至 0.2，播报结束即刻恢复，彻底解决 2 秒音乐延迟下的压音滞后。
5. **有线 USB 模式零影响**：通过 `wirelessEnabled` 准确隔离，有线模式完全旁路扣包状态机，继续走既有链路，保证原汁原味低延迟。
