# 星瑞 E01：1885 直发、失败收回、索流超时与顶部提示实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 三指左滑发出的 1885 不再走会丢的广播。Binder 回成功只表示送出。仪表在 3 秒内没有来要视频，或者 1885 根本没送出，就收回已经发出的导航状态，停掉 1028，回到可以再滑的空闲，并在画面最上面显示一次「仪表没有换页，请再滑一次」。

**Architecture:**

1. `ClusterFlyMcu.send1885` 走和 1887 同一条 `RpcService` Binder。左滑负载 `[74, 1]`，右滑负载 `[74, 2]`。真正的右滑关闭和断开时的关闭仍发 `[74, 2]`。中止路径不发。
2. `FLY_LEFT` 返回 false：不调用 `onWaitTimeout`。调用 `onAbort`，相位直接回到 `IDLE`，`flyStepAt` 清成 `NO_TIME`。这个函数开头不能调用 `advance()`。收回只发 `NAV_OFF` 和 1887 后台 `NAV_BACK`。然后走现有的 `stopEverything()`（里面是 `link.close()`，没有 `link.stop()`）。
3. `FLY_LEFT` 返回 true：才进入 `WAITING_FOR_VIDEO`，并开始 3 秒计时。3 秒内收到并接受 `VideoAsk.OPEN` 才算换页成功，取消计时。超时走同一条中止。Binder 的 `readInt() != 0` 不是换页成功。
4. 失败时用现有那条提示，从底部挪到画面最上面，同一次失败只显示一次，两秒后自己消失。它已经是根布局的最后一个子视图，画在 CarPlay 视频上面。

**Tech Stack:** Kotlin，Android 5.1 / API 22，JUnit4，Robolectric 4.17（测试），`DiagLog`，现有 `ClusterFlyLink`。

**Spec:** 本文件即为规格。报文来自 `ThreeFingerReceiver` 与 `ClusterFlyMcu` 已核对过的 1887 通道。

---

## Global Constraints

- 最低 API 级别为 22。禁止引入更高版本 Android API。
- 不修改 `/system`，不改系统 APK。
- 1885：服务名 `RpcService`，接口 `com.neusoft.alfus.os.IRpcService`，事务号 5，控制字节 0，功能号 1885，负载 25 字节。开启 `[0]=74, [1]=1`，关闭 `[0]=74, [1]=2`，其余为 0。
- 1887：功能号 1887，负载 25 字节。前台 `[0]=67, [1]=1`，后台 `[0]=67, [1]=0`。和 1885 至少相隔 `ClusterFlySequencer.MCU_GAP_MILLIS`（1000 ms）。这条间隔已经由打开序列保证，中止时不要把两笔 MCU 指令叠在同一毫秒。
- 打开顺序不变：`NAV_ON`，400 ms 后 `NAV_FRONT`，再 1000 ms 后 `FLY_LEFT`。
- 真正的关闭顺序不变：`FLY_RIGHT`（1885 关闭），400 ms 后 `NAV_OFF`，右滑后 1000 ms `NAV_BACK`。先清导航标志会让原厂丢掉右滑。这条只用于层已经打开、或者 1885 已经送出之后用户右滑或 CarPlay 断开。
- 中止顺序：`NAV_OFF` 立刻发，400 ms 后 `NAV_BACK`。不发 `FLY_RIGHT`。只在 `ARMING` 或 `WAITING_FOR_VIDEO` 中止。`PUSHING` 的关闭仍走原来的 `requestClose`。
- 3 秒从 `FLY_LEFT` 送出且返回 true 的那一刻起算。超时判断放在控制器里。不要改 `ClusterFlySession.onWaitTimeout`：它在时间到了之后进入等待，会话测试 `a cluster that never asks gets no video` 必须仍然是 `WAITING_FOR_VIDEO`。
- `ClusterFlyGate.MIN_COMMAND_INTERVAL_MILLIS` 仍是 1000 ms。中止解除的是 `phase != IDLE` 死锁。失败后 1 秒内再滑，仍提示「刚才那次还没完成，请稍等」。不要把间隔改成 0。
- 1887 返回 false 不在本计划里中止。昨天 1887 返回了 true，仪表仍没动。换页成败只看 1885。
- 每个代码任务都先写会失败的测试，再写实现。

## 实车结论（必须遵守）

