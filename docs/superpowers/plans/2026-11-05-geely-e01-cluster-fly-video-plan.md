# 星瑞 E01：三指后仪表底层显示 CarPlay

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 三指向左滑时，仪表切到地图页，最下面一层播放 CarPlay 的画面，车速挡位仍由仪表自己画在上面；向右滑时停掉这路画面并盖上这一层。

**Architecture:** 换页走原厂两条广播，由车机 Extraservice 发给仪表。画面按高德 `HeartBeatServer`：DiPlay 绑 `10.255.255.101:1028`（这台车的高德不绑这个口），应答仪表的 24 字节功能号 301，再把中控正在播的那一路 H.264 用 RTP 推到 `10.255.255.106:9026`。安全门在发送前挡住已知的危险状态，并在危险状态中途关掉推流。日志只记状态和计数，不记画面内容。推流默认转发已有码流；只有确认不是 Baseline 时才重编，而且只在这一层打开时工作。

**Tech Stack:** Kotlin，Android 5.1 / API 22，JUnit4，现有 `DiagLog`，现有 `AndroidMediaSink.onVideoFrame`。

**Spec:** 本文件就是规格。不要执行这三份已作废的计划：`2026-11-05-geely-e01-cluster-overlay-plan.md`、`2026-11-05-geely-e01-cluster-flyscreen-implementation-plan.md`、`2026-11-05-geely-e01-cluster-projection-xcnaviservice-plan.md`。

## Global Constraints

- 最低 API 是 22。不用 `MediaCodec.setOutputSurface`。不向手机要第二路画面（流 111）。
- 不改 `/system`，不替换 `Navi.apk`、`XCNaviService`、`Extraservice`。不用 `DimInteraction`，不建 `VirtualDisplay`，不写 `/dev/graphics/fb0` 或 `fb2`，不在中控上画白框。
- 不停止空调、方向盘按键、仪表服务、360、桌面、SystemUI、蓝牙、T-Box、ECARX。不停 `com.autonavi.amapauto`。
- 打开顺序固定：先 `EXTRA_STATE=8`，等 400 毫秒，再 `direction=left`。盖上顺序固定：先 `direction=right`，等 400 毫秒，再 `EXTRA_STATE=9`。先关导航会导致向右滑被原厂丢掉。
- 任意两条给仪表的指令至少隔 1000 毫秒。不要循环补发。
- `direction` 只能是 `left` 或 `right`。往下三指仍是现在的快捷栏。
- 倒车、雷达、紧急电话时不发换页指令。这些状态若在播放中途出现，立刻停止推流；换页关闭指令若会被原厂丢掉，就只停本地推流并记日志。
- 日志不写 NAL、不写 RTP 包体、不写手机地址。
- 每个任务都有能在电脑上跑的 JUnit。车上验收只在最后一节。

---

## 文件

- 改 `common/src/main/java/com/shilapi/xcertplay/ui/ThreeFingerSwipeDetector.kt`：增加向左、向右，往下不变。
- 新建 `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyGate.kt`：安全门。
- 新建 `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySequencer.kt`：换页顺序和间隔。
- 新建 `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySession.kt`：画面这一层的状态。
- 新建 `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyProtocol.kt`：24 字节口令和应答。
- 新建 `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyPush.kt`：转发或重编的策略。
- 新建 `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLog.kt`：埋点名称。
- 新建 `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyWarning.kt`：给驾驶员的一句提示。
- 改 `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`：手势接到会话；提示用一行字，不挡画面。
- 改 `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`：仅在有订阅者时，把主画面（类型 110）的 NAL 交出去。
- 测试与上面每个新类同名，放在对应的 `src/test`。

---

### Task 1: 三指认左右

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/ui/ThreeFingerSwipeDetector.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/ui/ThreeFingerSwipeDetectorTest.kt`

**Interfaces:**
- Consumes: 现有 `onTouch(phase, pointerCount, eventTime, centroidX, centroidY): Decision`
- Produces: `Decision.FLY_LEFT`、`Decision.FLY_RIGHT`。`Decision.TRIGGER` 仍是往下，现有测试不改语义。

往下的条件保持现在的写法：向下位移不小于 `distance`，并且不小于横向位移乘 `directionRatio`。向左是横向位移为负、绝对值达到同样门槛，并且横向不小于向下绝对值乘 `directionRatio`。向右是横向为正的对称情况。一条手势只触发一次，之后进入 `DRAINING`。

- [ ] **Step 1: 写会失败的测试**

在 `ThreeFingerSwipeDetectorTest` 末尾加上：

```kotlin
@Test
fun `three fingers moving left fly left and do not open the quick bar`() {
    threeFingers()
    assertEquals(Decision.FLY_LEFT, detector.onTouch(Phase.MOVE, 3, 120L, 800f, 400f))
}

