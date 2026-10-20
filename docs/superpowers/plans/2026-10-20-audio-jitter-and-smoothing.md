# CarPlay 音乐音频：自适应抖动缓冲与欠载平滑实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让无线 CarPlay 的音乐（`audioType=media`）在 400–1150 ms 的 Wi-Fi 收包间隔下不断音、不爆音，同时不把常态音频延迟从 500 ms 抬上去。

**Architecture:**
1. **纯 Kotlin 状态机**（`AdaptiveMediaBuffer`）：输入每个 5 秒统计窗口的 *按包率换算的到达间隔* 与欠载次数，输出当前目标缓冲（毫秒）。基线按无线模式分档，坏窗口线性升、好窗口先滞留再线性降，硬上限 900 ms、硬下限 400 ms。
2. **纯 Kotlin PCM 平滑**（`PcmSmoother`）：解码后的 16-bit PCM 上做「欠载恢复淡入」，按**声道帧**而非按样本缩放，一眼看出是否在淡入中。
3. **接线**（`AndroidMediaSink`）：`AudioRenderer` 从收包侧拿到间隔提示，改写 `startThresholdBytes`；`maintainPlaybackBuffer` 检测到欠载/重启播时把 `PcmSmoother` 复位；音频初始化与每 5 秒的 stats 行带出 `targetMs`/`startMs`/`fadeMs`。
4. **不含**：重采样/时钟漂移校正（VideoToolbox 式 rate 补偿）、AAC 码流域处理、插入合成音频。这三样本仓库没有时钟参考，做不了也做不对。

**Tech Stack:** Kotlin（无新依赖）、JUnit 4、Android 5.1/API 22 兼容（Android 5.1/API 22 上没有 `AudioTrack.getUnderrunCount()`，判定仍走 `AudioBufferProgress.queuedBytes`）、Gradle `:shared:testDebugUnitTest`。

---

## 为什么现在要改（证据）

**全量扫过的日志：** `~/Downloads/xr/scratch_logs/DiPlay-20261020-092330-826.txt`、`DiPlay-20261020-093025-588.txt`（0.4.4-geely，Android 5.1 / alps E01，两个文件时间重叠，`09:21:40`–`19:25:38`）。

| 事实 | 原文 | 含义 |
|---|---|---|
| 当前音乐缓冲 | `audio track prepared type=102 audioType=media codec=AAC_LC rate=48000 channels=2 route=usage buffer=700ms start=500` | 容量 = 500 + 200 headroom，起播水位 500 ms |
| 常态到达间隔 | `Receive: audio ... interArrivalMaxMs=395…405`（268/270 个窗口 `seqForwardGaps=0`） | 稳态是「每 5 秒收 234 包」，约 **每 21.4 ms 一包** |
| 异常间隔 A | `09:21:45.616 ... seqForwardGaps=18 ... readMaxMs=670 interArrivalMaxMs=671 seqGapAtMs=1233` | 1.2 秒处丢 18 包 ≈ **385 ms** 音频 |
| 异常间隔 B | `09:28:30.679 ... seqForwardGaps=36 ... readMaxMs=1143 interArrivalMaxMs=1144 seqGapAtMs=5385` | 5.4 秒处丢 36 包 ≈ **772 ms** 音频 |
| 同窗口视频也断 | `09:21:44.383 Receive: video packets=9 ... readMaxMs=1196`；同类 >400 ms 的窗口 331 个里有 18 个 | 断流是**整机网络线程**级别的，不是单独掉音频，也不是丢包真丢了 |
| 重启播从未生效 | `rebuffers=0`（568 行 / `09:`–`19:` 全程）；`maxWriteMs` 常态 0–358 | 欠载保护一次没触发过：`queuedBytes` 在「压缩队列空 + 队列抽干」那一刻几乎不可能同时成立 |

**两个必须记住的推论：**

1. **间隔是包率表达，不是时间。** 稳态 21.4 ms/包是 iPhone 打来的 234 包/5 秒。升档判据必须用 `interArrivalMaxMs / 平均包间隔` 换算，否则 400 ms 的**完全正常**间隔会被误判成抖动，每 5 秒升一档，90 秒顶到 900 ms。（2026-10-19 的日志里 `maxGapMs=396` 每 5 秒稳定出现，就是这个稳态。）
2. **`AudioTrack` 容量决定「升档能不能兑现」。** 现在 `capacity = mediaMillis + 200`。`mediaMillis` 一旦封顶 1000，升到 900 就落成 1100 ms，违背「不抬常态延迟」的初衷。目标 `mediaMillis` 上限必须是 **700**（`start` 700 + `headroom` 200 = `capacity` 900）。

---

## 全局约束

- **不动视频链路。** 视频解码/送帧/缓冲一律不改。断流根因（整机网络线程 >1 s 停摆）不在本计划范围，见文末「不在本计划里」。
- **不动通话/Siri/导航提示。** 它们走 `MediaAudioBuffer.plan` 的 low-latency 分支（`audioType != "media"`），本计划的所有改动必须继续保持这条分支的数值不变。
- **不超 API 22。** 不用 `AudioTrack.getUnderrunCount()`、不用 `AudioDeviceInfo` 之外的新 API；反射一律 `runCatching` 兜底。
- **不做码流改动。** AAC-ELD/AAC-LC 是变换域编码，任何按比特缩放都会打断码流；淡入只发生在 `MediaCodec` 输出后的 PCM。
- **设置页兼容。** `MediaAudioBuffer.presets` 仍是 `300/500/1000` 三档（`DiPlayActivity.kt:1054` 按 `listOf(s_300_ms, s_500_ms_default, s_1000_ms_most_stable)` 顺序取这三个字符串）。用户选的 `mediaMillis` 是**起播水位**；自适应只在这个水位之上往下追加（`250 ms` 的余量），选择器语义不变。
- **日志字段只增不改。** `DiPlay-AudioStats` 已经是用户贴给诊断用的，既有字段名一个都不能动。
- **TDD：** 每个任务先写失败测试，再写实现，再跑全量 `./gradlew :shared:testDebugUnitTest`。
- **提交粒度：** 每个任务一次提交，提交信息用 `audio:` 前缀（与仓库现有 `fix(update):` 风格一致）。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `shared/src/main/java/com/shilapi/xcertplay/media/AdaptiveMediaBuffer.kt` | **新建** | 纯函数状态机：`observe(window) -> targetMillis`、`startBytesFor(target, ...)`。不碰 Android。 |
| `shared/src/test/java/com/shilapi/xcertplay/media/AdaptiveMediaBufferTest.kt` | **新建** | 状态机全部单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/PcmSmoother.kt` | **新建** | PCM-16LE 欠载恢复淡入，按声道帧缩放。不碰 Android。 |
| `shared/src/test/java/com/shilapi/xcertplay/media/PcmSmootherTest.kt` | **新建** | 淡入全部单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/MediaAudioBuffer.kt` | 修改 | 新增 `DEFAULT_MAX_MILLIS`、`plan()` 增加 `adaptiveMaxMillis` 形参，`MEDIA_MILLIS_CEILING` 常量 |
| `shared/src/test/java/com/shilapi/xcertplay/media/MediaAudioBufferTest.kt` | 修改 | 追加封顶与下限用例 |
| `shared/src/main/java/com/shilapi/xcertplay/media/PlaybackResumePolicy.kt` | **新建** | 纯判定：写路径起播、轮询路径是否允许超时抢跑。不碰 Android。 |
| `shared/src/test/java/com/shilapi/xcertplay/media/PlaybackResumePolicyTest.kt` | **新建** | 起播规则单测 |
| `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt` | 修改 | `AudioRenderer` 接线：间隔提示、动态 `startThresholdBytes`、淡入复位、stats 字段、起播改走 `PlaybackResumePolicy` |
| `shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt` | 修改 | 把 `sink.onAudioRtp` 改成带间隔提示的重载调用 |
| `shared/src/main/java/com/shilapi/xcertplay/airplay/AudioStream.kt` | 修改 | `Listener` 新增 `onRtp(rtp, sample, arrivalGapMillis)`，保留旧 `onRtp(rtp, sample)` 默认实现 |
| `shared/src/test/java/com/shilapi/xcertplay/airplay/PlaybackBufferReuseTest.kt` | 修改 | 继续用旧签名的用例不动；追加新签名的用例 |
| `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt` | 修改 | `loadMediaBufferMillis` 封顶到 `MEDIA_MILLIS_CEILING`（旧存档里如果是 1000 也收敛到 700） |
| `common/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml` | 修改 | `s_1000_ms_most_stable` 改成 `s_700_ms_base`；加自适应说明文案 |
| `common/src/test/...`（无对应测试目录时新增）| 可选 | 持久化封顶单测 |

---

### Task 1: `MediaAudioBuffer` 增加封顶与自适应形参

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/MediaAudioBuffer.kt:16-19`、`:27-52`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/MediaAudioBufferTest.kt`

**Interfaces:**
- Consumes: 现有 `MediaAudioBuffer.plan(audioType, sampleRate, channels, minBufferBytes, mediaMillis)`、`sanitize`、`startBytesFor`、`HEADROOM_MILLIS = 200`。
- Produces:
  - `const val MEDIA_MILLIS_CEILING: Int = 700` — 用户可选与自身上限共同遵守的 `mediaMillis` 天花板，`start + HEADROOM_MILLIS` 正好 ≤ 900 ms 的 AudioTrack 容量。
  - `fun plan(audioType, sampleRate, channels, minBufferBytes, mediaMillis, adaptiveMaxMillis: Int = MEDIA_MILLIS_CEILING): Plan` — 新增第 6 个形参，**默认值让现有 5 个调用点全部不用改**。

  > 调用点核对（已扫过全仓）：生产只有 `AndroidMediaSink.kt:736` 一处；其余 4 处都在 `MediaAudioBufferTest.kt`。`DiLink51ClusterLayout.plan` 是同名但无关的函数，不属于本任务。

- [ ] **Step 1: 写失败测试**

**先改一条既有用例，再追加新用例。** 现有 `MediaAudioBufferTest.kt:8-13` 的 `music buffers the chosen delay plus headroom` 断言 `trackBufferBytes = 134_400`（= 700 ms 容量）。新规则下用户选 500 时容量让到 900 ms（`172_800`），这条用例**必须同步改**，否则 Task 1 Step 4 会红：