日志 `dist/supabase-logs/DiPlay-20261117-174622-109.txt`，0.5.0-geely，无线：

- 17:38:47.685 左滑进入 `ARMING`，发出 `NAV_ON`。17:38:48.087 `fly.mcu 1887 front true`。17:38:49.082 `FLY_LEFT`，5 ms 后无条件进入 `WAITING_FOR_VIDEO`。17:38:49.361 `fly.extra empty`，`ThreeFingerReceiver` 没有日志。
- 仪表画面没有变化，之后也没有 `fly.tcp accept`、`fly.ask`、`fly.push`、`fly.stat`。
- 17:38:59、17:39:00、17:39:03 只有 `fly.gesture left`。`onLeft` 在 `phase != IDLE` 时直接返回，所以这三次被丢掉。
- 17:45:46 下滑只打开快捷栏。17:45:48 断开才从 `WAITING_FOR_VIDEO` 收掉。卡住约 7 分钟。

因此：`fly.mcu 1885 left true` 的措辞是「Binder 收下了」。仪表没来要视频，就按没换页处理。

## 这次审查要改掉的实现陷阱

- 失败时只跳过 `onWaitTimeout` 不够。相位若还停在 `ARMING`，下一次 `onLeft` 会先跑 `advance()`，自己走进 `WAITING_FOR_VIDEO`。`onAbort` 必须先把相位改成 `IDLE`、把 `flyStepAt` 设成 `NO_TIME`，并且自己不能先调用 `advance()`。
- 会话测试里的条件函数叫 `openConditions`，没有 `okConditions`。
- `ClusterFlyLink` 没有 `stop()`。停监听用控制器里现有的 `stopEverything()`，它调用 `link.close()` 并释放地址。控制器单测默认不创建 link，不要写一个不存在的 `link.stop()` 断言。
- `sendStep` 里「左滑之前仪表已经要过视频」的 `takePendingOpen` 回复必须保留。现有测试 `an open before the fly step is answered when the step goes out` 必须仍然通过。只有中止之后相位仍是 `WAITING_FOR_VIDEO` 才启动 3 秒计时；已经因为提前索流进入 `PUSHING` 时不启动。
- `FlyCommandSink.send` 改成返回 `Boolean` 之后，每个分支都要返回。导航广播返回 true。只有 `FLY_LEFT` 返回 false 才中止。`FLY_RIGHT` 返回 false 只记日志，关闭序列继续，不能再走进中止。
- 打开路径去掉 `FlyTrace.captureAfterGesture()`。直发之后再去 logcat 里找 `ThreeFingerReceiver`，会得到无意义的 `fly.extra empty`。`FlyTrace` 类和它的测试留着，只是不再从 `sendFlyCommand` 调用。
- 中止步骤挂在 `generation` 上。新的左滑会把 `generation` 加一，还没发出的 `NAV_BACK` 作废。已经发出的 `NAV_OFF` 保留，新的 `NAV_ON` 会再打开导航。
- 现有 `pushing()` 在索流之后会再跑一遍已登记的延时。3 秒回调若那时仍执行，相位已经是 `PUSHING`，必须什么都不做。

---

### Task 1: `ClusterFlyMcu` 直接发送 1885

**Files:**

- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyMcu.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLog.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyMcuTest.kt`

**Interfaces:**

- Produces:
  - `ClusterFlyMcu.payload1885(open: Boolean): ByteArray`
  - `ClusterFlyMcu.send1885(open: Boolean, binder: IBinder? = lookup()): Boolean`
  - `ClusterFlyLog.mcu1885(open: Boolean, ok: Boolean): String`

`true` 只表示 `transact` 成功且应答 `readInt() != 0`。日志文案保持 `fly.mcu 1885 left true`，不要写成「仪表已换页」。

- [ ] **Step 1: 写会失败的测试**

在 `ClusterFlyMcuTest.kt` 增加：

```kotlin
    @Test
    fun `open 1885 payload is 25 bytes with 74 then 1`() {
        val bytes = ClusterFlyMcu.payload1885(open = true)
        assertEquals(25, bytes.size)
        assertEquals(74.toByte(), bytes[0])
        assertEquals(1.toByte(), bytes[1])
        assertEquals(0, bytes.drop(2).count { it != 0.toByte() })
    }

    @Test
    fun `close 1885 payload is 25 bytes with 74 then 2`() {
        val bytes = ClusterFlyMcu.payload1885(open = false)
        assertEquals(25, bytes.size)
        assertEquals(74.toByte(), bytes[0])
        assertEquals(2.toByte(), bytes[1])
        assertEquals(0, bytes.drop(2).count { it != 0.toByte() })
    }

    @Test
    fun `send1885 writes opcode 1885 with control 0 on transaction 5`() {
        val binder = RecordingBinder(succeeds = true)
        val ok = ClusterFlyMcu.send1885(open = true, binder = binder)
        assertEquals(true, ok)
        assertEquals(5, binder.code)
        assertEquals(0.toByte(), binder.control)
        assertEquals(1885, binder.opcode)
        assertEquals(ClusterFlyMcu.payload1885(open = true).toList(), binder.payload.toList())
    }

    @Test
    fun `1885 log line names direction and binder result`() {
        assertEquals("fly.mcu 1885 left true", ClusterFlyLog.mcu1885(open = true, ok = true))
        assertEquals("fly.mcu 1885 right false", ClusterFlyLog.mcu1885(open = false, ok = false))
    }
```

- [ ] **Step 2: 运行测试，确认因为方法不存在而编译失败**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyMcuTest
```

- [ ] **Step 3: 实现发送与日志**

`ClusterFlyLog.kt`：

```kotlin
    /** Binder accepted the 1885 write. This is not the cluster changing page. */
    fun mcu1885(open: Boolean, ok: Boolean): String =
        "fly.mcu 1885 ${if (open) "left" else "right"} $ok"
```

`ClusterFlyMcu.kt`，复用已有的 `DESCRIPTOR`、`TRANSACTION_SEND`、`CONTROL`、`PAYLOAD_LENGTH`、`lookup()`：

```kotlin
    const val OPCODE_1885 = 1885
    private const val MARKER_1885: Byte = 74

    fun payload1885(open: Boolean): ByteArray {
        val bytes = ByteArray(PAYLOAD_LENGTH)
        bytes[0] = MARKER_1885
        bytes[1] = if (open) 1 else 2
        return bytes
    }

    fun send1885(open: Boolean, binder: IBinder? = lookup()): Boolean {
        if (binder == null) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeByte(CONTROL)
            data.writeInt(OPCODE_1885)
            data.writeByteArray(payload1885(open))
            if (!binder.transact(TRANSACTION_SEND, data, reply, 0)) return false
            reply.readException()
            reply.readInt() != 0
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
```

- [ ] **Step 4: 再跑 Task 1 的测试，确认通过**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyMcuTest
```

- [ ] **Step 5: 提交**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyMcu.kt common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLog.kt common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyMcuTest.kt && git commit -m "feat(fly): send opcode 1885 through the RpcService binder"
```

---

### Task 2: 会话中止，清掉会把下一次左滑推进等待的时间戳

**Files:**

- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySession.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLog.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySessionTest.kt`

**Interfaces:**

- Produces:
  - `ClusterFlySession.onAbort(nowMillis: Long): FlyEffect`
  - `ClusterFlyLog.abort(reason: String): String`

`onAbort` 只在 `ARMING` 或 `WAITING_FOR_VIDEO` 时带上收回步骤。其它相位返回空步骤，并且不把 `PUSHING` 打回空闲。开头不要调用 `advance()`。

- [ ] **Step 1: 写会失败的测试**

`ClusterFlySessionTest` 的条件函数是 `openConditions`，左滑在 1000 ms 时，`FLY_LEFT` 的时刻是 2400 ms。

```kotlin
    @Test
    fun `abort from arming returns to idle without fly right`() {
        session.onLeft(1_000L, openConditions())
        assertEquals(FlyPhase.ARMING, session.currentPhase)

        val effect = session.onAbort(1_500L)
        assertEquals(FlyPhase.IDLE, effect.phase)
        assertEquals(FlyPhase.IDLE, session.currentPhase)
        assertFalse(effect.push)
        assertEquals(listOf(FlyCommand.NAV_OFF, FlyCommand.NAV_BACK), effect.steps.map { it.command })
        assertEquals(1_500L, effect.steps[0].sendAtMillis)
        assertEquals(1_900L, effect.steps[1].sendAtMillis)
        assertFalse(effect.steps.any { it.command == FlyCommand.FLY_RIGHT })

        assertEquals(FlyPhase.IDLE, session.onWaitTimeout(3_000L).phase)

        val next = session.onLeft(3_100L, openConditions())
        assertEquals(FlyPhase.ARMING, next.phase)
        assertEquals(FlyCommand.NAV_ON, next.steps.first().command)
    }

    @Test
    fun `abort from waiting returns to idle without fly right`() {
        session.onLeft(1_000L, openConditions())
        session.onWaitTimeout(2_400L)
        assertEquals(FlyPhase.WAITING_FOR_VIDEO, session.currentPhase)

        val effect = session.onAbort(5_400L)
        assertEquals(FlyPhase.IDLE, session.currentPhase)
        assertEquals(listOf(FlyCommand.NAV_OFF, FlyCommand.NAV_BACK), effect.steps.map { it.command })
        assertFalse(effect.steps.any { it.command == FlyCommand.FLY_RIGHT })
    }

    @Test
    fun `abort during pushing does not drop the layer`() {
        pushing()
        val effect = session.onAbort(3_000L)
        assertEquals(FlyPhase.PUSHING, effect.phase)
        assertTrue(effect.steps.isEmpty())
        assertTrue(effect.push)
    }
