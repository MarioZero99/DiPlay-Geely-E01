# 星瑞 E01：仪表 Presentation 投屏实施计划

> **2026-11-23 起，出画以 `2026-11-23-cluster-texture-mirror.md` 为准。** 软解出画作废。1885、displayId 1、Presentation、禁止 1028 和流 111 仍然有效。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 三指左滑把正在中控上解码的 CarPlay 画面挂到仪表那块 Android 屏上。不再往 `tbox0` 加地址，不再把 `1028` 上的连接当成投屏成功。

**Architecture:** 原厂投屏链路已经在车上看过。高德把两块 `SurfaceView` 画进 `displayId=1`（HDMI 屏幕，1920×720，`layerStack=1`）。中控的 `MainMapActivity` 留在 `displayId=0`。不走 `1028`，也不镜像整块中控屏。DiPlay 同样往 `displayId=1` 挂自己的画面，像素用中控正在解码的那一路。签名白名单只挡 `DimInteraction.create()`，不挡往这块屏上画。所以不调用 `DimInteraction`，也不发 `DIM_PRESENTATION_CTRL`。`1028`、给网卡加地址、RTP、重编码器，以及比亚迪仪表投屏（流 111），从生产代码和测试里删掉，不留一条不调用的死链路。本项目不兼容比亚迪。

**Tech Stack:** Kotlin，Android 5.1 / API 22，JUnit4，Robolectric 4.17，`DiagLog`，现有 `ClusterFlyController` 与 `AndroidMediaSink.setMainVideoObserver`。

**Spec:** 本文件即为规格。依据是固件 `Navi.apk`、`XCNaviService` 的 `NaviDashboardManager`、`Extraservice` 的 `BaiduScreenTestReceiver`，以及日志 `dist/supabase-logs/DiPlay-20261119-180129-927.txt`。

---

## Global Constraints

- 最低 API 级别为 22。禁止 `PixelCopy`、`Display.getMode` 和其他 API 23 以上的接口。显示器尺寸用 `Display.getRealSize`。
- 不修改 `/system`，不改 `Navi.apk`、`XCNaviService`、`Extraservice`。
- 不调用 `DimInteraction.create()`，不调用 `setPresentationToDimSwitch`，不发 `ecarx.intent.action.DIM_PRESENTATION_CTRL`。这三样都不是像素通路。白名单见下一节。
- 本项目不兼容比亚迪。删掉比亚迪仪表投屏，而不是留着不调用。包括 `ClusterMapPresentation`、`DiLink51ClusterLayout`、`DiLink51ClusterMonitor`、向手机要流 111 的设置项。见 Task 5。
- 不向手机请求 `SCREEN_TYPE_ALT`（流 111）。`AirPlayConfig.cluster` 保持 null。不调用 `sink.setSurface(SCREEN_TYPE_ALT, …)`。
- 删掉 `1028` 链路，而不是留着不调用。生产代码里不再有 `linkFactory`、`ClusterFlyLink`、`FlyClusterAddress`、`FlyNet`、`FlyPortHolder`、`ClusterFlyProtocol`、`ClusterFlyPush`、`ClusterFlyReencoderMediaCodec`。对应单测一起删。见 Task 4。
- 性能约束见下一节。软件解码器直接画到 `Presentation` 的 `Surface`。不读回 YUV，不再做第二遍编码，也不向手机要第二路画面。
- 1885 仍由 `ClusterFlyMcu.send1885` 直发。左滑 `[74, 1]`，右滑 `[74, 2]`。它只负责掀开仪表上的投屏层，不负责画像素。打开顺序保持现有的 `NAV_ON`、400 ms 后 `NAV_FRONT`、再 1000 ms 后 `FLY_LEFT`。
- 不发 `DIM_PRESENTATION_CTRL`，不创建 `FlyPresentationSignal`。这则广播在 `isNaviStart == false` 时会变成 1885 `[74, 0]`，盖掉左滑的 `[74, 1]`。
- 挂屏和拆屏不走 `NAV_ON` / `NAV_OFF` 广播。`show()` 和 `dismiss()` 只在主线程调用。`FLY_LEFT` 的 `sendStep` 已经由 `Handler(Looper.getMainLooper())` 派发，回调里直接 `show()`，不要再 `post` 一次。
- 3 秒收回的触发条件是「解码器还没有 `start`」。`surfaceCreated` 且解码器 `start` 成功之后才进入 `PUSHING` 并取消这次收回。`show()` 成功但 Surface 还没到，相位保持 `WAITING_FOR_VIDEO`。Binder 返回 true 仍然不是成功。
- `Presentation` 能被这块车机收下，以及 `OMX.google.h264.decoder` 能解开 profile 100，都还没有在这台车上证实。失败时记 `fly.display show failed` 或 `fly.display decoder failed`，拆屏，不要改回 MTK 硬解或 `1028`。
- 每个代码任务都先写会失败的测试，再写实现。

## 白名单

像素通路没有包名白名单，也没有签名白名单。

2026-11-20 10:49，高德中控投屏开着。`displayId=1` 上的两层 `SurfaceView` 生产者是 `com.autonavi.amapauto`，uid `u0_a32`，安装路径 `/data/app/Navi.apk`。它不是系统签名进程。`dumpsys window` 里这块屏没有 Activity 白名单，`FLAG_PRESENTATION` 开着。DiPlay 用 `Presentation` 挂到同一块 `displayId` 上，走的是同一条 SurfaceFlinger 通路。

签名白名单只在另一条路上：`DimInteraction.create()` 先调 `SignVerify.verifyWhiteApp`（`libsigner_verify.so`）。不过就返回 null。`setPresentationToDimSwitch` 是这条路上的调用，旧版导航服务还把包名写死成 `com.autonavi.amapauto`。高德投屏时 `XCNaviService` 4.1.3 打的是 `supportDim:true` 和转向信息，像素不是它画的。DiPlay 不走这条。

`BaiduScreenTestReceiver` 收 `DIM_PRESENTATION_CTRL` 时不查包名，但 `isNaviStart == false` 会把打开收成 1885 `[74, 0]`。这一笔会盖掉我们自己的 `[74, 1]`。所以计划里不发这则广播。