@Test
fun `three fingers moving right fly right`() {
    threeFingers()
    assertEquals(Decision.FLY_RIGHT, detector.onTouch(Phase.MOVE, 3, 120L, 1000f, 400f))
}

@Test
fun `downward swipe still opens the quick bar`() {
    threeFingers()
    assertEquals(Decision.TRIGGER, detector.onTouch(Phase.MOVE, 3, 120L, 900f, 520f))
}
```

- [ ] **Step 2: 跑测试，确认新用例失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.ui.ThreeFingerSwipeDetectorTest`

Expected: 左滑、右滑失败，因为现在只返回 `CONSUME` 或 `TRIGGER`。

- [ ] **Step 3: 实现**

把 `travelledFarEnough` 拆开。往下仍返回 `TRIGGER`。横向达标且比上下更明显时，向左返回 `FLY_LEFT`，向右返回 `FLY_RIGHT`。斜着、没过距离，仍是 `CONSUME`。

- [ ] **Step 4: 再跑，全部通过**

Run: 同上。

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/ThreeFingerSwipeDetector.kt \
  common/src/test/java/com/shilapi/xcertplay/ui/ThreeFingerSwipeDetectorTest.kt
git commit -m "feat: recognize left and right three-finger fly gestures"
```

---

### Task 2: 安全门和一句预警

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyGate.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyWarning.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyGateTest.kt`

**Interfaces:**
- Consumes: 手势方向 `FlyDirection`（`LEFT`、`RIGHT`）
- Produces:

```kotlin
enum class FlyBlock {
    NONE, NOT_NAVIGATING, NOT_PROVISIONED, REVERSING, RADAR, ECALL, TOO_SOON
}

data class FlyConditions(
    val navigationArmed: Boolean,
    val deviceProvisioned: Boolean,
    val reversing: Boolean,
    val radar: Boolean,
    val ecall: Boolean,
    val millisSinceLastClusterCommand: Long,
)

fun ClusterFlyGate.decide(conditions: FlyConditions): FlyBlock

fun ClusterFlyWarning.text(block: FlyBlock): String?
```

判断顺序固定，命中即返回：间隔小于 1000 毫秒 → `TOO_SOON`；未导航 → `NOT_NAVIGATING`；未完成设置 → `NOT_PROVISIONED`；倒车或全景 → `REVERSING`；雷达 → `RADAR`；紧急电话 → `ECALL`；否则 `NONE`。打开和关闭用同一套门。原厂在雷达、倒车、紧急电话时连「盖上」也会丢掉，所以这些状态下关闭也返回挡住，调用方改为只停本地推流。

预警文案：

| 原因 | 文案 |
| --- | --- |
| `NOT_NAVIGATING` | 还没进入导航，仪表不会换页 |
| `NOT_PROVISIONED` | 车机还没设置完，仪表不会换页 |
| `REVERSING` | 倒车或全景时不能飞屏 |
| `RADAR` | 雷达报警时不能飞屏 |
| `ECALL` | 紧急电话时不能飞屏 |
| `TOO_SOON` | 刚才那次还没完成，请稍等 |
| `NONE` | 不提示 |

DiPlay 能自己知道的只有：自己有没有发过导航状态、离上次指令多久、以及下面两条公开广播。

- `com.neusoft.radarDefanceView`，`state == 1` 是雷达开，其他值是关。
- `com.neusoft.ecallForThreeFinger`，`CallType == ECall` 且 `CallStatus` 为 `call data sending` 或 `incoming call` 是紧急电话开；`call end` 或 `call failed` 是关。

倒车、360、自动泊车、盲区没有给普通应用的广播，原厂用的是系统的 `RvcManager`。DiPlay 看不到时，`reversing` 保持 false，指令照发，由原厂丢掉。日志里记「已发送，倒车状态未知」。不要为了读倒车去提权。