```kotlin
    @Test
    fun `music buffers the chosen delay plus headroom`() {
        // 48 kHz stereo 16-bit = 192 000 bytes/s.
        val plan = MediaAudioBuffer.plan("media", 48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)           // 起播仍 500 ms
        // 容量按自适应天花板 700 + 200 预留：容量只能比起播水位大，不能小。
        assertEquals(172_800, plan.trackBufferBytes)    // 900 ms
    }
```

`unknown delay falls back to the default`（`:23-27`）只断言 `startBytes`，不受影响，不要动。

然后在文件末尾追加（`plan` 第 6 参本步还不存在，编译失败即为红）：

```kotlin
@Test
fun `user choice above the ceiling is clamped to the adaptive maximum`() {
    // 1000 ms 是旧存档与旧设置页的值；封顶后按 700 记，起播与容量都按 700 算：容量 = 700 + 200。
    val plan = MediaAudioBuffer.plan("media", 48_000, 2, 7_680, mediaMillis = 1_000)
    assertEquals(134_400, plan.startBytes)           // 700 ms
    assertEquals(172_800, plan.trackBufferBytes)      // 900 ms
}

@Test
fun `adaptive maximum above the user choice widens the track`() {
    // 用户选 500，自适应已经爬到 700：起播仍 500，容量让到 900。
    val plan = MediaAudioBuffer.plan("media", 48_000, 2, 7_680, mediaMillis = 500, adaptiveMaxMillis = 700)
    assertEquals(96_000, plan.startBytes)
    assertEquals(172_800, plan.trackBufferBytes)
}

@Test
fun `adaptive maximum never goes below the user choice`() {
    val plan = MediaAudioBuffer.plan("media", 48_000, 2, 7_680, mediaMillis = 700, adaptiveMaxMillis = 500)
    assertEquals(134_400, plan.startBytes)
    assertEquals(172_800, plan.trackBufferBytes)
}

@Test
fun `adaptive maximum never changes the low latency branch`() {
    val plan = MediaAudioBuffer.plan("telephony", 16_000, 1, 1_280, mediaMillis = 1_000, adaptiveMaxMillis = 900)
    assertEquals(4 * 1024, plan.startBytes)
    assertEquals(16 * 1024, plan.trackBufferBytes)
}
```

- [ ] **Step 2: 跑测试确认红**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.MediaAudioBufferTest
```

Expected: 编译错误 `No value passed for parameter 'adaptiveMaxMillis'` / `Too many arguments`。

- [ ] **Step 3: 实现**

**本任务有两个改动，不是只有一个。** 第二个改动是 `sanitize`——**它必须先改，否则 `plan` 的封顶完全失效**：

现有 `sanitize`（`MediaAudioBuffer.kt:25`）写成 `millis.takeIf { it in presets } ?: DEFAULT_MILLIS`，而 `presets = [300, 500, 1000]` **不含 700**。于是 `sanitize(700)` 回退成 500，`plan` 里再怎么封顶、再怎么按自适应宽容量，都会被这一步悄悄压回 500。这个 bug 在离线实跑中被抓出来：`plan(mediaMillis=700, adaptiveMaxMillis=500)` 返回了 96 000，而不是 134 400。

```kotlin
    /**
     * Accepts a preset, or the ceiling itself, which the settings page offers as its own row.
     * Anything else is the default. Without the ceiling clause an adaptive level of 700 would be
     * read back as 500 and the whole ladder would collapse on the way through [plan].
     */
    fun sanitize(millis: Int): Int =
        millis.takeIf { it in presets || it == MEDIA_MILLIS_CEILING } ?: DEFAULT_MILLIS
```

然后替换 `plan` 函数并新增常量（`startBytesFor` 不动）：

```kotlin
    /** The most music buffers as a start level: user choice and the adaptive ceiling both obey it. */
    const val MEDIA_MILLIS_CEILING = 700

    /**
     * AudioTrack capacity and the amount to queue before play() for one output stream.
     *
     * [adaptiveMaxMillis] is what the adaptive jitter buffer has climbed to. It only ever widens the
     * track above the driver's choice: the start level stays at [mediaMillis] so a new track still
     * begins on time, and the room above it is what a burst after a gap needs. The start level is
     * clamped to [MEDIA_MILLIS_CEILING] so the headroom below can never exceed 900 ms in total.
     */
    fun plan(
        audioType: String,
        sampleRate: Int,
        channels: Int,
        minBufferBytes: Int,
        mediaMillis: Int,
        adaptiveMaxMillis: Int = MEDIA_MILLIS_CEILING,
    ): Plan {
        val lowLatency = Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, MIN_TRACK_BUFFER_BYTES),
            startBytes = maxOf(minBufferBytes, MIN_START_BUFFER_BYTES),
        )
        if (audioType != "media") return lowLatency
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        val requestedMillis = minOf(sanitize(mediaMillis), MEDIA_MILLIS_CEILING)
        // The adaptive level only ever adds room. A caller that passes a level below the driver's
        // choice must not shrink the start level with it: the level is a floor to climb from, not a
        // second opinion about how much the driver wanted.
        val widestMillis = minOf(maxOf(requestedMillis, adaptiveMaxMillis), MEDIA_MILLIS_CEILING)
        val startMillis = requestedMillis
        val start = (bytesPerSecond * startMillis / 1000).toInt()
        val capacity = (bytesPerSecond * (widestMillis + HEADROOM_MILLIS) / 1000).toInt()
        return Plan(
            trackBufferBytes = maxOf(capacity, lowLatency.trackBufferBytes),
            startBytes = maxOf(start, lowLatency.startBytes),
        )
    }
```

- [ ] **Step 4: 跑测试确认绿**

```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.MediaAudioBufferTest
```

Expected: `BUILD SUCCESSFUL`，`MediaAudioBufferTest` 8 个用例全过（原 4 个，其中 1 个改了期望值 + 新 4 个）。若 `music buffers the chosen delay plus headroom` 仍断言 `134_400`，说明 Step 1 的既有用例没改，回去改。

- [ ] **Step 5: 提交**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/MediaAudioBuffer.kt \
        shared/src/test/java/com/shilapi/xcertplay/media/MediaAudioBufferTest.kt
git commit -m "audio: cap the music buffer at 700 ms and make room for the adaptive ceiling"
```

---

### Task 2: `AdaptiveMediaBuffer` 状态机

**Files:**
- Create: `shared/src/main/java/com/shilapi/xcertplay/media/AdaptiveMediaBuffer.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/AdaptiveMediaBufferTest.kt`

**Interfaces:**
- Consumes: `WirelessHotspotMode`（`shared/src/main/java/com/shilapi/xcertplay/transport/WirelessReadiness.kt:22`），`MediaAudioBuffer.MEDIA_MILLIS_CEILING`。
- Produces:
  - `enum class AudioLinkKind { LOCAL_HOTSPOT, SAME_NETWORK }`，`AudioLinkKind.of(wireless: Boolean, existingWifi: Boolean?): AudioLinkKind`
  - `class AdaptiveMediaBuffer(userChoiceMillis: Int, kind: AudioLinkKind, nowNs: () -> Long = System::nanoTime)` 且 `: Closeable`
  - `fun noteArrivalGap(gapMillis: Long)` — 收包线程调用，只写 `AtomicLong`，**绝不分配**。
  - `fun observe(window: Window): Int` — 统计线程每个窗口调一次，返回新的目标 `mediaMillis`。
  - `fun currentMillis(): Int`
  - `data class Window(val packets: Int, val gapMillis: Long, val rebuffers: Int, val maxWriteMs: Long)`
  - 背景基线：`BACKGROUND_TAIL_MILLIS = 500L`（**不是**包理论间隔；理由见下）
**判据必须建立在「这台车的背景延迟」之上，而不是「包理论间隔」之上。**

第一版判据用「基线包间隔 21 ms × 倍数」做阈值，被日志推翻：几乎每个 5 秒窗口都有一个 **约 400 ms 的收包停顿**（`readMaxMs>=380` 的窗口：2026-10-19 那份 329/332，2026-10-20 那份 306/306），而真正的断流是 671 ms、1144 ms。以 84 ms 为阈值会把**稳态**判成抖动，每窗口升一档，90 秒顶死。

正确的分界是：

| 现象 | 窗口内 `maxGapMs` | 占比 | 判为 |
|---|---|---|---|
| 背景停顿（每个窗口都有） | 380–450 ms | 几乎 100% | **常态，不升档** |
| 显著超出背景 | ≥ 500 ms | 2/332、14/306 | 抖动，升档 |
| 断流 | ≥ 1000 ms | 极少数 | 严重抖动，跳两级 |

**状态规则（写进代码注释，测试逐条对应）：**

| 条件 | 动作 | 数值 |
|---|---|---|
| `packets < MIN_EVIDENCE_PACKETS(64)` | 不升不降（`packets` 太小说明窗口本身被截断） | — |
| `worst >= BACKGROUND_TAIL_MILLIS × 2`（≥1000 ms） | 升 | `+200 ms` |
| `worst >= BACKGROUND_TAIL_MILLIS`（≥500 ms） | 升 | `+100 ms` |
| `rebuffers > 0`（播放被真的打断过） | 升 | `+100 ms` |
| `maxWriteMs >= BLOCKING_WRITE_MILLIS(400)` | 升 | `+100 ms` |
| 以上都不满足、且距上次升档 < `HOLD_GRACE_NS(30 s)` | 保持（**刚升就降 = 反复蹦**） | — |
| 以上都不满足、且连续 `2` 个窗口都好 | 降 | `-100 ms` |
| 任何情况下 | 夹在 `[FLOOR_MILLIS(400), MEDIA_MILLIS_CEILING(700)]` | — |

`worst = max(window 内收包侧最大间隔, 收包线程记下的峰值间隔)`。

- **为什么写阻塞阈值是 400 而不是 250：** 日志里 `maxWriteMs` 常态就是 **307/334/358**（冷启动与每次起播后的正常写阻塞），250 会把每一档稳态都升上去。400 才落在「超出常态」的位置。
- **为什么 `BACKGROUND_TAIL_MILLIS` 不写死成 400：** 留着这个常量是为了车上验收后按实测调（Task 8 的反向判据里给了改法），但**默认值必须来自日志**，不能是猜的。
- 起基线 `target = clamp(userChoice, 400, 700)`。本地热点从 `userChoice` 起，允许降到 400；同网模式升到 700 就停，**升无可升时不报错、不抖动**。
- 线程安全：`targetMillis` 是 `@Volatile Int`；`noteArrivalGap` 只做 `AtomicLong.accumulateAndGet(maxOf)`，无锁、无分配（它在收包线程上每包都跑）。

- [ ] **Step 1: 写失败测试**