## 性能

这块车机是 MT6735。中控已经占用 `OMX.MTK.VIDEO.DECODER.AVC`，画面大约 20 fps，H.264 profile 100，1920×720。再开一个硬解会把中控卡住或变黑。飞屏只用 `OMX.google.h264.decoder`。这个名字不存在、`configure` 失败或运行中抛错，记 `fly.display decoder failed`。不要退回 MTK 解码器。这块固件上的软解能不能解 profile 100，第一版车上看这行日志。

解码器的输出就是 `Presentation` 上那块 `Surface`。禁止 `getOutputImage` 和 `getOutputBuffer`。每送入一个访问单元，就要用超时 0 把输出排空，并且 `releaseOutputBuffer(index, true)`。只送输入不释放输出时，`dequeueInputBuffer` 会一直返回 -1，解码器卡死。`queueInputBuffer` 的 `presentationTimeUs` 从 0 起，每帧加 50000，必须单调递增。旧重编码器把 YUV 读回内存再编码，这一版不做第二遍编码。

不能按 5 fps 抽帧。旧的 200 ms 间隔是编码节流。这一路是 P 帧参考前面的帧，丢掉中间帧再送，仪表上是花屏。保护中控用下面四条，150 ms 沿用现在重编码器在这块芯片上用过的预算：

- 飞屏线程是 `cluster-fly-frames`，优先级 `THREAD_PRIORITY_BACKGROUND`。中控的媒体线程只做一次入队。
- 队列容量保持 1。满了就丢，并记住要等到下一个 IDR。媒体线程不等待。
- `dequeueInputBuffer` 和 `dequeueOutputBuffer` 的超时都是 0。飞屏线程不堵在解码器上。
- 处理一个访问单元超过 150 ms：`flush` 后再 `start`，等到下一个 IDR。SPS/PPS 仍然送入。仪表画面停在上一个完整关键帧，直到下一个 IDR。

解码器只在 `Surface` 还在时存在。收回、右滑、解码失败和 `onDestroy` 都在主线程 `dismiss()`，在飞屏线程 `stop` 再 `release`。窗口加 `FLAG_NOT_FOCUSABLE` 和 `FLAG_NOT_TOUCHABLE`，避免仪表窗口抢走中控焦点。

`csd-0` / `csd-1` 是 avcC 里的 SPS/PPS 原文，不带起始码。`AndroidMediaSink.lastMainConfig` 在订阅前就能读到。Surface 到了用它配置解码器；还没有配置就保持 `WAITING_FOR_VIDEO`，等 3 秒收回。解码器 `start` 成功后才订阅，订阅时 sink 会再重放一次 avcC，这次重放丢掉，参数集已经在 `csd` 里。

高德仪表投屏开着时，两家的 1920×720 图层会叠在 `displayId=1` 上，合成更重。车上试之前先关掉高德的仪表投屏。计划不去停高德进程。

## 实车结论（必须遵守）

日志 `dist/supabase-logs/DiPlay-20261119-180129-927.txt`，0.5.0-geely，无线，18:00：

- `fly.probe summary eth0=absent tbox0=present cluster-host=none ipk-host=none ipk-peer=none rtp-peer=none`。内核没有第二块以太网设备。`172.30.100.10` 不在任何网卡上。`28887` 没有在听。
- 左滑仍然 `fly.net add tbox0`、`fly.tcp listen`、`1887 front true`、`1885 left true`，3 秒后 `fly.abort cluster silent`。没有 `fly.tcp accept`。
- 中控画面在播。歌词经 Binder 送到了 `instrumentserver`。

固件配置与调用：

- `MultipleScreen.json` 的 `LargeScreenSurface`：`SurfaceRenderType` 1，`VideoEncodingType` 0，窗口 `1001`，1920×720。引导时 `IsMapVisiable` 为 1。
- `XCNaviService` 只在地图支持 V2DIM 且正在引导时调用 `setPresentationToDimSwitch(1, "com.autonavi.amapauto", "com.autonavi.amapauto", true)`。DiPlay 不走这个调用。
- `BaiduScreenTestReceiver` 接收 `ecarx.intent.action.DIM_PRESENTATION_CTRL`，额外字段 `act` 为 `open` 或 `close`。没有包名白名单。

---

### Task 1: 选出仪表屏并写成不会被涂掉的日志

**Files:**

- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyDisplay.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyDisplayTest.kt`

**Interfaces:**

- Consumes: 无
- Produces: `data class FlyDisplayInfo(val id: Int, val name: String, val width: Int, val height: Int, val defaultDisplay: Boolean)`
- Produces: `fun chooseClusterDisplay(displays: List<FlyDisplayInfo>): FlyDisplayInfo?`
- Produces: `fun displayLines(displays: List<FlyDisplayInfo>, chosen: FlyDisplayInfo?): List<String>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlyDisplayTest {
    private val center = FlyDisplayInfo(0, "Built-in Screen", 1920, 720, defaultDisplay = true)
    private val cluster = FlyDisplayInfo(2, "HDMI Screen", 1920, 720, defaultDisplay = false)
    private val other = FlyDisplayInfo(5, "virt", 800, 480, defaultDisplay = false)

    @Test fun theCenterScreenIsNeverTheCluster() {
        assertNull(chooseClusterDisplay(listOf(center)))
    }

    @Test fun a1920x720DisplayThatIsNotTheCenterWins() {
        assertEquals(cluster, chooseClusterDisplay(listOf(center, other, cluster)))
    }

    @Test fun anyOtherDisplayIsUsedWhenNoneIs1920x720() {
        assertEquals(other, chooseClusterDisplay(listOf(center, other)))
    }

    @Test fun linesNameTheChoiceAndDoNotContainADottedAddress() {
        val lines = displayLines(listOf(center, cluster), cluster)
        assertEquals("fly.display list 0:Built-in Screen:1920x720:default 2:HDMI Screen:1920x720", lines[0])
        assertEquals("fly.display use id=2 name=HDMI Screen 1920x720", lines[1])
        assertEquals("fly.display none", displayLines(listOf(center), null).last())
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyDisplayTest --offline`

Expected: FAIL，`chooseClusterDisplay` 未定义。

- [ ] **Step 3: Implement the picker**

`FlyDisplay.kt`：

```kotlin
package com.shilapi.xcertplay.instrument.fly

data class FlyDisplayInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val defaultDisplay: Boolean,
)

fun chooseClusterDisplay(displays: List<FlyDisplayInfo>): FlyDisplayInfo? {
    val candidates = displays.filter { !it.defaultDisplay && it.id != 0 }
    return candidates.firstOrNull { it.width == 1920 && it.height == 720 } ?: candidates.firstOrNull()
}

fun displayLines(displays: List<FlyDisplayInfo>, chosen: FlyDisplayInfo?): List<String> {
    val list = displays.joinToString(" ") { display ->
        val suffix = if (display.defaultDisplay) ":default" else ""
        "${display.id}:${display.name}:${display.width}x${display.height}$suffix"
    }
    val choice = if (chosen == null) {
        "fly.display none"
    } else {
        "fly.display use id=${chosen.id} name=${chosen.name} ${chosen.width}x${chosen.height}"
    }
    return listOf("fly.display list $list", choice)
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyDisplayTest --offline`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyDisplay.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyDisplayTest.kt
git commit -m "feat: choose the cluster presentation display"
```

---

### Task 2: 挂上显示器就进入 PUSHING

**Files:**

- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySession.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySessionTest.kt`（已有则追加；没有就新建同名文件，只加本任务的测试）

**Interfaces:**

- Consumes: `ClusterFlySession.onLeft`，`FlyPhase.WAITING_FOR_VIDEO`
- Produces: `ClusterFlySession.onDisplayReady(nowMillis: Long): FlyEffect`。仅当相位是 `WAITING_FOR_VIDEO` 时把相位改成 `PUSHING` 且 `push == true`。其他相位不改。
- Produces: `ClusterFlyController.onClusterDisplayReady()`。调用 `onDisplayReady`，置 `subscribed`，调用已有的 `setMainVideoSubscribed(true)`，并取消这一轮的 3 秒收回。

- [ ] **Step 1: Write the failing test**

在会话测试里加：

```kotlin
@Test fun aDisplayWhileWaitingStartsThePicture() {
    val session = ClusterFlySession()
    val now = 10_000L
    session.onLeft(now, openConditions())
    val due = now + ClusterFlySequencer.STEP_DELAY_MILLIS + ClusterFlySequencer.MCU_GAP_MILLIS
    assertEquals(FlyPhase.ARMING, session.onWaitTimeout(due - 1).phase)
    assertEquals(FlyPhase.WAITING_FOR_VIDEO, session.onWaitTimeout(due).phase)
    val effect = session.onDisplayReady(due + 1)
    assertEquals(FlyPhase.PUSHING, effect.phase)
    assertTrue(effect.push)
}

@Test fun aDisplayWhileArmingDoesNothing() {
    val session = ClusterFlySession()
    val effect = session.onLeft(10_000L, openConditions()).let {
        session.onDisplayReady(10_000L)
    }
    assertEquals(FlyPhase.ARMING, effect.phase)
    assertFalse(effect.push)
}

@Test fun aDisplayWhileIdleDoesNothing() {
    val effect = ClusterFlySession().onDisplayReady(0L)
    assertEquals(FlyPhase.IDLE, effect.phase)
    assertFalse(effect.push)
}
```

`openConditions()` 用这个测试文件里已经存在的那个。如果文件里没有，用 `FlyConditions(navigationArmed = true, deviceProvisioned = true, reversing = false, radar = false, ecall = false, millisSinceLastClusterCommand = Long.MAX_VALUE)`。

`FLY_LEFT` 的到期时间是 `now + 400 + 1000`。在这之前调用 `onDisplayReady` 必须留在 `ARMING`。现有的「一直没有索流就停在 WAITING」在 Task 4 删掉索流之后改成「没有挂上显示器就停在 WAITING」。

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlySessionTest --offline`

Expected: FAIL，`onDisplayReady` 未定义。

- [ ] **Step 3: Implement the transition**

在 `ClusterFlySession` 里增加。安全检查和 `onVideoAsk(VideoAsk.OPEN)` 在 `WAITING_FOR_VIDEO` 下的那一段相同。Task 4 删掉索流之后，这是进入 `PUSHING` 的唯一入口。

```kotlin
fun onDisplayReady(nowMillis: Long): FlyEffect {
    advance(nowMillis)
    if (phase != FlyPhase.WAITING_FOR_VIDEO) return effect()
    val safety = lastConditions?.let { safetyBlock(it) }
    if (safety != null) return effect(warning = safety)
    phase = FlyPhase.PUSHING
    return effect()
}
```

在 `ClusterFlyController` 里增加。这一步只供测试和 Task 3 调用，不在 `sendStep` 里调用。订阅也由 Task 3 在解码器 `start` 成功时打开；这里先把订阅打开，Task 3 再调用一次是幂等的。

```kotlin
fun onClusterDisplayReady() {
    synchronized(lock) {
        val effect = session.onDisplayReady(clock())
        if (effect.phase != FlyPhase.PUSHING) return
        videoTimeoutGeneration++
        if (!subscribed) {
            subscribed = true
            setMainVideoSubscribed(true)
        }
        handle(effect)
    }
}
```

不要在 `sendFlyCommand` 里调用。`send()` 返回时会话还在 `ARMING`，这时进来会被相位检查丢掉。

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlySessionTest --offline`

Expected: PASS。原有的「一直没有索流就停在 WAITING」测试仍 PASS。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySession.kt \
  common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySessionTest.kt
git commit -m "feat: a cluster display starts the fly picture"
```

---

### Task 3: 左滑挂上 Presentation，软解直接画到 Surface

`MediaCodec` 和 `Presentation` 在本机单测里没有可用的解码器和 HDMI。这一任务的单测只覆盖纯函数和会话接线。解码循环的代码按下面的类原样写上，车上用 `fly.display` 日志验收。

**Files:**

- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyAnnex.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterPresentation.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterDecoder.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyAnnexTest.kt`
- Modify: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyControllerTest.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`，增加 `mainVideoConfig()`

**Interfaces:**

- Consumes: `chooseClusterDisplay`，`displayLines`，`ClusterFlyController.onClusterDisplayReady`
- Produces: `onLayerOpened: () -> Unit`，默认空。`sendStep` 在 `FLY_LEFT` 成功且 `onWaitTimeout` 之后同步调用，这时相位仍是 `WAITING_FOR_VIDEO`。调用返回后才允许 `scheduleVideoTimeout()`。回调里只 `show()`，不调用 `onClusterDisplayReady()`。
- Produces: `FlyAnnex.parameterSets`、`FlyAnnex.isIdr`、`FlyAnnex.isParameterSet`、`FlyAnnex.isConfig`
- Produces: `class FlyClusterPresentation`。`show()` 只在主线程。`surfaceCreated` 才把 `Surface` 交给控制器。
- Produces: `class FlyClusterDecoder`。只在飞屏线程使用。

- [ ] **Step 1: Write the failing tests**

`FlyAnnexTest`：

```kotlin
@Test fun parameterSetsComeOutWithoutStartCodes() {
    val sps = byteArrayOf(0x67, 100, 0, 31)
    val pps = byteArrayOf(0x68, -84.toByte())
    val config = avcC(100, sps, pps)
    val sets = FlyAnnex.parameterSets(config)
    assertNotNull(sets)
    assertArrayEquals(sps, sets!!.sps)
    assertArrayEquals(pps, sets.pps)
}

@Test fun anIdrIsRecognizedAndAPredictedFrameIsNot() {
    assertTrue(FlyAnnex.isIdr(byteArrayOf(0, 0, 0, 1, 0x65, 0)))
    assertFalse(FlyAnnex.isIdr(byteArrayOf(0, 0, 0, 1, 0x41, 0)))
    assertTrue(FlyAnnex.isParameterSet(byteArrayOf(0, 0, 1, 0x67, 0)))
}

private fun avcC(profile: Int, sps: ByteArray, pps: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(1)
    out.write(profile)
    out.write(0)
    out.write(31)
    out.write(0xFF)
    out.write(0xE1)
    out.write(sps.size shr 8)
    out.write(sps.size)
    out.write(sps)
    out.write(1)
    out.write(pps.size shr 8)
    out.write(pps.size)
    out.write(pps)
    return out.toByteArray()
}
```

在 `ClusterFlyControllerTest` 里加。生产环境的 `postDelayed` 是主线程 `Handler`；测试把回调记下来再按顺序执行，用来确认 `onLayerOpened` 发生在 3 秒计时入队之前，并且这时还没有进入 `PUSHING`。

```kotlin
@Test fun theLeftStepOpensTheLayerAndStillWaitsForTheSurface() {
    val order = mutableListOf<String>()
    val controller = ClusterFlyController(
        commands = { sent.add(it) },
        showWarning = { warnings.add(it) },
        setMainVideoSubscribed = {},
        log = { lines.add(it) },
        clock = { now },
        postDelayed = { delay, action ->
            order.add("schedule $delay")
            scheduled.add(delay to action)
        },
        onLayerOpened = { order.add("opened") },
    )
    controller.onGesture(Decision.FLY_LEFT)
    now = 2_400L
    runScheduled()
    assertEquals(FlyPhase.WAITING_FOR_VIDEO, controller.phase)
    assertTrue(order.indexOf("opened") < order.indexOf("schedule 3000"))
    controller.onClusterDisplayReady()
    assertEquals(FlyPhase.PUSHING, controller.phase)
    scheduled.filter { it.first == 3_000L }.forEach { it.second() }
    assertFalse(lines.any { it.contains("fly.abort") })
}
```

控制器增加 `val phase: FlyPhase get() = session.currentPhase`，测试才能读到相位。

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyAnnexTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyControllerTest.theLeftStepOpensTheLayerAndStillWaitsForTheSurface --offline`

Expected: FAIL，`FlyAnnex` 或 `onLayerOpened` 未定义。

- [ ] **Step 3: 纯函数和左滑接线**

`FlyAnnex.kt` 承接现在 `ClusterFlyPush` 里 avcC 和 NAL 类型的判断。Task 4 删掉 `ClusterFlyPush` 之后，解码器仍用这个对象。`parameterSets` 返回的是 SPS/PPS 原文，不带 `00 00 00 01`。

```kotlin
internal object FlyAnnex {
    data class ParameterSets(val sps: ByteArray, val pps: ByteArray)

    fun isConfig(bytes: ByteArray): Boolean = profile(bytes) != null

    fun profile(bytes: ByteArray): Int? {
        if (bytes.size < 7 || bytes[0].toInt() != 1) return null
        if ((bytes[4].toInt() and 0xFC) != 0xFC) return null
        if ((bytes[5].toInt() and 0xE0) != 0xE0) return null
        return bytes[1].toInt() and 0xFF
    }

    fun parameterSets(config: ByteArray): ParameterSets? {
        if (profile(config) == null) return null
        var at = 5
        val spsCount = config[at].toInt() and 0x1F
        at++
        val sps = readSet(config, at, spsCount) ?: return null
        at = sps.second
        if (at >= config.size) return null
        val ppsCount = config[at].toInt() and 0xFF
        at++
        val pps = readSet(config, at, ppsCount) ?: return null
        return ParameterSets(sps.first, pps.first)
    }

    fun isIdr(nalu: ByteArray): Boolean = hasType(nalu, 5)
    fun isParameterSet(nalu: ByteArray): Boolean = hasType(nalu, 7) || hasType(nalu, 8)

    private fun readSet(config: ByteArray, start: Int, count: Int): Pair<ByteArray, Int>? {
        var at = start
        var first: ByteArray? = null
        repeat(count) {
            if (at + 2 > config.size) return null
            val length = ((config[at].toInt() and 0xFF) shl 8) or (config[at + 1].toInt() and 0xFF)
            val from = at + 2
            if (length <= 0 || from + length > config.size) return null
            if (first == null) first = config.copyOfRange(from, from + length)
            at = from + length
        }
        return first?.let { it to at }
    }

    private fun hasType(nalu: ByteArray, type: Int): Boolean {
        var i = 0
        while (i + 3 < nalu.size) {
            val header = when {
                nalu[i].toInt() == 0 && nalu[i + 1].toInt() == 0 && nalu[i + 2].toInt() == 1 -> i + 3
                i + 4 < nalu.size && nalu[i].toInt() == 0 && nalu[i + 1].toInt() == 0 &&
                    nalu[i + 2].toInt() == 0 && nalu[i + 3].toInt() == 1 -> i + 4
                else -> -1
            }
            if (header >= 0 && header < nalu.size && (nalu[header].toInt() and 0x1F) == type) return true
            i++
        }
        return false
    }
}
```

`ClusterFlyController` 增加构造参数，默认值让现有测试不用改调用：

```kotlin
private val onLayerOpened: () -> Unit = {},
private val mainVideoConfig: () -> ByteArray? = { null },
private val onMain: (() -> Unit) -> Unit = { action -> action() },
```

`sendStep` 里把现有的 `if (session.currentPhase == FlyPhase.WAITING_FOR_VIDEO) scheduleVideoTimeout()` 换成下面三行。这三行位于 `if (!ok) return` 之后，非 `FLY_LEFT` 的步骤在更前面已经返回。不要把 `onLayerOpened` 再丢回主线程。`FLY_LEFT` 这一步本身就是主线程 `postDelayed` 跑的。

```kotlin
handle(effect)
if (ok && session.currentPhase == FlyPhase.WAITING_FOR_VIDEO) onLayerOpened()
if (session.currentPhase == FlyPhase.WAITING_FOR_VIDEO) scheduleVideoTimeout()
```

`onLayerOpened` 里禁止调用 `onClusterDisplayReady()`。Surface 还没有创建。

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.FlyAnnexTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyControllerTest --offline`

Expected: PASS。若有用例断言 `fly.push` 或 `onPacket` 把字节送进重编码器，改成这一任务不再走 `ClusterFlyPush`。只验证重编码的用例删除，Task 4 会删掉那个类。

- [ ] **Step 5: Presentation、解码器和 Activity 接线**

`AndroidMediaSink` 增加。返回的是副本，调用方可以留着。

```kotlin
fun mainVideoConfig(): ByteArray? = synchronized(observerLock) { lastMainConfig?.copyOf() }
```

`FlyClusterPresentation.kt`：

```kotlin
internal class FlyClusterPresentation(
    context: Context,
    display: Display,
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        val root = FrameLayout(context)
        val surfaceView = SurfaceView(context)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
        })
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }
}
```

`FlyClusterDecoder.kt` 只在飞屏线程创建和使用。`csd-0` / `csd-1` 不带起始码。输出只用 `releaseOutputBuffer(index, true)`。

```kotlin
internal class FlyClusterDecoder(
    surface: Surface,
    sps: ByteArray,
    pps: ByteArray,
    private val log: (String) -> Unit,
) {
    private val codec: MediaCodec? = create(surface, sps, pps)
    val ready: Boolean = codec != null
    private var awaitingIdr = true
    private var ptsUs = 0L

    fun dropUntilIdr() {
        awaitingIdr = true
    }

    fun submit(nalu: ByteArray) {
        val codec = codec ?: return
        val idr = FlyAnnex.isIdr(nalu)
        if (awaitingIdr && !idr && !FlyAnnex.isParameterSet(nalu)) return
        if (idr) awaitingIdr = false
        val startedNs = System.nanoTime()
        queue(codec, nalu)
        drain(codec)
        val spentMs = (System.nanoTime() - startedNs) / 1_000_000L
        if (spentMs > SLOW_MS) {
            codec.flush()
            codec.start()
            awaitingIdr = true
            log("fly.display slow $spentMs")
        }
    }

    fun release() {
        val codec = codec ?: return
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
        }
        codec.release()
    }

    private fun queue(codec: MediaCodec, nalu: ByteArray) {
        var index = codec.dequeueInputBuffer(0)
        if (index < 0) {
            drain(codec)
            index = codec.dequeueInputBuffer(0)
        }
        if (index < 0) {
            awaitingIdr = true
            return
        }
        val buffer = codec.getInputBuffer(index) ?: run {
            awaitingIdr = true
            return
        }
        buffer.clear()
        buffer.put(nalu)
        codec.queueInputBuffer(index, 0, nalu.size, ptsUs, 0)
        ptsUs += FRAME_US
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> codec.releaseOutputBuffer(index, true)
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> return
            }
        }
    }

    private fun create(surface: Surface, sps: ByteArray, pps: ByteArray): MediaCodec? {
        val name = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { info ->
                !info.isEncoder && info.name == SOFTWARE &&
                    info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
            }?.name
        if (name == null) {
            log("fly.display decoder failed")
            return null
        }
        return try {
            val codec = MediaCodec.createByCodecName(name)
            val format = MediaFormat.createVideoFormat(MIME, 1920, 720)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: RuntimeException) {
            log("fly.display decoder failed ${error.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val MIME = "video/avc"
        const val SOFTWARE = "OMX.google.h264.decoder"
        const val FRAME_US = 50_000L
        const val SLOW_MS = 150L
    }
}
```

`getInputBuffer` 是 API 21，这块车机是 API 22，可以用。不要调用 `getOutputBuffer` 或 `getOutputImage`。

控制器在飞屏线程启动解码器。`processFrame` 不再调用 `ClusterFlyPush`。profile 100 在那里会被丢掉。

```kotlin
fun onFlySurface(surface: Surface?) {
    frameExecutor.execute {
        if (surface == null) {
            flyDecoder?.release()
            flyDecoder = null
            waitingSurface = null
            return@execute
        }
        waitingSurface = surface
        tryStartDecoder()
    }
}

private fun tryStartDecoder() {
    if (flyDecoder != null || waitingSurface == null) return
    val config = pendingConfig ?: mainVideoConfig() ?: run {
        if (!configWarned) {
            configWarned = true
            log("fly.display no config")
        }
        return
    }
    val sets = FlyAnnex.parameterSets(config) ?: run {
        log("fly.display decoder failed")
        onPictureFailed()
        return
    }
    val created = FlyClusterDecoder(waitingSurface!!, sets.sps, sets.pps, log)
    if (!created.ready) {
        created.release()
        onPictureFailed()
        return
    }
    flyDecoder = created
    onClusterDisplayReady()
}

private fun processFrame(type: Int, bytes: ByteArray) {
    if (!background) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        background = true
    }
    if (FlyAnnex.isConfig(bytes)) pendingConfig = bytes.copyOf()
    tryStartDecoder()
    val decoder = flyDecoder ?: return
    if (session.currentPhase != FlyPhase.PUSHING) return
    if (FlyAnnex.isConfig(bytes)) return
    if (dropUntilIdr.compareAndSet(true, false)) decoder.dropUntilIdr()
    try {
        decoder.submit(bytes)
    } catch (error: RuntimeException) {
        log("fly.display decoder failed ${error.javaClass.simpleName}")
        decoder.release()
        flyDecoder = null
        onPictureFailed()
    }
}
```

`onMainVideo` 的 `RejectedExecutionException` 里把 `dropUntilIdr` 设为 true，不再调用 `ClusterFlyPush.noteDropped()`。

`onPictureFailed` 从飞屏线程调用，拆屏必须回到主线程。相位已经是 `PUSHING` 时走右滑那套关闭（1885 已经掀开，需要 `[74, 2]` 盖上）。还在等待时走现有的 `abortSession`，它不发 `FLY_RIGHT`。

```kotlin
fun onPictureFailed() {
    onMain {
        synchronized(lock) {
            if (session.currentPhase == FlyPhase.PUSHING) {
                val effect = session.onRight(clock(), conditions(clock()))
                subscribed = false
                setMainVideoSubscribed(false)
                handle(effect)
                onGiveUp()
            } else {
                abortSession("decoder failed")
            }
        }
    }
}
```

`abortSession` 的开头调用 `onGiveUp()`。构造参数 `onGiveUp: () -> Unit = {}`。

`CarPlayHostActivity.flyController()` 删掉 `linkFactory = { ClusterFlyLink(...) }`。Task 4 再删构造器参数和套接字类。把 `onLayerOpened` 设成 `attachClusterPresentation()`，`mainVideoConfig` 设成 `{ sink?.mainVideoConfig() }`，`onMain` 设成 `{ action -> runOnUiThread(action) }`，`onGiveUp` 设成 `{ dismissClusterFly() }`。不要 `sendBroadcast`。

```kotlin
private var clusterPresentation: FlyClusterPresentation? = null
private var clusterDisplayListener: DisplayManager.DisplayListener? = null

private fun attachClusterPresentation() {
    if (clusterPresentation != null) return
    val manager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val infos = manager.displays.map { display ->
        val size = Point()
        display.getRealSize(size)
        FlyDisplayInfo(
            display.displayId,
            display.name,
            size.x,
            size.y,
            display.displayId == Display.DEFAULT_DISPLAY,
        )
    }
    val chosen = chooseClusterDisplay(infos)
    displayLines(infos, chosen).forEach { DiagLog.i(ClusterFlyLog.TAG, it) }
    if (chosen == null) {
        listenForClusterDisplay(manager)
        return
    }
    val display = manager.displays.first { it.displayId == chosen.id }
    val presentation = FlyClusterPresentation(this, display) { surface ->
        clusterFly?.onFlySurface(surface)
    }
    try {
        presentation.show()
    } catch (error: RuntimeException) {
        DiagLog.i(ClusterFlyLog.TAG, "fly.display show failed ${error.javaClass.simpleName}")
        return
    }
    clusterPresentation = presentation
}

private fun listenForClusterDisplay(manager: DisplayManager) {
    if (clusterDisplayListener != null) return
    val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = attachClusterPresentation()
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
    }
    clusterDisplayListener = listener
    manager.registerDisplayListener(listener, mainHandler)
}

private fun dismissClusterFly() {
    clusterDisplayListener?.let { listener ->
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(listener)
    }
    clusterDisplayListener = null
    val presentation = clusterPresentation
    clusterPresentation = null
    presentation?.dismiss()
    clusterFly?.onFlySurface(null)
}
```

右滑和 `onDestroy` 在主线程调用 `dismissClusterFly()`。`show()` 失败或 `fly.display none` 不拆，让已经入队的 3 秒收回照常发生，收回时 `onGiveUp` 再拆。`surfaceCreated` 晚于 `show()` 返回。解码器 `start` 成功后的 `onClusterDisplayReady()` 把 3 秒代次加一，收回作废。

这块车上 `displayId=1` 在左滑之前就存在，正常路径不会走到 `DisplayListener`。`Presentation.show()` 能否被窗口管理器收下还没在车上证实，失败日志是 `fly.display show failed`。

- [ ] **Step 6: 编译**

Run: `./gradlew :common:compileDebugKotlin --offline`

Expected: BUILD SUCCESSFUL。Task 4 完成之前套接字类还可以留在树上，但 `flyController()` 已经不再创建它们，`processFrame` 也不再调用 `ClusterFlyPush`。

- [ ] **Step 7: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyAnnex.kt \
  common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterPresentation.kt \
  common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterDecoder.kt \
  common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt \
  common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyAnnexTest.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyControllerTest.kt \
  shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt
git commit -m "feat: show the center picture on the cluster presentation"
```

---

### Task 4: 删掉 1028 和重编码

**Files:**

- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLink.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyProtocol.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyPush.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyReencoderMediaCodec.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyClusterAddress.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyNet.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyPortHolder.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyProbe.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/FlyProbeCapture.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLinkTest.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyProtocolTest.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyPushTest.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyClusterSocketTest.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/FlyProbeTest.kt`
- Modify: `ClusterFlyController.kt`，去掉 `linkFactory`、`onPacket`、对上述类的引用
- Modify: `ClusterFlySession.kt`，去掉 `onVideoAsk`、`VideoAsk`、`takePendingOpen`。进入 `PUSHING` 只剩 `onDisplayReady`
- Modify: `ClusterFlyControllerTest.kt`、`ClusterFlySessionTest.kt`，删掉 24 字节索流和重编码器的用例，改成挂上显示器进入 `PUSHING`
- Modify: `ClusterFlyLog.kt`，去掉只为套接字服务的 `tcp`、`ask`、`pushStart`、`profile`、`pushStop`
- Modify: `CarPlayHostActivity.kt`，去掉 `collectFlyProbe`
- Modify: `QuickHudView.kt` 和两种语言的 `strings.xml`，去掉「采集飞屏」按钮
- Modify: `QuickHudViewTest.kt`，去掉这个按钮的断言
- Keep: `FlyTrace.kt`。它抓的是 1885 之后的 Extra 日志，还用来确认掀层有没有发出去

**不删：**

- `ClusterFlyMcu`、`ClusterFlySequencer`、`ClusterFlyGate`、`ClusterFlyWarning`、`FlyTrace`。1885、1887 和导航广播还用它们。
- 仪表歌词和媒体卡（`loadClusterMediaMetadataEnabled`、`loadClusterMediaSource`）。那是星瑞自己的 Binder 通路，不是比亚迪投屏。
- 方向盘按键码、热点、导航音源里带 BYD 字样的注释和常量。它们不是这条仪表画面路径，本计划不动。

比亚迪仪表投屏在 Task 5 删除。

- [ ] **Step 1: 删掉上面列出的文件，改掉仍引用它们的编译错误**

`processFrame` 在 Task 3 已经改走 `FlyClusterDecoder` 和 `FlyAnnex`。这一步只删文件，并改掉仍然引用套接字、`ClusterFlyPush`、`VideoAsk` 的编译错误。不要把 avcC 再转成 Annex-B 送进解码器。`csd-0` / `csd-1` 继续用 `FlyAnnex.parameterSets`。

会话测试里「没有索流就停在 WAITING」改为：左滑并越过 `FLY_LEFT` 的到期时间之后，不调用 `onDisplayReady`，相位仍是 `WAITING_FOR_VIDEO`。

控制器测试里用 `onPacket` 把会话推进 `PUSHING` 的辅助函数删掉。推进改成调用 `onClusterDisplayReady()`，并且只在 `onLayerOpened` 之后相位才会是 `PUSHING`。

- [ ] **Step 2: 跑飞屏相关测试和编译**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.* --tests com.shilapi.xcertplay.QuickHudViewTest --offline`

Expected: PASS。仓库里不再出现 `ClusterFlyLink`、`FlyClusterAddress`、`10.255.255.101` 的生产引用。`FlyProbe` 的测试命令字符串可以跟着文件一起消失。

- [ ] **Step 3: Commit**

```bash
git add -A common/src/main/java/com/shilapi/xcertplay/instrument/fly \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly \
  common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt \
  common/src/main/java/com/shilapi/xcertplay/ui/component/QuickHudView.kt \
  common/src/test/java/com/shilapi/xcertplay/QuickHudViewTest.kt \
  common/src/main/res/values/strings.xml \
  common/src/main/res/values-zh-rCN/strings.xml
git commit -m "refactor: remove the unused cluster socket and re-encoder"
```

---

### Task 5: 删掉比亚迪仪表投屏

本项目不兼容比亚迪。`ClusterMapPresentation` 按屏名 `fission_bg_XDJAScreenProjection` 找显示器，再向手机要流 111。星瑞没有这块屏。设置页里的「仪表地图」会把 `AirPlayConfig.cluster` 填上，手机就会再推一路 1920×720。这条和飞屏无关，删掉。

`AirPlayConfig.cluster` 的默认值已经是 null。`AirPlaySession` 只在它非空时才加 `altScreen`，`AirPlayInfoPlist` 也只在那时才声明流 111。任务做到不再赋值即可，不改会话服务器。

**Files:**

- Delete: `common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/DiLink51ClusterLayout.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/DiLink51ClusterMonitor.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/ClusterActivityState.kt`
- Delete: `shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayClusterDisplay.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/ClusterMapPresentationTest.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/DiLink51ClusterLayoutTest.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/ClusterActivityStateTest.kt`
- Delete: `shared/src/test/java/com/shilapi/xcertplay/airplay/CarPlayClusterDisplayTest.kt`
- Modify: `CarPlayHostActivity.kt`。删掉 `ensureClusterPresentation`、`ensureDiLink51ClusterPresentation`、`dismissClusterPresentation`、`onClusterSurface`、`clusterDisplayConfig`、`effectiveClusterTheme`、`onClusterActivityState`，以及 `clusterPresentation`、`clusterLayers`、`clusterMonitor`、`clusterSurface`。`AirPlayConfig` 不再传 `cluster`。所有 `setSurface` / `clearSurface(SCREEN_TYPE_ALT, …)` 删掉。`SCREEN_TYPE_ALT` 常量如果没有剩余引用，一起删。
- Modify: `DiPlayActivity.kt`。删掉「仪表地图」那张卡、主题、对比度、地图尺寸、车标偏移，以及使用情况访问权限的自动设置对话框和 `reconnectForClusterMap`。
- Modify: `LocalizedOptions.kt`。只删 `DiLink51ClusterLayout.Theme` 和 `Contrast` 的两个扩展。文件里的尺寸和热点文案留下。
- Modify: `AirPlayPersistence.kt`。删掉 `loadClusterMapEnabled`、`saveClusterMapEnabled`、`loadClusterContent`、`saveClusterContent`、`loadClusterMapScalePercent`、`saveClusterMapScalePercent`、两个车标步进的读写。`loadClusterMediaMetadataEnabled` 和 `loadClusterMediaSource` 留下。
- Modify: `values/strings.xml` 和 `values-zh-rCN/strings.xml`。只删不再被引用的仪表地图文案，例如 `instrument_cluster_map`、`instrument_theme`、`instrument_contrast`、`cluster_map_size`、`automatic_cluster_map_setup`、`follow_instrument_theme_and_map_card`。删之前用引用搜索确认。

- [ ] **Step 1: 删掉上面的类和测试，改掉编译错误**

启动时不再扫描比亚迪屏名，也不再创建 `DiLink51ClusterMonitor`。连接参数里不声明第二块屏。

- [ ] **Step 2: 编译并跑相关测试**

Run: `./gradlew :common:compileDebugKotlin :shared:compileDebugKotlin :common:testDebugUnitTest --offline`

Expected: BUILD SUCCESSFUL，测试 PASS。`common` 和 `shared` 的生产代码里不再出现 `ClusterMapPresentation`、`DiLink51ClusterLayout`、`CarPlayClusterDisplay`、`fission_bg_XDJAScreenProjection`。

- [ ] **Step 3: Commit**

```bash
git add -A common/src/main/java/com/shilapi/xcertplay \
  common/src/test/java/com/shilapi/xcertplay \
  common/src/main/res \
  shared/src/main/java/com/shilapi/xcertplay/airplay \
  shared/src/test/java/com/shilapi/xcertplay/airplay
git commit -m "refactor: remove the BYD cluster map path"
```

---

## 实车屏幕（2026-11-20 adb 已核对）

车机 `172.20.10.4`，`SWFS11G0706H5181.00600`，当时前台是系统设置，高德没有在投。`dumpsys display` 里一直有两块屏，不需要等高德把屏创建出来：

- `displayId=0`，名字 `内置屏幕`，`local:0`，1920×720，内置主屏，有内容。
- `displayId=1`，名字 `HDMI 屏幕`，`local:1`，1920×720，类型 HDMI，带 `FLAG_PRESENTATION`，状态 ON，`mHasContent=false`。`Display #1` 上没有 Activity。

`chooseClusterDisplay` 应选中 id 1。Task 5 删掉按比亚迪屏名过滤的 `ClusterMapPresentation`。新的选择只认非主屏，不认 `fission_bg_XDJAScreenProjection`。

当时仍然没有 `eth0`。`tbox0` 是 `192.168.225.190/24`。`wlan0` 是热点 `172.20.10.4/28`。

同一条 adb，高德中控投屏打开之后（2026-11-20 10:49）：

- `displayId=1` 变成 `mHasContent=true`。`SurfaceFlinger` 里 HDMI 的 `numLayers` 从 0 变成 2，`flips` 从 2 变成 90。
- 这两层都是 `com.autonavi.amapauto`（pid 3278）的 `SurfaceView`，1920×720，铺满 `layerStack=1`。底下一层不透明，上面一层是界面。生产者就是高德进程，不是中控屏的镜像。
- `dumpsys window` 里 `Display 1` 仍然没有 Activity。高德的 `MainMapActivity` 留在 `displayId=0`。
- 这段日志里 HDMI 刷新大约 1 fps。`XCNaviService` 4.1.3 同时在打 `supportDim:true`、`isInNavi:true`，推的是转向信息，不是像素。
- 运行中的高德配置：`WindowId` 1001，`DisplayName` 为空，`SurfaceRenderType` 1。代码按 `deviceId - 1000 == displayId` 挂到 `displayId=1`。

## 车上怎么验收

装上这一版，无线连上，三指左滑一次，然后上传日志。

成功时日志里有 `fly.display use id=1`，中控画面仍在，仪表上出现同一路画面。没有 `fly.net add`，没有 `fly.tcp listen`。这两行在 Task 4 之后不该再能打出来。`show()` 没被收下时是 `fly.display show failed`。软解没起来时是 `fly.display decoder failed` 或 `fly.display no config`。单帧超过预算时是 `fly.display slow`。

这块车上 `displayId=1` 一直在。日志若是 `fly.display none`，是选择写错了，不是再去改 `1028` 或 `setPresentationToDimSwitch`。

`fly.display use id=1` 之后玻璃仍不换：说明像素已经进了 HDMI 层，1885 没有把仪表切到这一层。那一次以前的 1885 发出时这块 HDMI 上是 0 层，所以现在还没有证据。先看这一版的玻璃，再决定要不要查 `XCNaviService` 4.1.3。那一步不在本计划里，签名白名单也过不去。

中控帧率明显掉下去，或日志里连续出现单帧超过 150 ms：软解预算不够。不要改成 MTK 硬解，也不要加第二遍编码。

## Self-review

- 规格里的签名白名单：只挡 `DimInteraction`。不调用它，也不发 `DIM_PRESENTATION_CTRL`。没有单独的取消任务。
- 1885 仍是左滑最后一笔，只掀层，不画像素。像素挂到 `displayId=1`。`onLayerOpened` 在进入 `WAITING_FOR_VIDEO` 之后、3 秒计时之前，并且只 `show()`。`onClusterDisplayReady` 在解码器 `start` 之后。
- `show()` 和 `dismiss()` 在主线程。`FLY_LEFT` 的 `sendStep` 已经在主线程，不再套一层 `post`。窗口带 `FLAG_NOT_FOCUSABLE` 和 `FLAG_NOT_TOUCHABLE`。
- 解码器排空输出，`releaseOutputBuffer(index, true)`，`presentationTimeUs` 每帧加 50000。不读回 YUV。
- 不抓 `dumpsys`：Task 3 自己枚举显示器并写 `fly.display`。
- 不向手机要第二路。Task 3 只消费中控这一路。`AirPlayConfig.cluster` 保持 null。
- 旧的套接字、加地址、RTP、重编码器和采集按钮在 Task 4 删除。比亚迪仪表投屏在 Task 5 删除。
- 不按 5 fps 抽帧。150 ms 和等到下一个 IDR 用来保护中控。profile 100 的软解能力留给车上的 `fly.display decoder failed` 证实。