- [ ] **Step 1: 写会失败的测试**

```kotlin
@Test
fun `radar blocks even when navigation is armed`() {
    val block = ClusterFlyGate.decide(openConditions(radar = true))
    assertEquals(FlyBlock.RADAR, block)
    assertEquals("雷达报警时不能飞屏", ClusterFlyWarning.text(block))
}

@Test
fun `a second command inside one second is too soon`() {
    assertEquals(
        FlyBlock.TOO_SOON,
        ClusterFlyGate.decide(openConditions(millisSinceLastClusterCommand = 999)),
    )
}

@Test
fun `clear conditions pass`() {
    assertEquals(FlyBlock.NONE, ClusterFlyGate.decide(openConditions()))
    assertEquals(null, ClusterFlyWarning.text(FlyBlock.NONE))
}
```

`openConditions` 的默认值是：正在导航、已设置完、不倒车、无雷达、无紧急电话、离上次指令 1000 毫秒。

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyGateTest`

Expected: 类不存在。

- [ ] **Step 3: 实现上面的判断和文案**

- [ ] **Step 4: 再跑，通过**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: block cluster fly during radar, ecall, and command bursts"
```

---

### Task 3: 换页顺序

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySequencer.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySequencerTest.kt`

**Interfaces:**
- Consumes: `ClusterFlyGate.decide`，`FlyDirection`
- Produces:

```kotlin
enum class FlyCommand { NAV_ON, FLY_LEFT, FLY_RIGHT, NAV_OFF }

data class FlyStep(val command: FlyCommand, val sendAtMillis: Long)

fun ClusterFlySequencer.open(nowMillis: Long, conditions: FlyConditions): List<FlyStep>
fun ClusterFlySequencer.close(nowMillis: Long, conditions: FlyConditions): List<FlyStep>
```

打开在门通过时返回两步：`now` 发 `NAV_ON`，`now + 400` 发 `FLY_LEFT`。盖上返回：`now` 发 `FLY_RIGHT`，`now + 400` 发 `NAV_OFF`。门不通过时返回空列表。调用方没真正发出之前，不能把「上次指令时间」往前拨。

广播内容：

- `NAV_ON`：`AUTONAVI_STANDARD_BROADCAST_SEND`，`KEY_TYPE=10019`，`EXTRA_STATE=8`
- `NAV_OFF`：同一 action，`EXTRA_STATE=9`
- `FLY_LEFT` / `FLY_RIGHT`：`com.neusoft.alfus.three_finger`，`direction` 为 `left` 或 `right`

- [ ] **Step 1: 写会失败的测试**

```kotlin
@Test
fun `open sends navigation before the left swipe`() {
    val steps = ClusterFlySequencer.open(1_000L, openConditions())
    assertEquals(listOf(FlyStep(FlyCommand.NAV_ON, 1_000L), FlyStep(FlyCommand.FLY_LEFT, 1_400L)), steps)
}

@Test
fun `close keeps navigation until after the right swipe`() {
    val steps = ClusterFlySequencer.close(5_000L, openConditions(millisSinceLastClusterCommand = 5_000))
    assertEquals(FlyCommand.FLY_RIGHT, steps[0].command)
    assertEquals(FlyCommand.NAV_OFF, steps[1].command)
    assertEquals(400L, steps[1].sendAtMillis - steps[0].sendAtMillis)
}