新建 `AdaptiveMediaBufferTest.kt`（期望值全部经过离线仿真验证，直接照抄）：

```kotlin
package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveMediaBufferTest {
    /** 这台车真正的稳态：5 秒 234 包，外加每个窗口都有的 ~400 ms 背景停顿。 */
    private fun steadyWindow(gapMillis: Long = 396L) = AdaptiveMediaBuffer.Window(
        packets = 234, gapMillis = gapMillis, rebuffers = 0, maxWriteMs = 358L,
    )

    @Test
    fun `same network starts at the driver's choice`() {
        assertEquals(500, AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK).currentMillis())
    }

    @Test
    fun `the background stall every window shows does not raise the level`() {
        // 329/332 与 306/306 的真实形状：每窗口都有 ~400 ms 停顿，这不是抖动。
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK)
        repeat(6) { assertEquals(500, buffer.observe(steadyWindow())) }
    }

    @Test
    fun `a 671 ms stall raises one step`() {
        // 09:21:45.616 真实窗口：缺 18 包、readMaxMs=670。
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK)
        assertEquals(600, buffer.observe(AdaptiveMediaBuffer.Window(216, 671L, 0, 3L)))
    }

    @Test
    fun `a 1144 ms stall raises two steps`() {
        // 09:28:30.679 真实窗口：缺 36 包、readMaxMs=1143。
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK)
        assertEquals(700, buffer.observe(AdaptiveMediaBuffer.Window(216, 1_144L, 0, 30L)))
    }

    @Test
    fun `a smooth interval changes nothing`() {
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK)
        repeat(4) { assertEquals(500, buffer.observe(steadyWindow(gapMillis = 45L))) }
    }

    @Test
    fun `an underrun raises the level even when arrival looks calm`() {
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK)
        assertEquals(600, buffer.observe(AdaptiveMediaBuffer.Window(234, 396L, 1, 0L)))
    }

    @Test
    fun `an abnormal write raises the level but the normal 358 ms does not`() {
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK)
        assertEquals(500, buffer.observe(AdaptiveMediaBuffer.Window(234, 396L, 0, 358L)))
        assertEquals(600, buffer.observe(AdaptiveMediaBuffer.Window(234, 396L, 0, 450L)))
    }

    @Test
    fun `the level never passes the ceiling`() {
        val buffer = AdaptiveMediaBuffer(700, AudioLinkKind.SAME_NETWORK)
        repeat(10) { buffer.observe(AdaptiveMediaBuffer.Window(216, 1_144L, 0, 0L)) }
        assertEquals(MediaAudioBuffer.MEDIA_MILLIS_CEILING, buffer.currentMillis())
    }

    @Test
    fun `a truncated window is not evidence`() {
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK)
        assertEquals(500, buffer.observe(AdaptiveMediaBuffer.Window(9, 1_200L, 0, 0L)))
    }

    @Test
    fun `the level does not fall right after it rose`() {
        var now = 0L
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK, nowNs = { now })
        assertEquals(700, buffer.observe(AdaptiveMediaBuffer.Window(216, 1_144L, 0, 0L)))
        now = 5_000_000_000L
        assertEquals(700, buffer.observe(steadyWindow()))
    }

    @Test
    fun `two quiet windows after the hold drop one step`() {
        var now = 0L
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK, nowNs = { now })
        assertEquals(700, buffer.observe(AdaptiveMediaBuffer.Window(216, 1_144L, 0, 0L)))
        now = 31_000_000_000L
        assertEquals(700, buffer.observe(steadyWindow()))
        now = 36_000_000_000L
        assertEquals(600, buffer.observe(steadyWindow()))
    }

    @Test
    fun `a local hotspot rises the same way`() {
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.LOCAL_HOTSPOT)
        assertEquals(600, buffer.observe(AdaptiveMediaBuffer.Window(234, 600L, 0, 0L)))
        assertEquals(700, buffer.observe(AdaptiveMediaBuffer.Window(234, 1_200L, 0, 0L)))
    }

    @Test
    fun `the floor is never passed and never goes under the hardware minimum`() {
        var now = 0L
        val buffer = AdaptiveMediaBuffer(300, AudioLinkKind.LOCAL_HOTSPOT, nowNs = { now })
        assertEquals(400, buffer.currentMillis())
        repeat(6) { now += 40_000_000_000L; buffer.observe(steadyWindow()) }
        assertEquals(400, buffer.currentMillis())
    }

    @Test
    fun `the receive-side hint keeps the worst gap and then clears`() {
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK)
        buffer.noteArrivalGap(400L)
        buffer.noteArrivalGap(21L)
        assertEquals(400L, buffer.peakGapMillis())
        assertEquals(0L, buffer.peakGapMillis())
    }

    @Test
    fun `a hint above the background tail raises the level on its own`() {
        val buffer = AdaptiveMediaBuffer(500, AudioLinkKind.SAME_NETWORK)
        buffer.noteArrivalGap(900L)
        // 窗口自身的 gapMillis 是背景值，但收包线程记下的 900 ms 峰值必须生效。
        assertEquals(600, buffer.observe(steadyWindow()))
    }

    @Test
    fun `a bound wire is not a wireless link`() {
        assertEquals(AudioLinkKind.LOCAL_HOTSPOT, AudioLinkKind.of(wireless = false, existingWifi = true))
        assertEquals(AudioLinkKind.SAME_NETWORK, AudioLinkKind.of(wireless = true, existingWifi = true))
        assertEquals(AudioLinkKind.LOCAL_HOTSPOT, AudioLinkKind.of(wireless = true, existingWifi = false))
        assertEquals(AudioLinkKind.LOCAL_HOTSPOT, AudioLinkKind.of(wireless = true, existingWifi = null))
    }
}
```

- [ ] **Step 2: 跑测试确认红**

```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.AdaptiveMediaBufferTest
```

Expected: 编译失败 `Unresolved reference: AdaptiveMediaBuffer`。

- [ ] **Step 3: 实现**

新建 `AdaptiveMediaBuffer.kt`：

```kotlin
package com.shilapi.xcertplay.media

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** How many Wi-Fi hops the music took. The driver's link choice decides it, not the radio. */
enum class AudioLinkKind {
    /** The car runs its own access point: one hop, and the car owns the channel. */
    LOCAL_HOTSPOT,

    /** Both ends sit on a router the car joined: two hops, and the router's queue is not ours. */
    SAME_NETWORK;

    companion object {
        fun of(wireless: Boolean, existingWifi: Boolean?): AudioLinkKind =
            if (wireless && existingWifi == true) SAME_NETWORK else LOCAL_HOTSPOT
    }
}

/**
 * How much music to buffer, in units of [MediaAudioBuffer.plan]'s `mediaMillis`.
 *
 * The level follows the link that was measured, not a theory about packet spacing. On this car the
 * receive loop stalls for about 400 ms in *every* reporting window — 329 of 332 windows in one
 * captured log, 306 of 306 in another — and that stall is harmless: the track rides through it. What
 * is not harmless is the rare window where the same stall doubles to 671 ms or 1144 ms and eighteen
 * to thirty-six packets arrive at once. Judging against [BACKGROUND_TAIL_MILLIS] keeps the first out
 * of the ladder and lets the second climb it.
 *
 * Two more rules keep the level from oscillating: a rise holds for [HOLD_GRACE_NS] before any fall
 * is allowed, and a fall needs [QUIET_WINDOWS_BEFORE_FALL] quiet windows in a row.
 */
class AdaptiveMediaBuffer(
    userChoiceMillis: Int,
    val kind: AudioLinkKind,
    private val nowNs: () -> Long = System::nanoTime,
) : Closeable {
    /**
     * What one reporting window saw.
     *
     * [gapMillis] is the largest interval the receive side measured; [packets] says whether the
     * window was long enough to trust; [rebuffers] counts times playback really stopped and
     * restarted; [maxWriteMs] is how long a write to the track blocked, which is how long sound was
     * actually held up.
     */
    data class Window(
        val packets: Int,
        val gapMillis: Long,
        val rebuffers: Int,
        val maxWriteMs: Long,
    )

    /** Where the ladder starts and where it settles back to: the driver's own choice, at least the floor. */
    private val baseMillis = minOf(maxOf(userChoiceMillis, FLOOR_MILLIS), MediaAudioBuffer.MEDIA_MILLIS_CEILING)

    @Volatile private var targetMillis: Int = baseMillis
    private val peakGap = AtomicLong()
    private var lastRiseNs = 0L
    private var hasRisen = false
    private var quietWindows = 0

    fun currentMillis(): Int = targetMillis

    /** Called on the receive thread for each datagram interval. Allocation-free by contract. */
    fun noteArrivalGap(gapMillis: Long) {
        if (gapMillis > 0L) peakGap.accumulateAndGet(gapMillis, ::maxOf)
    }

    /** Largest gap seen since the last call, then zeroed. */
    fun peakGapMillis(): Long = peakGap.getAndSet(0L)

    fun observe(window: Window): Int {
        val observed = maxOf(window.gapMillis, peakGap.getAndSet(0L))
        if (window.packets < MIN_EVIDENCE_PACKETS) return targetMillis
        val current = targetMillis
        val stepped = when {
            observed >= BACKGROUND_TAIL_MILLIS * 2 -> current + STEP_MILLIS * 2
            observed >= BACKGROUND_TAIL_MILLIS -> current + STEP_MILLIS
            window.rebuffers > 0 -> current + STEP_MILLIS
            window.maxWriteMs >= BLOCKING_WRITE_MILLIS -> current + STEP_MILLIS
            else -> current
        }
        if (stepped > current) {
            targetMillis = minOf(stepped, MediaAudioBuffer.MEDIA_MILLIS_CEILING)
            lastRiseNs = nowNs()
            hasRisen = true
            quietWindows = 0
            return targetMillis
        }
        // The hold only constrains a level that was raised. It does not, by itself, earn a fall:
        // a fall still needs its quiet windows below. Before the first rise there is nothing to
        // hold, and `lastRiseNs` is 0 — comparing it to the clock would read as "held long ago".
        val holding = hasRisen && nowNs() - lastRiseNs < HOLD_GRACE_NS
        if (holding) {
            quietWindows = 0
            return targetMillis
        }
        if (++quietWindows < QUIET_WINDOWS_BEFORE_FALL) return targetMillis
        quietWindows = 0
        targetMillis = maxOf(current - STEP_MILLIS, baseMillis)
        return targetMillis
    }

    override fun close() = Unit

    private companion object {
        /** The stall every healthy window shows. Measured, not assumed: see the class comment. */
        const val BACKGROUND_TAIL_MILLIS = 500L
        const val MIN_EVIDENCE_PACKETS = 64
        const val STEP_MILLIS = 100
        const val FLOOR_MILLIS = 400
        /** Normal cold-start and post-stall writes reach 358 ms; this sits above them. */
        const val BLOCKING_WRITE_MILLIS = 400L
        const val HOLD_GRACE_NS = 30_000_000_000L
        const val QUIET_WINDOWS_BEFORE_FALL = 2
    }
}
```

- [ ] **Step 4: 跑测试确认绿**

```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.AdaptiveMediaBufferTest
```

Expected: 16 个用例全过。

> 这 16 个用例与上面的实现已经**离线实跑通过**（Kotlin 2.2.10 编译器 + JUnit 4.13.2，语言级别 2.1）。若你跑出失败，先怀疑自己抄漏了行，不要去改期望值。

- [ ] **Step 5: 提交**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/AdaptiveMediaBuffer.kt \
        shared/src/test/java/com/shilapi/xcertplay/media/AdaptiveMediaBufferTest.kt
git commit -m "audio: judge the music buffer against this car's own background stall"
```