```

- [ ] **Step 2: 运行测试，确认因为 `onAbort` 不存在而编译失败**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlySessionTest
```

- [ ] **Step 3: 实现 `onAbort`**

`ClusterFlyLog.kt`：

```kotlin
    fun abort(reason: String): String = "fly.abort $reason"
```

`ClusterFlySession.kt`。不要在第一行调用 `advance()`。`effect()` 会在相位变化时打 `fly.phase`。

```kotlin
    /**
     * 1885 没送出，或送出后 3 秒内仪表没来要视频。
     *
     * 直接回到空闲并清掉左滑时刻。先走 advance 会在时刻已过时把 ARMING 推进成等待。
     * 收回导航标志和 1887 前台。不发右滑：这次换页没有生效，补一笔关闭没有对象。
     * 正在推流时什么都不做，关闭仍由原来的右滑或断开负责。
     */
    fun onAbort(nowMillis: Long): FlyEffect {
        if (phase != FlyPhase.ARMING && phase != FlyPhase.WAITING_FOR_VIDEO) {
            return effect()
        }
        phase = FlyPhase.IDLE
        flyStepAt = NO_TIME
        closeNavOffAt = NO_TIME
        openPending = false
        closeOwed = false
        return effect(
            steps = listOf(
                FlyStep(FlyCommand.NAV_OFF, nowMillis),
                FlyStep(FlyCommand.NAV_BACK, nowMillis + ClusterFlySequencer.STEP_DELAY_MILLIS),
            ),
        )
    }
```

- [ ] **Step 4: 再跑会话测试，确认通过，并且 `a cluster that never asks gets no video` 仍是等待而不是空闲**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlySessionTest
```

- [ ] **Step 5: 提交**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySession.kt common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLog.kt common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySessionTest.kt && git commit -m "feat(fly): abort a fly that never reached the cluster"
```

---

### Task 3: 控制器只在 1885 送出后等待，失败或超时则收回

**Files:**

- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyWarning.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyControllerTest.kt`

**Interfaces:**

- Consumes:
  - `ClusterFlyMcu.send1885(open: Boolean, binder: IBinder? = lookup()): Boolean`
  - `ClusterFlySession.onAbort(nowMillis: Long): FlyEffect`
  - `ClusterFlyLog.mcu1885`、`ClusterFlyLog.abort`
- Produces:
  - `fun interface FlyCommandSink { fun send(command: FlyCommand): Boolean }`
  - `ClusterFlyWarning.pageUnchanged(): String`，返回 `仪表没有换页，请再滑一次`
  - 控制器常量 `VIDEO_ASK_TIMEOUT_MILLIS = 3_000L`

现有测试的 `commands = { sent.add(it) }` 不用改：`MutableList.add` 返回 true，表示送出成功。

- [ ] **Step 1: 写会失败的控制器测试**

在 `ClusterFlyControllerTest.kt` 增加下面的辅助和三个测试。现有 `controller()` 保持不动。

```kotlin
    private fun failedLeft(): ClusterFlyController = ClusterFlyController(
        commands = { command ->
            sent.add(command)
            command != FlyCommand.FLY_LEFT
        },
        showWarning = { warnings.add(it) },
        setMainVideoSubscribed = {},
        log = { lines.add(it) },
        clock = { now },
        postDelayed = { delay, action -> scheduled.add(delay to action) },
    )

    private fun openToLeft(controller: ClusterFlyController) {
        controller.onGesture(Decision.FLY_LEFT)
        now = 1_400L
        scheduled.removeAt(0).second()
        now = 2_400L
        scheduled.removeAt(0).second()
    }

    @Test
    fun `failed 1885 aborts without waiting or fly right`() {
        val controller = failedLeft()
        openToLeft(controller)

        assertEquals(
            listOf(FlyCommand.NAV_ON, FlyCommand.NAV_FRONT, FlyCommand.FLY_LEFT, FlyCommand.NAV_OFF),
            sent,
        )
        assertEquals(listOf(400L), scheduled.map { it.first })
        assertFalse(lines.contains("fly.phase WAITING_FOR_VIDEO"))
        assertTrue(lines.contains("fly.abort 1885 left false"))
        assertEquals(listOf(ClusterFlyWarning.pageUnchanged()), warnings)

        now = 2_400L
        controller.onGesture(Decision.FLY_LEFT)
        assertEquals(listOf("刚才那次还没完成，请稍等"), warnings.drop(1))
        assertFalse(sent.drop(4).contains(FlyCommand.NAV_ON))

        now = 3_400L
        controller.onGesture(Decision.FLY_LEFT)
        assertEquals(FlyCommand.NAV_ON, sent.last())
        scheduled.removeAt(0).second()
        assertFalse(sent.contains(FlyCommand.NAV_BACK))
    }

    @Test
    fun `no video ask within 3000ms aborts once`() {
        val controller = controller()
        openToLeft(controller)
        assertTrue(lines.contains("fly.phase WAITING_FOR_VIDEO"))
        assertEquals(listOf(3_000L), scheduled.map { it.first })

        now = 5_400L
        scheduled.removeAt(0).second()

        assertEquals(
            listOf(FlyCommand.NAV_ON, FlyCommand.NAV_FRONT, FlyCommand.FLY_LEFT, FlyCommand.NAV_OFF),
            sent,
        )
        assertFalse(sent.contains(FlyCommand.FLY_RIGHT))
        assertEquals(listOf(ClusterFlyWarning.pageUnchanged()), warnings)
        assertTrue(lines.contains("fly.abort cluster silent"))
    }

    @Test
    fun `an open ask cancels the silent timeout`() {
        val controller = controller()
        openToLeft(controller)
        now = 2_500L
        controller.onPacket(openAsk())
        runScheduled()

        assertFalse(sent.contains(FlyCommand.NAV_OFF))
        assertFalse(sent.contains(FlyCommand.NAV_BACK))
        assertFalse(warnings.contains(ClusterFlyWarning.pageUnchanged()))
        assertTrue(lines.contains("fly.push start forward") || lines.contains("fly.phase PUSHING"))
    }
```

`openToLeft` 假定左滑当时只登记了 400 ms 和 1400 ms 两笔，执行第二笔时才登记 3 秒超时或中止的 400 ms。这和现有 `left sends navigation, then front, then the fly` 的登记顺序一致。

- [ ] **Step 2: 运行控制器测试，确认新测试编译失败或断言失败，旧测试先不要改到能假通过**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyControllerTest
```

- [ ] **Step 3: 改发送口、控制器和 Activity**

`ClusterFlyWarning.kt` 增加，不要改现有闸门文案：

```kotlin
    fun pageUnchanged(): String = "仪表没有换页，请再滑一次"
```

`FlyCommandSink`：

```kotlin
fun interface FlyCommandSink {
    fun send(command: FlyCommand): Boolean
}
```

`ClusterFlyController` 增加 `private var videoTimeoutGeneration = 0`。伴生对象增加 `const val VIDEO_ASK_TIMEOUT_MILLIS = 3_000L`。

把 `sendStep` 换成下面这一版。`takePendingOpen` 的回复保留。`lastCommandAt` 在中止之前更新，这样 1 秒间隔从这次失败起算。