@Test
fun `ecall produces no command`() {
    assertEquals(emptyList<FlyStep>(), ClusterFlySequencer.open(0L, openConditions(ecall = true)))
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlySequencerTest`

- [ ] **Step 3: 实现**

- [ ] **Step 4: 再跑，通过**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: order cluster fly commands the way the factory expects"
```

---

### Task 4: 画面这一层的状态

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySession.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySessionTest.kt`

**Interfaces:**
- Consumes: `FlyStep`，仪表口令 `VideoAsk.OPEN` / `VideoAsk.CLOSE`（Task 5 的解析结果。本任务用枚举，不解析字节）
- Produces:

```kotlin
enum class FlyPhase { IDLE, ARMING, WAITING_FOR_VIDEO, PUSHING, CLOSING }

data class FlyEffect(
    val phase: FlyPhase,
    val push: Boolean,
    val warning: FlyBlock?,
    val steps: List<FlyStep>,
)

class ClusterFlySession {
    fun onLeft(nowMillis: Long, conditions: FlyConditions): FlyEffect
    fun onRight(nowMillis: Long, conditions: FlyConditions): FlyEffect
    fun onVideoAsk(ask: VideoAsk, nowMillis: Long): FlyEffect
    fun onWaitTimeout(nowMillis: Long): FlyEffect
    fun onSafety(conditions: FlyConditions, nowMillis: Long): FlyEffect
    fun onCarPlayEnded(nowMillis: Long, conditions: FlyConditions): FlyEffect
}
```

状态只允许这样走：

- `IDLE` 加向左，门通过 → `ARMING`，带上打开的两步，`push=false`。
- `ARMING` 在左滑那一步的时间到了之后 → `WAITING_FOR_VIDEO`。没到时间不推流。
- `WAITING_FOR_VIDEO` 收到 `OPEN` → `PUSHING`，`push=true`。
- 等待超过 3000 毫秒仍没有 `OPEN` → 留在 `WAITING_FOR_VIDEO`，`push` 保持 false。不启动编码器，不复制中控屏幕。
- `PUSHING` 收到 `CLOSE`，或向右且门通过 → `CLOSING`，`push=false`，带上盖上的两步。
- 播放中雷达、倒车或紧急电话变为真 → `CLOSING`，`push=false`，`steps` 为空（原厂会丢掉盖上指令），`warning` 为对应原因。
- CarPlay 会话结束：若在 `PUSHING` 或 `WAITING_FOR_VIDEO`，按向右处理。
- `CLOSING` 在 `NAV_OFF` 的时间到了之后 → `IDLE`。

同一时刻 `push` 为 true 只有 `PUSHING`。重复向左在已经 `PUSHING` 时不再发指令。

- [ ] **Step 1: 写会失败的测试**

覆盖：向左后 400 毫秒内不推流；收到打开才推；3 秒没人要视频则不推；播放中雷达响起则停推且不发盖上指令；向右先停推再发盖上。

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlySessionTest`

- [ ] **Step 3: 实现状态机。不要在这里开线程或套接字。**

- [ ] **Step 4: 再跑，通过**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: keep cluster video off until the cluster asks for it"
```

---

### Task 5: 仪表口令

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyProtocol.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyProtocolTest.kt`

**Interfaces:**
- Consumes: 仪表发来的 `ByteArray`
- Produces:

```kotlin
enum class VideoAsk { OPEN, CLOSE, HEARTBEAT, IGNORE }

fun ClusterFlyProtocol.parse(packet: ByteArray): VideoAsk
fun ClusterFlyProtocol.heartbeat(): ByteArray
fun ClusterFlyProtocol.videoReady(): ByteArray
fun ClusterFlyProtocol.videoStart(): ByteArray
fun ClusterFlyProtocol.videoStop(): ByteArray
fun ClusterFlyProtocol.videoRefused(): ByteArray
```

解析：长度不是 24 就 `IGNORE`。功能号是第 4、5 字节的大端整数，开关是第 23 字节。301 且开关 0 → `OPEN`。301 且开关 1 → `CLOSE`。303 → `HEARTBEAT`。其他 → `IGNORE`。

应答按原厂 `lk.java` 的 20 字节头加 4 字节状态。头是：`03 F0`、`00 01`、功能号 2 字节、随后 2 字节、`00 00`、`5A A5`、4 字节 0、4 字节长度。

- 心跳：功能号 `00 64`，随后 `01 57`，长度为 0，整包 20 字节。连接期间每 3000 毫秒发一次。
- 准备好了：功能号 `01 2D`，随后 `02 53`，长度 4，状态最后一字节为 2。
- 拒绝：同一包，状态最后一字节为 0。还没进入 `WAITING_FOR_VIDEO` 或 `PUSHING` 时用这个，并且不推流。
- 开始播：功能号 `01 2F`，随后 `04 08`，长度 4，状态最后一字节为 0。
- 停止：同一包，状态最后一字节为 1。

收到 `OPEN` 且会话允许时，先发「准备好了」，再发「开始播」，然后才把 `push` 变成 true。收到 `CLOSE` 时先把 `push` 变成 false，再发「停止」。

套接字放在 `ClusterFlyLink`，只做三件事：绑 `10.255.255.101` 的 TCP 1028（和高德 `HeartBeatServer` 同一个地址，不要只绑 `0.0.0.0`）；把收到的字节交给 `parse`；把应答写回这根连接。UDP 不占用 1028 的 TCP。听失败（端口被占）记一条日志并保持 `WAITING_FOR_VIDEO`，不重试到把 CPU 打满：每 3000 毫秒试一次，最多记第一次和每 10 次。

- [ ] **Step 1: 写会失败的测试**

用手写的 24 字节样本断言 `OPEN`、`CLOSE`、短包、以及四个应答的长度和最后一字节。

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyProtocolTest`

- [ ] **Step 3: 实现解析和组包。测试不打开真套接字。**

- [ ] **Step 4: 再跑，通过**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: speak the factory cluster video handshake"
```

---

### Task 6: 推流优化

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyPush.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt` 的 `onVideoFrame`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyPushTest.kt`

**Interfaces:**
- Consumes: 主画面类型 110 的一帧 `naluBytes`，`FlyPhase`
- Produces:

```kotlin
enum class PushMode { FORWARD, REENCODE }

data class PushDecision(
    val send: Boolean,
    val mode: PushMode,
    val dropReason: String?,
)

fun ClusterFlyPush.onFrame(
    phase: FlyPhase,
    screenType: Int,
    nalu: ByteArray,
    nowMillis: Long,
): PushDecision
```

规则：

- `phase` 不是 `PUSHING`，或 `screenType` 不是 110：`send=false`，原因 `idle`。不碰流 111。
- `PUSHING` 且这帧的 SPS `profile_idc` 是 66（Baseline）：`FORWARD`，`send=true`。
- SPS 不是 66：这一帧丢掉，`mode=REENCODE`，原因 `profile`。从下一帧起由重编器出 Baseline，不再把原帧送出。重编参数照原厂：宽按 16 对齐，高为偶数，码率 2_000_000，关键帧间隔 1 秒。帧率上限 10。若最近一帧的编码耗时超过 100 毫秒，上限降到 5，直到连续 3 秒都低于 80 毫秒再回到 10。
- 重编器只在第一次遇到非 Baseline 时创建，离开 `PUSHING` 时释放。
- `AndroidMediaSink.onVideoFrame` 在 `submit` 之前，若订阅者非空且类型是 110，把同一份字节交给订阅者。没有飞屏时订阅者是空的，主路解码不增加拷贝以外的工作；拷贝只在 `PUSHING` 时做。
- RTP 目标是 `10.255.255.106` 端口 9026。打不成包的帧计一次丢弃，不抛到主线程。

- [ ] **Step 1: 写会失败的测试**

```kotlin
@Test
fun `main baseline frame is forwarded only while pushing`() {
    val sps = byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 0)
    assertEquals(false, ClusterFlyPush.onFrame(FlyPhase.WAITING_FOR_VIDEO, 110, sps, 0L).send)
    val live = ClusterFlyPush.onFrame(FlyPhase.PUSHING, 110, sps, 0L)
    assertEquals(true, live.send)
    assertEquals(PushMode.FORWARD, live.mode)
}

@Test
fun `high profile is not forwarded`() {
    val sps = byteArrayOf(0, 0, 0, 1, 0x67, 100, 0, 0)
    val decision = ClusterFlyPush.onFrame(FlyPhase.PUSHING, 110, sps, 0L)
    assertEquals(false, decision.send)
    assertEquals(PushMode.REENCODE, decision.mode)
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyPushTest`

- [ ] **Step 3: 实现策略。重编器的 MediaCodec 调用放在类的边界后面，测试用一个假的重编器，只断言「该不该送」。**

- [ ] **Step 4: 再跑，通过**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: forward the CarPlay picture and reencode only when it is not Baseline"
```

---

### Task 7: 日志埋点

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLog.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLogTest.kt`

**Interfaces:**
- Consumes: 上面各任务的枚举
- Produces: 一行字符串，调用方用 `DiagLog.i("xcertplay-fly", line)` 写入现有会话日志

每条线以固定前缀开头，字段用空格分开：

- `fly.gesture left|right|down`
- `fly.blocked NOT_NAVIGATING|NOT_PROVISIONED|REVERSING|RADAR|ECALL|TOO_SOON`
- `fly.command NAV_ON|FLY_LEFT|FLY_RIGHT|NAV_OFF`
- `fly.phase IDLE|ARMING|WAITING_FOR_VIDEO|PUSHING|CLOSING`
- `fly.tcp listen|accept|closed|busy`
- `fly.ask OPEN|CLOSE|HEARTBEAT|IGNORE`
- `fly.reply heartbeat|ready|start|stop|refused`
- `fly.push start forward|reencode`
- `fly.push stop`
- `fly.stat packets=<n> bytes=<n> drops=<n> fps=<n> keyframeAgeMs=<n>`
- `fly.safety on RADAR|REVERSING|ECALL` 和 `fly.safety off`

`fly.stat` 每 5 秒一条，且只在 `PUSHING`。`fly.tcp busy` 只在第一次和之后每 10 次失败时记。测试断言这些字符串，不读 logcat。

- [ ] **Step 1: 写会失败的测试**

至少断言：挡住原因原样出现、统计行不含字节数组、阶段变化各一条。

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyLogTest`

- [ ] **Step 3: 实现。在会话、协议、推流的状态变化处各调用一次，不在每帧视频上调用。**

- [ ] **Step 4: 再跑，通过**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: log cluster fly decisions without video payloads"
```

---

### Task 8: 接到中控画面上

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` 的 `onHostTouch`
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt`
- Test: 控制器用 Task 2 到 Task 7 的纯函数，不再单测 Activity。

**Interfaces:**
- Consumes: `Decision.FLY_LEFT`、`Decision.FLY_RIGHT`、`ClusterFlySession`、`ClusterFlyLink`、`ClusterFlyPush`、`ClusterFlyWarning`
- Produces: 一行提示。往下的 `TRIGGER` 仍打开现在的快捷栏。

控制器在主线程收手势，在自己的线程听 1028 和写 RTP。主线程只收「该提示什么」和「该不该订阅主画面」。提示用现有快捷条附近的一行字，2 秒后消失，不新开页面，不盖住 CarPlay。

`FLY_LEFT` 时调用 `session.onLeft`。有 `warning` 就显示文案，不发广播。有 `steps` 就按 `sendAtMillis` 用一个 `Handler` 发出，每发一条记 `fly.command`。进入 `WAITING_FOR_VIDEO` 后确保 `ClusterFlyLink` 在听。收到仪表口令后走会话，允许推流时才向 `AndroidMediaSink` 订阅类型 110。

Activity `onDestroy` 和 CarPlay 会话结束都调用 `session.onCarPlayEnded`，然后取消订阅、关掉套接字、释放重编器。

- [ ] **Step 1: 把 `onHostTouch` 里的 `TRIGGER` 分支留住，旁边加上左右分支，只转给控制器。**

- [ ] **Step 2: 控制器的发送用一个可以替换的 `FlyCommandSink`，单元测试里断言发出的 action 和 extra，不发真广播。**

- [ ] **Step 3: 跑这一组和之前的飞屏测试。**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFly* --tests com.shilapi.xcertplay.ui.ThreeFingerSwipeDetectorTest`

Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: wire three-finger fly to the cluster video session"
```

---

## 车上怎么验收

电脑上的测试证明不了仪表会不会要视频。装到星瑞上，CarPlay 已经在中控显示之后：

1. 停车、不倒车、无雷达、无紧急电话。三指向左。会话日志里应有 `fly.command NAV_ON`、`fly.command FLY_LEFT`，仪表切到地图页，车速还在。
2. 若接着出现 `fly.ask OPEN` 和 `fly.push start`，最下面应是 CarPlay。若 3 秒内没有 `fly.ask OPEN`，最下面不会被我们填上。这时停，不要改去复制中控画面。
3. 三指向右。日志有 `FLY_RIGHT` 然后 `NAV_OFF`，推流停止，仪表盖上这一层，中控的 CarPlay 还在。
4. 播放中触发雷达或紧急电话的广播。提示出现，日志是 `fly.safety on` 和 `fly.push stop`，不再有 RTP。
5. 一秒钟内连滑两次。第二次是 `fly.blocked TOO_SOON`，没有第二套指令。

## 明确不做

- 不把中控画面拷进 `fb2`。
- 不造第二块安卓屏，不改高德，不改导航服务。
- 不向手机要仪表专用的第二路视频。
- 不在中控上画一块给仪表去照的区域。