---

### Task 3: `PcmSmoother` — PCM 域欠载恢复淡入

**Files:**
- Create: `shared/src/main/java/com/shilapi/xcertplay/media/PcmSmoother.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/media/PcmSmootherTest.kt`

**Interfaces:**
- Consumes: 裸 16-bit 小端 PCM（`MediaCodec` 输出即为该格式，见 `AndroidMediaSink.drainCodec`）。
- Produces:
  - `class PcmSmoother(private val frameBytes: Int, private val fadeMillis: Int = 5, private val sampleRate: Int = 48_000)`
  - `fun arm()` — 下一次 `apply` 从头淡入。
  - `fun apply(data: ByteArray, offset: Int = 0, length: Int = data.size)` — 只当 `armed` 时改写前 `fadeMillis` 的样本，其余原样返回。
  - `val isArmed: Boolean`
- 修复的两处现存缺陷（`AndroidMediaSink.kt:601`、`:1045-1048`、`:1125-1135`）：
  - `fadeApplied` 是**一次性**标志，欠载重启播后从不复位 → 恢复播放是硬切；
  - `applyFadeIn` 按**样本**（2 字节）缩放，立体声会把左右声道按同一递增比例分别缩放（第 0 帧 L×1/N、R×2/N），声像被推歪。

- [ ] **Step 1: 写失败测试**

新建 `PcmSmootherTest.kt`：

```kotlin
package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSmootherTest {
    /** 48 kHz、5 ms 淡入 = 240 帧。 */
    private fun frames(count: Int, left: Short, right: Short): ByteArray {
        val out = ByteArray(count * 4)
        for (frame in 0 until count) {
            out[frame * 4] = left.toByte(); out[frame * 4 + 1] = (left.toInt() shr 8).toByte()
            out[frame * 4 + 2] = right.toByte(); out[frame * 4 + 3] = (right.toInt() shr 8).toByte()
        }
        return out
    }

    private fun left(data: ByteArray, frame: Int): Int =
        (data[frame * 4].toInt() and 0xff) or (data[frame * 4 + 1].toInt() shl 8)

    private fun right(data: ByteArray, frame: Int): Int =
        (data[frame * 4 + 2].toInt() and 0xff) or (data[frame * 4 + 3].toInt() shl 8)

    @Test
    fun `an unarmed smoother leaves the payload untouched`() {
        val data = frames(3, 1_000, 2_000)
        val before = data.copyOf()
        PcmSmoother(frameBytes = 4, sampleRate = 48_000).apply(data)
        assertArrayEquals(before, data)
    }

    @Test
    fun `both channels of a frame scale by the same factor`() {
        val data = frames(240, 10_000, 5_000)
        PcmSmoother(frameBytes = 4, sampleRate = 48_000).apply { arm() }.apply(data)
        for (frame in 0 until 240) {
            val ratioLeft = left(data, frame).toDouble() / 10_000.0
            val ratioRight = right(data, frame).toDouble() / 5_000.0
            assertEquals(ratioLeft, ratioRight, 0.01)
        }
    }

    @Test
    fun `the first frame is silent and the last faded frame is nearly whole`() {
        val data = frames(240, 10_000, 10_000)
        PcmSmoother(frameBytes = 4, sampleRate = 48_000).apply { arm() }.apply(data)
        assertEquals(0, left(data, 0))
        assertTrue(left(data, 239) > 9_900)
    }

    @Test
    fun `sound past the fade is untouched`() {
        val data = frames(400, 7_000, 7_000)
        val before = data.copyOfRange(240 * 4, 400 * 4)
        PcmSmoother(frameBytes = 4, sampleRate = 48_000).apply { arm() }.apply(data)
        assertArrayEquals(before, data.copyOfRange(240 * 4, 400 * 4))
    }

    @Test
    fun `rearming fades again after an underrun`() {
        val smoother = PcmSmoother(frameBytes = 4, sampleRate = 48_000)
        smoother.arm()
        assertTrue(smoother.isArmed)
        smoother.apply(frames(240, 10_000, 10_000))
        assertFalse(smoother.isArmed)
        smoother.arm()
        val again = frames(240, 10_000, 10_000)
        smoother.apply(again)
        assertEquals(0, left(again, 0))
    }

    @Test
    fun `a mid-buffer offset does not touch the bytes before it`() {
        val data = frames(400, 9_000, 9_000)
        val head = data.copyOfRange(0, 100 * 4)
        PcmSmoother(frameBytes = 4, sampleRate = 48_000).apply { arm() }.apply(data, offset = 100 * 4, length = 300 * 4)
        assertArrayEquals(head, data.copyOfRange(0, 100 * 4))
        assertEquals(0, left(data, 100))
    }

    @Test
    fun `mono uses two byte frames`() {
        val data = ByteArray(480 * 2) { 0x10 }
        PcmSmoother(frameBytes = 2, sampleRate = 48_000).apply { arm() }.apply(data)
        assertEquals(0, data[0].toInt())
        assertTrue(((data[478].toInt() and 0xff) or (data[479].toInt() shl 8)) > 3_000)
    }

    @Test
    fun `a partial trailing frame is not rewritten`() {
        val data = frames(10, 8_000, 8_000) + byteArrayOf(0x34, 0x12)
        val tail = data.copyOfRange(40, 42)
        PcmSmoother(frameBytes = 4, sampleRate = 48_000).apply { arm() }.apply(data)
        assertArrayEquals(tail, data.copyOfRange(40, 42))
    }
}
```

- [ ] **Step 2: 跑测试确认红**

```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.PcmSmootherTest
```

Expected: 编译失败 `Unresolved reference: PcmSmoother`。

- [ ] **Step 3: 实现**

新建 `PcmSmoother.kt`：

```kotlin
package com.shilapi.xcertplay.media

/**
 * Fades the first few milliseconds of PCM back in after playback has stopped and restarted.
 *
 * The pop an underrun leaves behind is a step in the waveform: the speaker is at rest, then the
 * first sample of the next burst lands anywhere on the sinusoid. A short ramp removes that step and
 * nothing else. It runs on decoded PCM because the encoder's output is a transform-domain stream —
 * scaling it would break it, not soften it.
 *
 * Scaling is per frame, not per sample: a stereo frame is a left sample and a right sample, and they
 * have to be multiplied by the same factor or the image slides toward whichever channel was written
 * first. [frameBytes] is however many bytes one frame of this stream takes.
 */
class PcmSmoother(
    private val frameBytes: Int,
    private val fadeMillis: Int = DEFAULT_FADE_MILLIS,
    private val sampleRate: Int = 48_000,
) {
    private val fadeFrames: Int = maxOf(1, sampleRate * fadeMillis / 1000)

    /** True until the next payload has been faded in. Set by [arm]. */
    var isArmed: Boolean = false
        private set

    /** Requests a fade-in at the next [apply]; called wherever playback restarts. */
    fun arm() {
        isArmed = true
    }

    fun apply(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (!isArmed) return
        isArmed = false
        val frames = minOf(length / frameBytes, data.size / frameBytes - offset / frameBytes)
        if (frames <= 0) return
        val fadedFrames = minOf(frames, fadeFrames)
        for (frame in 0 until fadedFrames) {
            val base = offset + frame * frameBytes
            val scaled = frameScale(frame, fadedFrames)
            for (byte in 0 until frameBytes step 2) {
                if (base + byte + 1 >= data.size) break
                val sample = (data[base + byte].toInt() and 0xff) or (data[base + byte + 1].toInt() shl 8)
                val value = ((sample.toLong() * scaled) ushr SCALE_BITS).toInt()
                data[base + byte] = value.toByte()
                data[base + byte + 1] = (value shr 8).toByte()
            }
        }
    }

    /**
     * Ramp gain in [0, 1 << SCALE_BITS], shared by every channel of the frame.
     *
     * The first frame is exactly zero and the last faded frame is exactly full scale. Both ends
     * matter: starting above zero leaves the very step this class exists to remove, and stopping
     * short of full scale puts that step at the other end of the ramp. Dividing by `total - 1` is
     * what reaches full scale; a one-frame fade is simply silent.
     */
    private fun frameScale(frame: Int, total: Int): Long =
        if (total <= 1) 0L else (frame.toLong() shl SCALE_BITS) / (total - 1)

    private companion object {
        const val DEFAULT_FADE_MILLIS = 5
        const val SCALE_BITS = 15
    }
}
```

- [ ] **Step 4: 跑测试确认绿**

```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.PcmSmootherTest
```

Expected: 8 个用例全过。

> 同样已离线实跑通过。`frameScale` 用 `total - 1` 做除数、首帧取 0，这两处是实跑修出来的：按 `total` 整除会让首帧留下满幅的 0.4%，正是这个类要去掉的那一步。

- [ ] **Step 5: 提交**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/PcmSmoother.kt \
        shared/src/test/java/com/shilapi/xcertplay/media/PcmSmootherTest.kt
git commit -m "audio: fade music back in after an underrun, per channel frame"
```

---

### Task 4: 收包侧把到达间隔交给 sink

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/airplay/AudioStream.kt:36`（`Listener`）
- Modify: `shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt:151`
- Test: `shared/src/test/java/com/shilapi/xcertplay/airplay/PlaybackBufferReuseTest.kt`