```kotlin
    private fun sendStep(step: FlyStep) {
        log(ClusterFlyLog.command(step.command))
        val ok = commands.send(step.command)
        lastCommandAt = clock()
        if (step.command != FlyCommand.FLY_LEFT) return
        if (!ok) {
            log(ClusterFlyLog.abort("1885 left false"))
            abortSession()
            return
        }
        val effect = session.onWaitTimeout(clock())
        if (effect.push && session.takePendingOpen()) {
            link?.reply(
                listOf(
                    reply(FlyReply.READY, ClusterFlyProtocol.videoReady()),
                    reply(FlyReply.START, ClusterFlyProtocol.videoStart()),
                ),
            )
        }
        handle(effect)
        if (session.currentPhase == FlyPhase.WAITING_FOR_VIDEO) scheduleVideoTimeout()
    }

    private fun scheduleVideoTimeout() {
        val expected = ++videoTimeoutGeneration
        postDelayed(VIDEO_ASK_TIMEOUT_MILLIS) {
            synchronized(lock) {
                if (videoTimeoutGeneration != expected) return@synchronized
                if (session.currentPhase != FlyPhase.WAITING_FOR_VIDEO) return@synchronized
                log(ClusterFlyLog.abort("cluster silent"))
                abortSession()
            }
        }
    }

    private fun abortSession() {
        videoTimeoutGeneration++
        val effect = session.onAbort(clock())
        showWarning(ClusterFlyWarning.pageUnchanged())
        handle(effect)
        stopEverything()
    }
```

`abortSession` 不要自己再 `generation++`。`handle` 发现有步骤时会加一，中止的 `NAV_BACK` 使用加一之后的值。新左滑再次加一，没发出的 `NAV_BACK` 就对不上。

在 `onPacket` 的 `VideoAsk.OPEN` 分支里，算出 `effect` 之后、如果 `effect.push` 为真，执行 `videoTimeoutGeneration++`。被拒绝的索流不取消计时，让 3 秒中止仍然发生。`holdOpen` 发生在左滑送出之前，那时还没有计时。

`CarPlayHostActivity.sendFlyCommand` 改成返回 `Boolean`。四个分支都返回。`FLY_LEFT` 和 `FLY_RIGHT` 都调用 `ClusterFlyMcu.send1885`。删除这两支里的 `FlyTrace.captureAfterGesture()`。

```kotlin
    private fun sendFlyCommand(command: FlyCommand): Boolean = when (command) {
        FlyCommand.NAV_FRONT, FlyCommand.NAV_BACK -> {
            val front = command == FlyCommand.NAV_FRONT
            val ok = ClusterFlyMcu.send(front)
            DiagLog.i(ClusterFlyLog.TAG, ClusterFlyLog.mcu(front, ok))
            ok
        }
        FlyCommand.NAV_ON -> {
            sendBroadcast(
                Intent(ClusterFlySequencer.ACTION_NAV_STATE).apply {
                    setClassName(
                        ClusterFlySequencer.PACKAGE_EXTRASERVICE,
                        ClusterFlySequencer.RECEIVER_NAV_STATE,
                    )
                    putExtra(ClusterFlySequencer.EXTRA_KEY_TYPE, ClusterFlySequencer.KEY_TYPE_NAV_STATE)
                    putExtra(ClusterFlySequencer.EXTRA_STATE, ClusterFlySequencer.STATE_NAV_ON)
                },
            )
            true
        }
        FlyCommand.NAV_OFF -> {
            sendBroadcast(
                Intent(ClusterFlySequencer.ACTION_NAV_STATE).apply {
                    setClassName(
                        ClusterFlySequencer.PACKAGE_EXTRASERVICE,
                        ClusterFlySequencer.RECEIVER_NAV_STATE,
                    )
                    putExtra(ClusterFlySequencer.EXTRA_KEY_TYPE, ClusterFlySequencer.KEY_TYPE_NAV_STATE)
                    putExtra(ClusterFlySequencer.EXTRA_STATE, ClusterFlySequencer.STATE_NAV_OFF)
                },
            )
            true
        }
        FlyCommand.FLY_LEFT, FlyCommand.FLY_RIGHT -> {
            val open = command == FlyCommand.FLY_LEFT
            val ok = ClusterFlyMcu.send1885(open)
            DiagLog.i(ClusterFlyLog.TAG, ClusterFlyLog.mcu1885(open, ok))
            ok
        }
    }
```