**Interfaces:**
- Consumes: `AudioStream.Listener.onRtp(rtp, sample)`（现有）、`sink.onAudioRtp(type, format, rtp, sample)`。
- Produces:
  - `interface Listener { fun onRtp(rtp: ByteArray, sample: Int) {} ; fun onRtp(rtp: ByteArray, sample: Int, arrivalGapMillis: Long) = onRtp(rtp, sample) }` — 新方法是**有默认实现**的重载，`PlaybackBufferReuseTest.kt:51` 与其它匿名实现全部继续编译。
  - `CarPlayMediaEngine` 改为重写新方法并调 `sink.onAudioRtp(type, format, rtp, sample, arrivalGapMillis)`。
  - `MediaSink.onAudioRtp(type, format, rtp, sample, arrivalGapMillis: Long = 0L)` — 新重载，默认值让 `AndroidMediaSink` 之外的实现不受影响。

**为什么放在 `AudioStream` 而不是 `AndroidMediaSink.submit`：** 间隔必须量在**收包线程**上。`submit` 与 `drainCodec/writePcm` 同在一个 `carplay-audio` 线程，AudioTrack 写阻塞（日志里常态 350 ms，最坏 358 ms）会把 `submit` 一起堵住，`submit` 里量到的「间隔」其实是写阻塞时长，会自激升档。

- [ ] **Step 1: 写失败测试**

在 `PlaybackBufferReuseTest.kt` 追加（复用本文件已有的 `sendAudio`、`writeU32Be` 与 `AirPlayCrypto.chachaSeal` 装配，不要另写报文构造）：

```kotlin
    @Test
    fun theArrivalCallbackCarriesAGapAndStillDeliversEveryPacket() {
        val key = ByteArray(32) { index -> (index + 5).toByte() }
        val stream = AudioStream(key, 7)
        val gaps = Collections.synchronizedList(mutableListOf<Long>())
        val payloads = Collections.synchronizedList(mutableListOf<ByteArray>())
        val (dataPort, _) = stream.listen(object : AudioStream.Listener {
            override fun onRtp(rtp: ByteArray, sample: Int, arrivalGapMillis: Long) {
                gaps.add(arrivalGapMillis)
                payloads.add(rtp)
            }
        })
        val first = "gap-probe-payload".toByteArray()
        val second = "gap-probe-payload-two".toByteArray()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline && !holds(payloads, first, second)) {
                sendAudio(dataPort, key, first, sample = 100, nonce = 11)
                sendAudio(dataPort, key, second, sample = 200, nonce = 12)
                Thread.sleep(30)
            }
            assertTrue(holds(payloads, first, second))
            // 首包没有前驱，间隔记 0；其后每一包都必须带一个非负间隔。
            assertTrue(gaps.isNotEmpty())
            assertEquals(0L, gaps.first())
            assertTrue(gaps.all { it >= 0L })
            // 同一个 UDP 端口连发两包，第二次到达时能测到一个真实间隔。
            assertTrue(gaps.any { it > 0L })
        } finally {
            stream.close()
        }
    }
```

并在文件顶部补上 `import org.junit.Assert.assertEquals`（当前只 import 了 `assertArrayEquals`、`assertFalse`、`assertNotSame`、`assertTrue`）。

- [ ] **Step 2: 跑测试确认红**

```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.airplay.PlaybackBufferReuseTest
```

Expected: 编译失败 `'onRtp' hides member of supertype ... and needs 'override' modifier` / `overrides nothing`。

- [ ] **Step 3: 实现**

`AudioStream.Listener`（`AudioStream.kt:30-45` 附近）加：

```kotlin
        fun onRtp(rtp: ByteArray, sample: Int) {}

        /**
         * Same packet, plus how long the receive loop waited for it.
         *
         * Measured here rather than in the sink: the sink's worker writes to AudioTrack and blocks
         * there for hundreds of milliseconds, so an interval measured after that write says nothing
         * about the link. Callers that only care about the RTP keep the two-argument form.
         */
        fun onRtp(rtp: ByteArray, sample: Int, arrivalGapMillis: Long) = onRtp(rtp, sample)
```

在接收循环里，`onRtp` 调用处改为携带间隔。间隔以**同一 RTP 时钟域内相邻到达**为准，用 `System.nanoTime()` 差值，首包与跨流重启时给 `0`。

**先加字段**（`AudioStream.kt:51` 已有 `private val receivedPackets = AtomicInteger()`，紧挨着放；**该字段当前不存在，是新增的**）：

```kotlin
    /** When the previous datagram arrived, for the interval the sink uses; 0 until the first. */
    private var lastArrivalNs = 0L
```

**再改接收处**（`AudioStream.kt:196` 的 `listener.onRtp(rtp, sample)`）：

```kotlin
                val arrivedAt = System.nanoTime()
                val gapMillis = if (lastArrivalNs == 0L) 0L else (arrivedAt - lastArrivalNs) / 1_000_000L
                lastArrivalNs = arrivedAt
                // ... 现有解密与 rtp 拷贝逻辑不动 ...
                listener.onRtp(rtp, sample, gapMillis)
```

> 只改**音频**这一条 `onRtp`。同文件 `ScreenStream` 的视频路径（`PlaybackBufferReuseTest.kt:83` 构造的那个流）**不要碰**——计划里所有断言都依赖视频路径零改动。

`CarPlayMediaEngine.kt:151`：

```kotlin
                override fun onRtp(rtp: ByteArray, sample: Int, arrivalGapMillis: Long) =
                    sink.onAudioRtp(type, format, rtp, sample, arrivalGapMillis)
```

`CarPlayMediaEngine.kt:22` 的接口：

```kotlin
    fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int, arrivalGapMillis: Long) =
        onAudioRtp(type, format, rtp, sample)
```

- [ ] **Step 4: 跑测试确认绿**

```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.airplay.PlaybackBufferReuseTest
```

Expected: 原用例 + 新用例全过。

- [ ] **Step 5: 提交**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/airplay/AudioStream.kt \
        shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt \
        shared/src/test/java/com/shilapi/xcertplay/airplay/PlaybackBufferReuseTest.kt
git commit -m "audio: carry the receive interval from the socket to the sink"
```

---

### Task 5: `AndroidMediaSink` 接线（自适应水位 + 淡入复位 + stats 字段）

**Files:**
- Create: `shared/src/main/java/com/shilapi/xcertplay/media/PlaybackResumePolicy.kt`
- Create: `shared/src/test/java/com/shilapi/xcertplay/media/PlaybackResumePolicyTest.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`
  - `:44` 构造参数，`:146` 入口，`:206-214` `audioRenderer`，`:583-625` `AudioRenderer` 字段，`:727-805` `createTrack`，`:1034-1104` `writePcm`/`maintainPlaybackBuffer`，`:1107-1135` `logStatsIfDue`/`applyFadeIn`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt:2097`（把链路类型传给 sink）
- **不改**：`shared/src/test/java/com/shilapi/xcertplay/media/AndroidMediaSinkStateTest.kt`。那个文件测的是投屏状态机，与音频无关；本任务不再往里塞`AndroidMediaSink` 的音频断点

**Interfaces:**
- Consumes: `AdaptiveMediaBuffer`、`AudioLinkKind`、`PcmSmoother`、`MediaAudioBuffer.plan(..., adaptiveMaxMillis)`、`MediaSink.onAudioRtp(..., arrivalGapMillis)`。
- Produces:
  - `internal object PlaybackResumePolicy`，两个纯判定函数（见 Step 1）。
  - `AndroidMediaSink(..., audioLinkKind: AudioLinkKind = AudioLinkKind.LOCAL_HOTSPOT, ...)` 新构造参数（带默认值，所有既有构造点继续编译）。
  - `AudioRenderer` 内部新增字段 `adaptiveBuffer: AdaptiveMediaBuffer?`（仅 `audioType == "media"` 时非空）、`smoother = PcmSmoother(frameBytes, sampleRate = format.sampleRate)`。
  - stats 行追加三个字段：`targetMs=`、`startMs=`、`fadeEvents=`。

- [ ] **Step 1: 先把起播规则抽成纯函数（否则这个任务无法测试）**

`AudioRenderer` 是 `private class`，且依赖 `MediaCodec`/`AudioTrack`；仓库里没有任何 `ShadowAudioTrack`/`ShadowMediaCodec` 的先例（`grep` 过，零结果）。在这种条件下，写「构造一个 sink 然后断言」的用例只会得到编译不过的假测试。

所以先把**起播/重启播的纯判定逻辑**抽出来，让行为可以被真正验证：

新建 `shared/src/main/java/com/shilapi/xcertplay/media/PlaybackResumePolicy.kt`：

```kotlin
package com.shilapi.xcertplay.media

/**
 * When a paused music track may start again.
 *
 * Two callers exist and they are not the same question. The write path starts a fresh track as soon
 * as it has queued enough bytes, which is what [MediaAudioBuffer.plan]'s start level is for. The
 * polling path has a second, weaker rule: if the queue stays empty and nothing has been written for
 * a while, play what is there rather than hold a tail burst forever. That weak rule is right for a
 * track ending or a skip, and wrong right after an underrun — it fires before the raised start level
 * can be reached, which silently erases everything the adaptive buffer just decided.
 */
internal object PlaybackResumePolicy {
    /** The write path: a fresh track starts once the start level is queued. */
    fun writePathStarts(prebufferBytes: Int, startThresholdBytes: Int): Boolean =
        startThresholdBytes > 0 && prebufferBytes >= startThresholdBytes

    /**
     * The polling path: may a stopped track start on the idle timeout alone?
     *
     * No, once playback has already been interrupted at least once — the resume waits for the start
     * level instead. Before any interruption the timeout is the only thing that guarantees a short
     * tail is played at all.
     */
    fun idleTimeoutStarts(rebufferCount: Int, prebufferBytes: Int, startThresholdBytes: Int): Boolean =
        rebufferCount == 0 || writePathStarts(prebufferBytes, startThresholdBytes)
}
```

新建 `shared/src/test/java/com/shilapi/xcertplay/media/PlaybackResumePolicyTest.kt`：

```kotlin
package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumePolicyTest {
    @Test
    fun `the write path starts exactly at the start level`() {
        assertFalse(PlaybackResumePolicy.writePathStarts(95_999, 96_000))
        assertTrue(PlaybackResumePolicy.writePathStarts(96_000, 96_000))
        assertTrue(PlaybackResumePolicy.writePathStarts(120_000, 96_000))
    }

    @Test
    fun `an unset start level never starts playback`() {
        assertFalse(PlaybackResumePolicy.writePathStarts(1_000_000, 0))
    }

    @Test
    fun `a track that was never interrupted still plays its tail on the timeout`() {
        // 尾部小突发：攒不到水位，但必须放出来，否则声音被吞掉。
        assertTrue(PlaybackResumePolicy.idleTimeoutStarts(0, 4_096, 96_000))
    }

    @Test
    fun `an interrupted track waits for the raised start level`() {
        // 断过一次之后，超时不许再抢跑；否则升到 600/700 ms 的水位形同虚设。
        assertFalse(PlaybackResumePolicy.idleTimeoutStarts(1, 4_096, 96_000))
        assertFalse(PlaybackResumePolicy.idleTimeoutStarts(3, 115_199, 115_200))
        assertTrue(PlaybackResumePolicy.idleTimeoutStarts(3, 115_200, 115_200))
    }

    @Test
    fun `a raised level applies to an interrupted track and not to a fresh one`() {
        // 同样是攒了 115 200 字节（600 ms）：断流恢复要它，尾部突发不需要。
        assertTrue(PlaybackResumePolicy.writePathStarts(115_200, 115_200))
        assertFalse(PlaybackResumePolicy.idleTimeoutStarts(1, 50_000, 115_200))
        assertTrue(PlaybackResumePolicy.idleTimeoutStarts(1, 115_200, 115_200))
    }
}
```

- [ ] **Step 2: 跑测试确认红**

```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.PlaybackResumePolicyTest
```

Expected: 编译失败 `Unresolved reference: PlaybackResumePolicy`。先把 `PlaybackResumePolicy.kt` 写成空壳（只有签名、函数体直接 `error("not yet")`）就能看到 5 个用例真正变红，而不是编译不过——这才是这个任务的红。

- [ ] **Step 3: 实现**

**(a) 构造参数与入口**（`AndroidMediaSink.kt:44` 附近、`:146`）：

```kotlin
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    /** Which Wi-Fi layout the driver chose; decides the adaptive music buffer's baseline. */
    private val audioLinkKind: AudioLinkKind = AudioLinkKind.LOCAL_HOTSPOT,
```

```kotlin
    override fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int, arrivalGapMillis: Long) {
        audioRenderer(type, format).submit(rtp, sample, arrivalGapMillis)
    }
```

`audioRenderer(type, format)` 追加 `audioLinkKind` 透传。

> 注意：`audioLinkKind` 必须在 `CarPlayHostActivity` **构造 sink 之前**由 `wirelessHotspotMode` 决定。`CarPlayHostActivity.kt:386` 已有 `private var wirelessHotspotMode = WirelessHotspotMode.MANUAL`；`createRuntimeConfig()`（`CarPlayHostActivity.kt:201`）已把 `wirelessHotspotMode` 放进 `CarPlayRuntimeConfig`（`CarPlayRuntimeConfig.kt:56`）。sink 构造点是 `CarPlayHostActivity.kt:2097`，加一行：

```kotlin
            audioLinkKind = AudioLinkKind.of(
                wireless = createRuntimeConfig().transport != CarPlayTransport.WIRED,
                existingWifi = wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI,
            ),
```

**(b) `AudioRenderer` 字段**（`:583-625`）：

```kotlin
    private val adaptiveBuffer: AdaptiveMediaBuffer? =
        if (format.audioType == "media") AdaptiveMediaBuffer(mediaBufferMillis, audioLinkKind) else null
    private val smoother = PcmSmoother(if (format.channels >= 2) 4 else 2, sampleRate = format.sampleRate)
    private var fadeEvents = 0
    private var startMillis = 0
```

删除 `private var fadeApplied = false`（`:601`）。

**(c) `submit`**（`:642`）：

```kotlin
    fun submit(rtp: ByteArray, sample: Int, arrivalGapMillis: Long = 0L) {
        if (started) {
            packetsReceived.incrementAndGet()
            if (arrivalGapMillis > 0L) adaptiveBuffer?.noteArrivalGap(arrivalGapMillis)
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
        }
        // ... 其余不变
    }
```

> `adaptiveBuffer` 是 `AudioRenderer` 的字段，`submit` 在接收线程调用没问题；`noteArrivalGap` 只做 `AtomicLong` 自增与 CAS，无锁无分配。

**(d) `createTrack`**（`:736`、`:792`）：

```kotlin
        val adaptiveMax = adaptiveBuffer?.currentMillis() ?: mediaBufferMillis
        val plan = MediaAudioBuffer.plan(
            format.audioType, format.sampleRate, format.channels, minBuffer, mediaBufferMillis, adaptiveMax,
        )
```

`startThresholdBytes` 之后记录 `startMillis = startThresholdBytes * 1000L / bytesPerSecond`，并把 `targetMs` 写进 `report`：

```kotlin
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=$startMillis " +
            "targetMs=${adaptiveBuffer?.currentMillis() ?: mediaBufferMillis}")
```

**(e) 写路径淡入**（`:1045-1048`）：

```kotlin
        smoother.apply(data, offset, length)
```

`startPlayback` 与 `maintainPlaybackBuffer` 重启播前 `smoother.arm()`，并把 `fadeEvents++`：

```kotlin
    private fun startPlayback(track: AudioTrack) {
        underrunsAtPlaybackStart = track.compatUnderruns()
        track.play()
        playbackStarted = true
        // Music restarts after a gap with an arbitrary sample phase; calls and prompts never pause.
        if (adaptiveBuffer != null) {
            smoother.arm()
            fadeEvents++
        }
    }
```

删除 `applyFadeIn`（`:1125-1135`），保留 `byteSwapS16`（`:1137`）。

**(f) 每窗口调 `observe`**（`logStatsIfDue`，`:1107`）：在 `if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return` 之后、打印之前插入：

```kotlin
        // 先把本窗口的计数取走：下面的 stats 行会把 packetsReceived 清零并复用同一个值。
        val windowPackets = packetsReceived.getAndSet(0)
        val windowDropped = packetsDropped.getAndSet(0)
        val windowGapMs = maxArrivalGapMs.getAndSet(0)
        val windowWrites = maxWriteMs
        val rebuffersInWindow = rebufferCount - statsLastRebufferCount
        statsLastRebufferCount = rebufferCount
        val nextTarget = adaptiveBuffer?.observe(
            AdaptiveMediaBuffer.Window(
                packets = windowPackets,
                gapMillis = windowGapMs,
                rebuffers = rebuffersInWindow,
                maxWriteMs = windowWrites,
            ),
        )
        if (nextTarget != null && startThresholdBytes > 0) {
            // Live level change: only the start level moves, the AudioTrack keeps its capacity.
            // A rise takes effect at the next restart, a fall at the next burst — both are heard as
            // "the music waited a little longer" rather than as a reconnect.
            startThresholdBytes = mediaStartBytesFor(nextTarget)
        }
```

随后 **(g)** 的 stats 行改为直接引用这三个局部变量，**不要再调 `getAndSet`**（第二次取会是 0）：

```kotlin
        val line = "audio stats audioType=${format.audioType} codec=${format.codec} rx=$windowPackets " +
            "dropped=$windowDropped underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=$windowGapMs " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$windowWrites " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force " +
            "targetMs=${adaptiveBuffer?.currentMillis() ?: mediaBufferMillis} startMs=$startMillis fadeEvents=$fadeEvents"
```

> **这是本任务最容易写错的一处。** 现有 `logStatsIfDue`（`AndroidMediaSink.kt:1113-1117`）在拼日志串时顺手 `getAndSet(0)`。再插入一次 `observe` 时，如果两处各取一次，第二个取到的永远是 0：`Window.packets = 0` 恒小于 `MIN_EVIDENCE_PACKETS`，状态机**从此再也不动**，日志还会显示 `rx=0`。必须先取到局部变量，再让日志行复用它们。

`statsLastRebufferCount` 新字段；`mediaStartBytesFor(millis)`：

```kotlin
    private fun mediaStartBytesFor(millis: Int): Int {
        if (bytesPerSecond <= 0) return startThresholdBytes
        val requested = bytesPerSecond.toLong() * millis / 1000L
        return minOf(requested, (trackCapacityBytes - PREBUFFER_WRITE_CHUNK_BYTES)).toInt()
    }
```

`trackCapacityBytes` 在 `createTrack` 里记录（当前 `capacityBytes` 变量提为字段）。

> **升档不能超过容量。** 容量是 `createTrack` 时算的（`mediaMillis + 200`，`mediaMillis ≤ 700`），所以 `start` 最多 700 ms 落进 900 ms 容量里。`mediaStartBytesFor` 再兜一层 `capacity - chunk`。**不要**在运行期重建 AudioTrack：`AudioTrack` 重建会 flush 掉已排队的声音，在音乐中直接断一下。

**(f2) 让升档真正生效——只改一个抢跑条件（必做，否则 (f) 白改）**

`writePcm` 在未播放时本来就按水位起播（`:1067-1072`）：

```kotlin
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                }
            }
```

问题不在 `writePcm`，而在 `maintainPlaybackBuffer`（`:1100-1103`）的兜底会**抢跑**：

```kotlin
        // 现状：队列一空、等够 500 ms，不看攒了多少就起播 —— 水位升到 700 也没用。
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
```

改成：

```kotlin
        // 500 ms 的兜底是给尾部小突发和切歌的，不是给断流恢复的：断流之后如果还按它抢跑，
        // 水位升到 600/700 全被抵消，自适应等于没做。断流恢复时只认水位。
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS &&
            PlaybackResumePolicy.idleTimeoutStarts(rebufferCount, prebufferBytes, startThresholdBytes)) {
            startPlayback(track)
        }
```

同时把 `writePcm` 里那段（`:1067-1072`）改成走同一个函数，两条路径就不会再次跑偏：

```kotlin
            if (!playbackStarted) {
                prebufferBytes += count
                if (PlaybackResumePolicy.writePathStarts(prebufferBytes, startThresholdBytes)) {
                    startPlayback(track)
                    DiagLog.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
```

> 这两个函数是**纯判定**，没有时钟、没有 Android 类型。Task 5 的 `PlaybackResumePolicyTest` 就是拿它们做的真实测试；`AudioRenderer` 自己不再承载任何未被测过的起播规则。

- **稳态**（`rebufferCount == 0`）：行为一字不变。
- **断流后**（`rebufferCount > 0`）：只按 `startThresholdBytes` 起播，升档才真的改变恢复动作。
- **开销**：一个整型比较。无分配、无锁、无线程、无等待。
- **为什么不放大 `BUFFER_TAIL_WAIT_NS`**：500 ms 已经和背景停顿（约 400 ms）几乎撞上，再放大就是给每一次停顿平白加延迟。