构造控制器的那一处保持 `commands = { command -> sendFlyCommand(command) }`，返回值会传回去。

`FLY_RIGHT` 返回 false 时不要调用 `abortSession`。用户右滑和断开仍要发 1885 关闭。

- [ ] **Step 4: 跑飞屏包的全部单元测试**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.fly.*"
```

必须仍然通过的旧测试：`left sends navigation, then front, then the fly`、`an open before the fly step is answered when the step goes out`、`a frame is packetized on the fly thread, not the caller's`。最后这个会在索流后执行 3 秒回调；回调必须看到相位已是 `PUSHING` 后直接返回。

- [ ] **Step 5: 提交**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyWarning.kt common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyControllerTest.kt && git commit -m "feat(fly): abort when 1885 is rejected or the cluster stays silent"
```

---

### Task 4: 提示放到画面最上面，同一次失败只出现一次

**Files:**

- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`（`buildContentView` 里那条 `flyWarning`）

**Interfaces:**

- Consumes: `ClusterFlyWarning.pageUnchanged()`，已由 Task 3 在 `abortSession` 里调用一次。本任务不再增加调用点。

行为已经由 Task 3 的测试锁住：一次中止只往 `warnings` 加一行。本任务只改它画在哪里。

- [ ] **Step 1: 移动现有 TextView**

不要新建窗口，不要用 `Dialog`，不要给视频 `setZOrderOnTop`。CarPlay 画面是根布局里的第一个子视图，这条字是最后一个，画在视频上面。视频层没有被抬到窗口之上，所以画面盖不住这行字。连接页在有画面之后是 `GONE`。快捷栏加在这行字之前，也盖不住它。

闸门拒绝（雷达、倒车、滑得太快）使用同一条 TextView，会一起挪到上面。仍由现有的 `FLY_WARNING_MILLIS`（2000 ms）收起。

把 `buildContentView` 里 `flyWarning` 的布局从底部改成顶部，并换掉旁边那句「放在快捷栏旁边」的注释：

```kotlin
        // Last child, so it draws over the CarPlay surface. The surface is not z-ordered on top.
        // One failure shows one line; the view hides itself after two seconds.
        root.addView(
            flyWarning,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(24)
            },
        )
```

- [ ] **Step 2: 确认 Task 3 的控制器测试仍然通过**

这条没有新的单元测试可跑：位置是视图参数，现有测试不启动 Activity。跑控制器测试，确认提示文案没有被本任务改掉。

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyControllerTest
```

- [ ] **Step 3: 提交**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && git add common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt && git commit -m "fix(fly): show the cluster-page failure at the top of the picture"
```

---

### Task 5: 全量单测与本地包

- [ ] **Step 1: 跑全部单测**

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew testDebugUnitTest
```

- [ ] **Step 2: 打本地包**

需要 `DIPLAY_VENDOR_HOP_DIR` 和 `~/.diplay-signing`（或已导出的 `ANDROID_KEYSTORE_PASSWORD`）。缺其中任何一个就停在这里，说明缺什么，不要改脚本去绕过。

```bash
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./scripts/package-geely.sh local
```

产物是 `dist/DiPlay-Legacy-Geely-V050-local.apk`。脚本不会拷到 `~/Downloads`。默认不带参数打出来的是正式包，不要用那一条来装这辆试验车。

上车看这四条：

- 左滑后有 `fly.mcu 1885 left true` 或 `fly.mcu 1885 left false`。不再出现这次左滑的 `fly.extra empty`。
- 返回 false，或 true 之后 3 秒没有 `fly.tcp accept`：有一行 `fly.abort`，接着 `NAV_OFF` 和 `NAV_BACK`，没有 `FLY_RIGHT`，相位回到 `IDLE`。画面最上面出现一次「仪表没有换页，请再滑一次」，约两秒后消失。
- 回到空闲后再左滑，1 秒内提示「刚才那次还没完成，请稍等」。过了 1 秒会重新进入 `ARMING`，不再被 `phase != IDLE` 吃掉。
- 仪表真的来连并进入推流之后，右滑或断开仍发 `FLY_RIGHT`，再 `NAV_OFF`，再 `NAV_BACK`。