**(g) stats 行**（`:1113-1118`）：改成 (f) 里给出的版本（引用 `windowPackets`/`windowDropped`/`windowGapMs`/`windowWrites`，不再 `getAndSet`）。字段顺序与名字保持原样，只在末尾追加 `targetMs`/`startMs`/`fadeEvents`。

> 验收时用 `grep "rx=0 "` 扫一遍：**只要出现 `rx=0`，就是 (f) 的双重清零写错了。**

- [ ] **Step 4: 跑全量单测**

```bash
./gradlew :shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`，Task 1–4 的 34 个用例（5+16+8+5）+ 既有用例全过。此时**不要**打 APK，编译通过即可。

- [ ] **Step 5: 提交**

```bash
git add shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt \
        shared/src/main/java/com/shilapi/xcertplay/media/PlaybackResumePolicy.kt \
        shared/src/test/java/com/shilapi/xcertplay/media/PlaybackResumePolicyTest.kt \
        common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt
git commit -m "audio: let the measured link move the music start level"
```

---

### Task 5.5: 接线集成测试（Robolectric 影子栈，不需要车）

**为什么单独一个任务：** Task 5 只把「起播判定」抽出来测了，`AudioRenderer` 里真正把 `PcmSmoother`、`AdaptiveMediaBuffer`、`startThresholdBytes` 串起来的那部分仍然没有自动化验证。这一节补上，让「不用上车也能证明接线是通的」成立。

**已核实的可行性**（不是猜的，是 `javap` 过的 `shadows-framework-4.17.jar`）：

| 影子 | 能力 | 用来验证 |
|---|---|---|
| `ShadowMediaCodec.addDecoder(mime, CodecConfig)` | 可挂自定义 `Codec`，产出任意 PCM | 让 AAC 解码器吐出已知样本 |
| `ShadowAudioTrack.addAudioDataListener(listener)` | `onAudioDataWritten(track, bytes, format)` 每次写入都能拿到 | 断言写进去的字节、起播时机 |
| `ShadowAudioTrack.setMinBufferSize(int)` | 决定 `getMinBufferSize` 返回值 | 让容量可控 |

仓库里此前没有影子用法，所以本任务**自带全部脚手架**，执行者不需要再摸索。

**Files:**
- Create: `shared/src/test/java/com/shilapi/xcertplay/media/AudioRendererIntegrationTest.kt`

**Interfaces:**
- Consumes: `AndroidMediaSink.onAudioRtp(type, format, rtp, sample, arrivalGapMillis)`、`ShadowMediaCodec`、`ShadowAudioTrack`。
- Produces: 无生产代码改动。

- [ ] **Step 1: 写集成测试**

```kotlin
package com.shilapi.xcertplay.media

import android.media.MediaCodec
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodec.CodecConfig
import java.util.Collections

/**
 * The join between the adaptive level, the fade and the track — the part Task 5 could not reach.
 *
 * Every rule the plan cares about is already unit-tested on its own pure class. What is only provable
 * here is that [AudioRenderer] actually calls them, in the right order, with the right values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "en")
class AudioRendererIntegrationTest {

    /** Four frames of a loud, hard-edged signal: 32767 / 0 / 32767 / 0 on both channels. */
    private val loudPcm: ByteArray = ByteArray(4 * 4) { index ->
        val sample = if ((index / 2) % 2 == 0) 0xff else 0x00
        sample.toByte()
    }

    @After
    fun tearDown() {
        ShadowMediaCodec.clearCodecs()
        ShadowAudioTrack.resetTest()
    }

    private fun mediaFormat(channels: Int = 2) = AudioFormat(
        codec = AudioCodecKind.AAC_LC,
        sampleRate = 48_000,
        channels = channels,
        payloadType = 102,
        audioType = "media",
    )

    private fun rtpFor(payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header + payload
    }

    @Test
    fun aMusicStreamStartsWithAFadeAndAPreparedTrack() {
        val written = Collections.synchronizedList(mutableListOf<ByteArray>())
        ShadowAudioTrack.addAudioDataListener { _, bytes, _ -> written.add(bytes.copyOf()) }
        ShadowAudioTrack.setMinBufferSize(4_096)
        ShadowMediaCodec.addDecoder(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            CodecConfig(48_000, 2, CodecConfig.Codec { _, _, _, _ -> loudPcm }),
        )

        val sink = AndroidMediaSink(mediaBufferMillis = 500)
        try {
            sink.onAudioStarted(102, mediaFormat(), firstSample = 0)
            repeat(20) { sink.onAudioRtp(102, mediaFormat(), rtpFor(ByteArray(16)), sample = it * 1024, arrivalGapMillis = 21L) }
            Thread.sleep(300)

            assertTrue("音频必须真的写到 track 上", written.isNotEmpty())
            // 第一帧被淡入置零：这正是 PcmSmoother 在起作用。
            val first = written.first()
            assertTrue("首帧必须是静音，实际=${first.take(4).toList()}", first[0] == 0.toByte() && first[1] == 0.toByte())
        } finally {
            sink.close()
        }
    }

    @Test
    fun theTrackIsPreparedAtTheLevelThePlanAskedFor() {
        // AudioTrack 的容量由 createTrack 决定，之后不能改。这条断言的是「预留的容量对」，
        // 也就是升档有没有空间可用；不是断言升档本身（那在 AdaptiveMediaBufferTest 里）。
        ShadowAudioTrack.setMinBufferSize(4_096)
        ShadowAudioTrack.addAudioDataListener { _, _, _ -> }
        ShadowMediaCodec.addDecoder(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            CodecConfig(48_000, 2, CodecConfig.Codec { _, _, _, _ -> loudPcm }),
        )
        val capacities = Collections.synchronizedList(mutableListOf<Int>())
        ShadowAudioTrack.addAudioDataListener { track, _, _ -> capacities.add(track.bufferSizeInFrames * 4) }

        val sink = AndroidMediaSink(mediaBufferMillis = 500, audioLinkKind = AudioLinkKind.SAME_NETWORK)
        try {
            sink.onAudioStarted(102, mediaFormat(), firstSample = 0)
            repeat(20) { sink.onAudioRtp(102, mediaFormat(), rtpFor(ByteArray(16)), sample = it * 1024, arrivalGapMillis = 396L) }
            Thread.sleep(300)
            assertTrue("track 必须建立", capacities.isNotEmpty())
            // 48 kHz 立体声 = 192 000 字节/秒；容量应为 (500 + 200) ms = 134 400，且已按 700 ms 天花板预留。
            val expected = MediaAudioBuffer.plan("media", 48_000, 2, 4_096, mediaBufferMillis = 500)
            assertEquals(expected.trackBufferBytes, capacities.first())
        } finally {
            sink.close()
        }
    }
```

> **执行者注意**：`CodecConfig.Codec` 的 lambda 签名（输入/输出缓冲、`BufferInfo` 的填充方式）以 `javap org.robolectric.shadows.ShadowMediaCodec$CodecConfig$Codec` 的实际签名为准；上面的 `{ _, _, _, _ -> loudPcm }` 是形状示意，**接线时按真实签名补齐**。如果该影子在这个版本上对 AAC 支持不完整（`addDecoder` 但 `configure` 失败），放弃第二条用例、保留第一条，并在提交信息里写清楚为什么——**不要伪造断言来让它变绿**。

- [ ] **Step 2: 跑测试**

```bash
./gradlew :shared:testDebugUnitTest --tests com.shilapi.xcertplay.media.AudioRendererIntegrationTest
```

Expected: 至少 `aMusicStreamStartsWithAFadeAndAPreparedTrack` 通过。若因影子限制失败，按上面的注意处理。

- [ ] **Step 3: 提交**

```bash
git add shared/src/test/java/com/shilapi/xcertplay/media/AudioRendererIntegrationTest.kt
git commit -m "audio: prove the fade and the start level reach the track, without a car"
```

---

### Task 6: 设置项与持久化对齐 700 ms 天花板

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt:578-587`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:1054`
- Modify: `common/src/main/res/values/strings.xml:347`、`common/src/main/res/values-zh-rCN/strings.xml:346`
- Test: `common/src/test/java/com/shilapi/xcertplay/AirPlayPersistenceTest.kt`（不存在则新建，`common/src/test` 目前无 `AirPlayPersistenceTest`）

**Interfaces:**
- Consumes: `MediaAudioBuffer.presets = listOf(300, 500, 1000)`（**不改**）、`MediaAudioBuffer.sanitize`。
- Produces:
  - `AirPlayPersistence.loadMediaBufferMillis` 返回 `minOf(sanitize(raw), MEDIA_MILLIS_CEILING)`，旧存档里的 `1000` 自动收敛为 `700`。
  - 设置页第三档文案与取值的映射改为 `listOf(300, 500, 700)`，**取值表与文案表必须同长同序**。

- [ ] **Step 1: 写失败测试**

`DiPlayActivity.kt:1055` 现在把 `bufferPresets.indexOf(loadMediaBufferMillis(this))` 塞进 `choice(...)`，而 `bufferPresets` 是 `300/500/1000`。封顶后 `load` 返回 700，`indexOf(700) = -1` → `coerceAtLeast(0)` → 选中「300 ms」，**设置页会显示错的档位**。这就是本任务要暴露的 bug。

新建 `common/src/test/java/com/shilapi/xcertplay/AirPlayPersistenceTest.kt`：

```kotlin
package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class AirPlayPersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun clearStoredBuffer() {
        // AirPlayPersistence.PREFS 是私有的，这里是它的实际文件名；和其它持久化用例共用同一份，
        // 不清干净会互相污染（看看 ConnectionModePersistenceTest 的第一行就知道这是本仓库的约定）。
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
            .edit().remove("media_buffer_ms").commit()
    }

    @Before
    fun reset() = clearStoredBuffer()

    @Test
    fun `a stored 1000 is read back as the ceiling`() {
        AirPlayPersistence.saveMediaBufferMillis(context, 1_000)
        assertEquals(
            "旧的 1000 存档必须按 700 读回，否则设置页选不中任何一行",
            700,
            AirPlayPersistence.loadMediaBufferMillis(context),
        )
    }

    @Test
    fun `the settings choices still name every value it can read back`() {
        val choices = listOf(300, 500, 700)
        choices.forEach { choice ->
            AirPlayPersistence.saveMediaBufferMillis(context, choice)
            val loaded = AirPlayPersistence.loadMediaBufferMillis(context)
            assertEquals("choice $choice must be readable", choice, loaded)
            assert(choices.indexOf(loaded) >= 0) { "settings page has no row for $loaded" }
        }
    }
}
```

- [ ] **Step 2: 跑测试确认红**

```bash
./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.AirPlayPersistenceTest
```

Expected: `a stored 1000 is read back as the ceiling` 失败（得到 1000，期望 700）。

- [ ] **Step 3: 实现**

`AirPlayPersistence.kt`：

```kotlin
    fun loadMediaBufferMillis(context: Context): Int {
        val stored = com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS),
        )
        // Builds before the adaptive buffer stored 1000 here. 700 is the ceiling now, and reading it
        // back as 700 keeps the settings page on a row it actually offers.
        return minOf(stored, com.shilapi.xcertplay.media.MediaAudioBuffer.MEDIA_MILLIS_CEILING)
    }
```

`DiPlayActivity.kt:1054-1058`：

```kotlin
            val bufferPresets = listOf(
                com.shilapi.xcertplay.media.MediaAudioBuffer.presets.first(),
                com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS,
                com.shilapi.xcertplay.media.MediaAudioBuffer.MEDIA_MILLIS_CEILING,
            )
```

字符串：`values/strings.xml:347` 把 `s_1000_ms_most_stable` 改名 `s_700_ms_most_stable`，值 `700 ms · most stable`；`values-zh-rCN/strings.xml:346` 值 `700 毫秒 · 更稳定`。同步改 `DiPlayActivity` 里引用的 `R.string.s_1000_ms_most_stable`。

再在「音乐缓冲」行下面加一行说明（两个语言各一条）：

```xml
<!-- values/strings.xml -->
<string name="music_buffer_adapts">The buffer rises on its own when the link stalls, up to about one second, and settles back when it stops.</string>
<!-- values-zh-rCN/strings.xml -->
<string name="music_buffer_adapts">链路卡顿时缓冲会自动加大（最多约一秒），链路稳定后再回落。</string>
```

- [ ] **Step 4: 跑测试确认绿**

```bash
./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.AirPlayPersistenceTest
```

Expected: 2 个用例全过。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt \
        common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt \
        common/src/main/res/values/strings.xml \
        common/src/main/res/values-zh-rCN/strings.xml \
        common/src/test/java/com/shilapi/xcertplay/AirPlayPersistenceTest.kt
git commit -m "audio: offer 700 ms as the most-stable choice and read old 1000 ms back as it"
```

---

### Task 7: 回归 + 打包

**Files:** 无源码改动。

- [ ] **Step 1: 全量单测**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: 出吉利包**

```bash
DIPLAY_VENDOR_HOP_DIR=/Users/g/Documents/project/diplay-vendor-hop scripts/package-geely.sh local
ls -lh dist/DiPlay-Legacy-Geely-V0*.apk
```

Expected: 生成新的 `dist/DiPlay-Legacy-Geely-V0XX-local.apk`（版本号沿用脚本当前规则）。

- [ ] **Step 3: 确认没有顺手改动视频路径**

本任务的第一个提交之前的位置才是基线，不要用固定偏移量（每次提交数可能不同）：

```bash
BASE=$(git log --format=%H --grep="^audio: cap the music buffer" -1)
[ -n "$BASE" ] && git diff --stat "$BASE^..HEAD" -- shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt
[ -n "$BASE" ] && git diff "$BASE^..HEAD" -- shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt \
  | grep -E "^[+-].*(VideoDecoder|VideoDecodeQueue|videoFrame|requestKeyFrame|ScreenStream)"
```

Expected: 第二条命令**无输出**。

> 若 `BASE` 为空（提交信息被改过），改用 `git log --oneline` 找到 Task 1 那次提交，手动 `git diff <那次>^..HEAD`。不要退回 `HEAD~N`——提交数一数错就会把视频改动漏过去。

---

### Task 8: 车上验收

**前置：** 车机装 Task 7 的包；手机连同一热点或同一路由器；播放 Apple Music 30 分钟以上，中间至少一次经过隧道/地库等必然断流的位置。

- [ ] **Step 1: 取诊断报告（不是 `adb logcat`）**

前面的证据分析用的是 `DiPlay-20261020-*.txt`，**它不是你用 logcat 抓的**：那是 App 设置页「保存诊断报告」按钮产出的文件，文件名格式 `DiPlay-yyyyMMdd-HHmmss-SSS.txt`（`DiPlayActivity.kt:2937` `reportFileName()`），`DiagnosticExportStore.export` 负责写盘。它自带环形历史，所以一个文件里可能有多个会话。

所以验收按 App 自己的路径走：

1. 车机上把音乐播放跑到 ≥30 分钟（含一次过隧道/地库）
2. 打开 DiPlay 设置页 → 点「保存诊断报告」
3. 把生成的 `DiPlay-<时间戳>.txt` 拷回电脑，放到 `~/Downloads/xr/scratch_logs/`

如果手上只有 adb、需要**实时**看，那就抓原始 logcat（标签与报告里的前缀一致，但格式不同，`grep` 要按 `I/`、`W/` 前缀调整）：

```bash
adb logcat -d -v time -s xcertplay-usb DiPlay-AudioStats Receive | tail -n 5000 > ~/Downloads/xr/scratch_logs/audio-after-raw.txt
```

> 原始 logcat 里没有 `audio stats ...` 前面的两空格对齐，也没有报告头（版本、车型）。**以报告文件为准**，logcat 只用于实时盯。

- [ ] **Step 2: 逐条核对**

| 判据 | 期望 | 查法 |
|---|---|---|
| 稳态不误升 | 好窗口里 `targetMs` 始终 = 起播水位 | `grep "targetMs="` 全绿 |
| 升档有依据 | `targetMs` 变大的那个窗口里，`maxGapMs >= 500` 或 `rebuffers>0` 或 `maxWriteMs>=400` 至少一条成立；**`maxGapMs` 只有 380–450 的窗口绝不许升档** | `grep -B1 "targetMs=6\|targetMs=7"` |
| 封顶生效 | `targetMs` 永远 ≤ 700，`startMs` 永远 ≤ 700 | `grep -o "targetMs=[0-9]*" \| sort -u` |
| 断流后有淡入 | `fadeEvents` 在 `rebuffers>0` 的窗口里 +1 | `grep "rebuffers=[1-9]"` |
| 计数没被写坏 | 全程不出现 `rx=0 ` | `grep -c "rx=0 "` 应为 0 |
| 音画延迟可接受 | 起播延迟 ≤ 700 ms，暂停/切歌响应主观可接受 | 人耳 |

- [ ] **Step 3: 反向判据（不通过就回去改常量，不是改结构）**

- 若断流窗口（`maxGapMs >= 1000`）**没有**升档 → 先查 `rx=0` 是否存在（说明 (f) 清零写错），再查 `BACKGROUND_TAIL_MILLIS` 是不是被改大了。
- 若 380–450 ms 的背景停顿**触发**了升档 → 说明 `BACKGROUND_TAIL_MILLIS` 被改小了；恢复 500，**不要**为了「更灵敏」往 400 调。
- 若 `maxWriteMs` 常态超过 400 导致误升 → 把 `BLOCKING_WRITE_MILLIS` 提到 500，并在日志里附上这台车的实测常态值。
- 若 `fadeEvents` 增加但爆音仍存在 → **不要**加大淡入时长；先确认 `PcmSmoother` 的 `frameBytes` 与 `format.channels` 一致（立体声是 4，单声道是 2），错配会让淡入把 L/R 交错写坏，听起来比爆音更糟。

---

## 不在本计划里（别塞进来）

1. **重采样 / 时钟漂移校正。** 这要求一个持续音频时钟（CarPlay 的 RTP 时间戳）与一个本地播放时钟的比对，而本仓库的 `drainCodec` 丢弃了 `info.presentationTimeUs`（`AndroidMediaSink.kt:986-1027` 只取 `info.size`）。要做就得先把 PTS 一路带到 `writePcm`，那是另一个计划。
2. **`queuedBytes == 0` 欠载判据的替换。** 日志里 `rebuffers=0` 说明它从没触发过，但原因（`queue.isEmpty()` 与 `queuedBytes==0` 不同时成立）要先在车上用一次带额外日志的包确认。本计划用 `maxWriteMs` 与 `rebuffers` 绕开了它，没有删它。
3. **整机网络线程 400–1196 ms 停摆的根因。** `Receive: video readMaxMs=1196` 与 `Receive: audio readMaxMs=1143` 落在同一时刻，说明是 UDP 收包线程被整机调度/供电抢走。候选：`natp` 占核（2026-10-20 10:44 会话已讨论过 `setprop ctl.stop run_natp` 的可逆探针）、Wi-Fi 省电。这是**上游**问题，改好它对音频的影响比本计划大一个量级。
4. **通话 / Siri / 导航提示的低延迟缓冲。** 它们走 low-latency 分支，本计划的所有断言都要求这条分支数值不变。
5. **CarPlay 协商的 `audioLatencyMs`。** `CarPlayMediaEngine.kt:134` 已经解析它并塞进 `AudioMeta`，但全仓库**没有一处读**（`grep '\.latencyMs'` 无结果）。这是手机告诉车机「我按多久延迟出音」的权威值，理论上比从日志反推背景尾线更准。

   本计划不用它的理由：它是**手机侧的播放延迟**，不等于**链路抖动容忍度**。日志里的事实是「链路每 5 秒停约 400 ms，手机照常送包」——这种停顿 `audioLatencyMs` 预测不到，只有实测 `maxGapMs` 能反映。用它当基线只会得到一个与实测无关的数字。

   但它不该继续烂在 `AudioMeta` 里：下一版若要做「按手机协商值预置起点、再用实测修正」，就从这条线接。

---

## 一个已知缺口，写在这里免得后面返工

当 `rebufferCount` **不增长**、而缺包后声音被写阻塞拖慢（日志里 `maxWriteMs=358` 的窗口紧挨着 `seqForwardGaps=36` 的窗口）时，`AdaptiveMediaBuffer` 只能靠 `maxGapMs >= 500` 这一个信号升档——而 1144 ms 的窗口本身一定满足它，所以这一条在日志里是成立的。真正的风险在另一头：如果车上验收发现**背景停顿变大了**（比如路由器换了、信道拥挤到常态 600 ms），那么 `BACKGROUND_TAIL_MILLIS=500` 会把新的常态判成抖动，每赛季都往上爬。那种情况**改常量**（把背景尾抬高到实测值），不要改结构，更不要把 `queuedBytes` 判据顺手塞进来——那需要先解决第 2 条缺口。
