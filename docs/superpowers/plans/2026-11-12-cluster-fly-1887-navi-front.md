# 星瑞 E01：三指飞屏前先告诉仪表「导航在前台」

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 下次上车，CarPlay 已经连上时三指向左滑，仪表地图层下面出现 CarPlay 的画面，车速和挡位仍由仪表自己画；向右滑时这一层盖上，画面停掉。

**Architecture:** 昨天 17:27 的日志里，1028 已经在听，三指也已经让 Extraservice 发出换页指令 1885，仪表没有来连。原厂高德能飞，是因为切页之前 Extraservice 还会发另一笔：功能号 1887，25 字节，第 0 字节 67，第 1 字节 1，日志原文是 `Navi front`。这笔告诉仪表「导航在前台」，仪表才会来拨 1028。DiPlay 现在只发了「正在导航」广播（`EXTRA_STATE` 8）和三指，1887 没有发。这一版由 DiPlay 按原厂同一笔 Binder 自己发出 1887，再隔 1000 毫秒发三指。1028 的监听、301/303 应答、RTP 到 `10.255.255.106:9026` 保持现在的实现。

**Tech Stack:** Kotlin，Android 5.1 / API 22，JUnit4，Robolectric 4.17（仅测试），现有 `DiagLog`，现有 `ClusterFlyLink`。

**Spec:** 本文件就是规格。不要执行 `docs/superpowers/plans/2026-11-12-cluster-fly-net-tbox-fix.md`。不要执行这三份已作废的计划：`2026-11-05-geely-e01-cluster-overlay-plan.md`、`2026-11-05-geely-e01-cluster-flyscreen-implementation-plan.md`、`2026-11-05-geely-e01-cluster-projection-xcnaviservice-plan.md`。

## Global Constraints

- 最低 API 是 22。发送代码只用 `Parcel.writeInterfaceToken`、`writeByte`、`writeInt`、`writeByteArray`、`IBinder.transact`。这些从 API 1 就有。
- 不改 `/system`，不替换 `Navi.apk`、`XCNaviService`、`Extraservice`。不用 `DimInteraction`，不建 `VirtualDisplay`，不写 `/dev/graphics/fb0` 或 `fb2`。
- 不发 `ecarx.intent.action.MAP_PRIOR`。那笔是把仪表窗口 1001 交给高德。
- 不改 `FlyNet` 的排除名单，不在 `tbox0` 上添加 `10.255.255.101`，不改 `ClusterFlyLink` 的监听地址和 `ClusterFlyProtocol` 的应答字节。
- 不 `Class.forName("com.neusoft.alfus.RpcManager")`。这个类不在 DiPlay 的 classpath 上；它的 `getInstance()` 还会向 `RpcService` 注册监听，服务不在时直接返回 null。
- 1887 的控制字节是 `0`。`67` 在 25 字节负载的第 0 字节，不写进控制字节。事务号是 `5`。服务名是 `RpcService`。接口名是 `com.neusoft.alfus.os.IRpcService`。只发一次，不重试，不在主线程上 `sleep`。
- 不发隐式 `EXTRA_STATE=3`。`AvmReceiver` 里虽有同样的 1887 字节，但 `ExtraService` 注册它时用的是 AVM/升级动作（字段 `g`–`n`、`r`、`s`），没有字段 `q`（`AUTONAVI_STANDARD_BROADCAST_SEND`）。Android 5.1 上带组件名的广播只找清单里的接收器，点名这个动态接收器也到不了。`XCNaviService` 的 `a(int)` 只处理 107、108、109，`EXTRA_STATE` 3 不会变成前台状态。
- 打开顺序：`EXTRA_STATE=8` 立刻发；400 毫秒后发 1887 前台；再隔 1000 毫秒发 `direction=left`。盖上顺序：先 `direction=right`，400 毫秒后 `EXTRA_STATE=9`，右滑后 1000 毫秒发 1887 后台。先清导航标志会让原厂丢掉向右滑。
- 1887 和 1885 是两条 MCU 指令，至少隔 1000 毫秒。`EXTRA_STATE` 8 和 9 是广播，不是 MCU。不要循环补发。
- `direction` 只能是 `left` 或 `right`。倒车、雷达、紧急电话时不发。这些状态若在播放中途出现，立刻停止推流。
- 日志不写 NAL、不写 RTP 包体、不写手机地址、不写那 25 字节的全文。1887 只记方向和成败。
- 每个代码任务都有能在电脑上跑的 JUnit。车上验收只在最后一节。测试用 Robolectric sdk 29，因为工程里现成的 Robolectric 不带 sdk 22；发送代码本身仍只使用 API 22 有的调用。

---

## 为什么是这一笔

原厂 `RpcManager.sendMessage(int, byte[])` 内部调用的是 `IRpcService.sendMessage((byte) 0, opecode, data)`。Extraservice 在协议 1001、地图类型 10、地图状态 3 时写：

```text
byte[25]，[0]=67，[1]=1，其余为 0
RpcManager.getInstance().sendMessage(1887, 这 25 字节)
日志：Navi front
```

后台是同一功能号，`[1]=0`，日志是 `Navi back`。三指换页是另一笔：`[0]=74`，左是 `[1]=1`，右是 `[1]=2`，功能号 1885，由 `ThreeFingerReceiver` 在导航标志为真时发送。DiPlay 已经能让 1885 发出。缺的是它前面的 1887。

Binder 代理（`IRpcService.Stub`，`TRANSACTION_sendMessage = 5`）的写入顺序是：接口名、控制字节、功能号、字节数组。应答先 `readException()`，再 `readInt()!=0` 表示成功。

## 文件

- 新建 `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyMcu.kt`：25 字节和那一次 Binder 发送。
- 改 `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySequencer.kt`：打开三步、盖上三步。
- 改 `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySession.kt`：等左滑的时刻改成左滑那一步的时刻；推迟的盖上包含 1887 后台。
- 改 `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLog.kt`：1887 的一行日志。
- 改 `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` 的 `sendFlyCommand`：前台和后台走 Binder，导航广播和三指仍是现在的显式广播。这一改和顺序放在同一个任务里，避免枚举加上之后 `when` 编不过。
- 改对应的 `ClusterFlySequencerTest`、`ClusterFlySessionTest`、`ClusterFlyControllerTest`。新建 `ClusterFlyMcuTest`。

---

### Task 1: 1887 的字节和一次 Binder 发送

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyMcu.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLog.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyMcuTest.kt`

**Interfaces:**
- Consumes: 无。
- Produces: `ClusterFlyMcu.payload(front: Boolean): ByteArray`，`ClusterFlyMcu.send(front: Boolean, binder: IBinder? = lookup()): Boolean`，`ClusterFlyMcu.lookup(): IBinder?`，`ClusterFlyLog.mcu(front: Boolean, ok: Boolean): String`。后面的 Activity 只调用 `send` 和 `mcu`。

- [ ] **Step 1: 写会失败的测试**

新建 `ClusterFlyMcuTest.kt`：

```kotlin
package com.shilapi.xcertplay.instrument.fly

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterFlyMcuTest {

    @Test
    fun `front payload is 25 bytes with 67 then 1`() {
        val bytes = ClusterFlyMcu.payload(front = true)
        assertEquals(25, bytes.size)
        assertEquals(67.toByte(), bytes[0])
        assertEquals(1.toByte(), bytes[1])
        assertEquals(0, bytes.drop(2).count { it != 0.toByte() })
    }

    @Test
    fun `back payload is 25 bytes with 67 then 0`() {
        val bytes = ClusterFlyMcu.payload(front = false)
        assertEquals(67.toByte(), bytes[0])
        assertEquals(0.toByte(), bytes[1])
    }

    @Test
    fun `send writes control 0 opcode 1887 and the front payload on transaction 5`() {
        val binder = RecordingBinder(succeeds = true)
        val ok = ClusterFlyMcu.send(front = true, binder = binder)
        assertEquals(true, ok)
        assertEquals(5, binder.code)
        assertEquals(0.toByte(), binder.control)
        assertEquals(1887, binder.opcode)
        assertEquals(ClusterFlyMcu.payload(front = true).toList(), binder.payload.toList())
    }

    @Test
    fun `a missing service is a failed send`() {
        assertFalse(ClusterFlyMcu.send(front = true, binder = null))
    }

    @Test
    fun `the log line names the direction and the result`() {
        assertEquals("fly.mcu 1887 front true", ClusterFlyLog.mcu(front = true, ok = true))
        assertEquals("fly.mcu 1887 back false", ClusterFlyLog.mcu(front = false, ok = false))
    }

    private class RecordingBinder(private val succeeds: Boolean) : Binder() {
        var code: Int = -1
        var control: Byte = -1
        var opcode: Int = -1
        var payload: ByteArray = ByteArray(0)

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            this.code = code
            data.enforceInterface(ClusterFlyMcu.DESCRIPTOR)
            control = data.readByte()
            opcode = data.readInt()
            payload = data.createByteArray() ?: ByteArray(0)
            reply?.writeNoException()
            reply?.writeInt(if (succeeds) 1 else 0)
            return true
        }
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyMcuTest`

Expected: 编译失败，`ClusterFlyMcu` 和 `ClusterFlyLog.mcu` 还不存在。

- [ ] **Step 3: 实现**

`ClusterFlyLog.kt` 末尾加上：

```kotlin
fun mcu(front: Boolean, ok: Boolean): String =
    "fly.mcu 1887 ${if (front) "front" else "back"} $ok"
```

新建 `ClusterFlyMcu.kt`：

```kotlin
package com.shilapi.xcertplay.instrument.fly

import android.os.IBinder
import android.os.Parcel

/**
 * The factory's "navigation is in front" message.
 *
 * Extraservice sends opcode 1887 through RpcManager.sendMessage. That method always uses control
 * byte 0; 67 lives in the first byte of the 25-byte body. The class itself is not on this app's
 * classpath, so this talks to the RpcService binder with the same parcel the proxy writes.
 */
object ClusterFlyMcu {
    const val SERVICE = "RpcService"
    const val DESCRIPTOR = "com.neusoft.alfus.os.IRpcService"
    const val TRANSACTION_SEND = 5
    const val CONTROL: Byte = 0
    const val OPCODE = 1887
    const val PAYLOAD_LENGTH = 25
    private const val MARKER: Byte = 67

    fun payload(front: Boolean): ByteArray {
        val bytes = ByteArray(PAYLOAD_LENGTH)
        bytes[0] = MARKER
        bytes[1] = if (front) 1 else 0
        return bytes
    }

    fun send(front: Boolean, binder: IBinder? = lookup()): Boolean {
        if (binder == null) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeByte(CONTROL)
            data.writeInt(OPCODE)
            data.writeByteArray(payload(front))
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

    /** Same lookup E01PrivilegedBridge uses, aimed at RpcService. */
    fun lookup(): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        getService.invoke(null, SERVICE) as? IBinder
    } catch (_: Throwable) {
        null
    }
}
```

- [ ] **Step 4: 再跑，全部通过**

Run: 同上。

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyMcu.kt \
  common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyLog.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyMcuTest.kt
git commit -m "feat: send the factory 1887 navigation-front message"
```

---

### Task 2: 打开和盖上的三步时刻

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySequencer.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` 的 `sendFlyCommand`（约第 2714 行）
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySequencerTest.kt`

**Interfaces:**
- Consumes: 任务 1 的 `ClusterFlyMcu.send`、`ClusterFlyLog.mcu`。现有 `FlyStep`、`ClusterFlyGate`、`STEP_DELAY_MILLIS = 400`。
- Produces: `FlyCommand.NAV_FRONT`、`FlyCommand.NAV_BACK`，`ClusterFlySequencer.MCU_GAP_MILLIS = 1000`。`open` 在手势时刻返回三步，`close` 返回三步。门拒绝时仍是空列表。`sendFlyCommand` 对这两条走 Binder，导航广播和三指仍是现在的显式广播。

`sendFlyCommand` 的 `when` 是穷尽的。枚举一加，这里不补分支，`common` 就编不过，所以发送和顺序在同一步里落地。不另写 Activity 测试：Parcel 已在 `ClusterFlyMcuTest` 对过。

这一步的单元测试只跑 `ClusterFlySequencerTest` 和 `ClusterFlyMcuTest`。`ClusterFlySessionTest` 和 `ClusterFlyControllerTest` 里写死的 1400 毫秒在任务 3、4 改，现在跑它们会红。

- [ ] **Step 1: 改测试，让它按新顺序失败**

把 `ClusterFlySequencerTest` 里前两个测试换成：

```kotlin
@Test
fun `open arms the cluster before the left swipe`() {
    val steps = ClusterFlySequencer.open(1_000L, openConditions())
    assertEquals(
        listOf(
            FlyStep(FlyCommand.NAV_ON, 1_000L),
            FlyStep(FlyCommand.NAV_FRONT, 1_400L),
            FlyStep(FlyCommand.FLY_LEFT, 2_400L),
        ),
        steps,
    )
    assertEquals(1_000L, steps[2].sendAtMillis - steps[1].sendAtMillis)
}

@Test
fun `close keeps navigation until after the right swipe`() {
    val steps = ClusterFlySequencer.close(5_000L, openConditions(millisSinceLastClusterCommand = 5_000))
    assertEquals(
        listOf(
            FlyStep(FlyCommand.FLY_RIGHT, 5_000L),
            FlyStep(FlyCommand.NAV_OFF, 5_400L),
            FlyStep(FlyCommand.NAV_BACK, 6_000L),
        ),
        steps,
    )
    assertEquals(1_000L, steps[2].sendAtMillis - steps[0].sendAtMillis)
}
```

`ecall produces no command` 保持不动。

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlySequencerTest`

Expected: FAIL。现在打开只有两步，第二步在 1400，没有 `NAV_FRONT`。

- [ ] **Step 3: 实现**

`FlyCommand` 改为：

```kotlin
enum class FlyCommand { NAV_ON, NAV_FRONT, FLY_LEFT, FLY_RIGHT, NAV_OFF, NAV_BACK }
```

类注释改成：打开是导航广播、400 毫秒后 1887 前台、再隔 1000 毫秒左滑。盖上是右滑、400 毫秒后清导航标志、右滑后 1000 毫秒 1887 后台。先清标志会让原厂丢掉右滑。

`STEP_DELAY_MILLIS` 下面加上：

```kotlin
/** Two MCU opcodes, 1887 and 1885, must be at least this far apart. */
const val MCU_GAP_MILLIS = 1_000L
```

`open` 的列表改为：

```kotlin
listOf(
    FlyStep(FlyCommand.NAV_ON, nowMillis),
    FlyStep(FlyCommand.NAV_FRONT, nowMillis + STEP_DELAY_MILLIS),
    FlyStep(FlyCommand.FLY_LEFT, nowMillis + STEP_DELAY_MILLIS + MCU_GAP_MILLIS),
)
```

`close` 的列表改为：

```kotlin
listOf(
    FlyStep(FlyCommand.FLY_RIGHT, nowMillis),
    FlyStep(FlyCommand.NAV_OFF, nowMillis + STEP_DELAY_MILLIS),
    FlyStep(FlyCommand.NAV_BACK, nowMillis + MCU_GAP_MILLIS),
)
```

把 `sendFlyCommand` 从「组装一个 Intent 再统一 `sendBroadcast`」改成下面这样。`NAV_ON`、`NAV_OFF`、`FLY_LEFT`、`FLY_RIGHT` 的 Intent 字段保持原样。

```kotlin
private fun sendFlyCommand(command: FlyCommand) {
    when (command) {
        FlyCommand.NAV_FRONT, FlyCommand.NAV_BACK -> {
            val front = command == FlyCommand.NAV_FRONT
            val ok = ClusterFlyMcu.send(front)
            DiagLog.i(ClusterFlyLog.TAG, ClusterFlyLog.mcu(front, ok))
            return
        }

        FlyCommand.NAV_ON -> sendBroadcast(
            Intent(ClusterFlySequencer.ACTION_NAV_STATE)
                .setClassName(ClusterFlySequencer.PACKAGE_EXTRASERVICE, ClusterFlySequencer.RECEIVER_NAV_STATE)
                .putExtra(ClusterFlySequencer.EXTRA_KEY_TYPE, ClusterFlySequencer.KEY_TYPE_NAV_STATE)
                .putExtra(ClusterFlySequencer.EXTRA_STATE, ClusterFlySequencer.STATE_NAV_ON),
        )

        FlyCommand.NAV_OFF -> sendBroadcast(
            Intent(ClusterFlySequencer.ACTION_NAV_STATE)
                .setClassName(ClusterFlySequencer.PACKAGE_EXTRASERVICE, ClusterFlySequencer.RECEIVER_NAV_STATE)
                .putExtra(ClusterFlySequencer.EXTRA_KEY_TYPE, ClusterFlySequencer.KEY_TYPE_NAV_STATE)
                .putExtra(ClusterFlySequencer.EXTRA_STATE, ClusterFlySequencer.STATE_NAV_OFF),
        )

        FlyCommand.FLY_LEFT -> sendBroadcast(
            Intent(ClusterFlySequencer.ACTION_THREE_FINGER)
                .setClassName(ClusterFlySequencer.PACKAGE_EXTRASERVICE, ClusterFlySequencer.RECEIVER_THREE_FINGER)
                .putExtra(ClusterFlySequencer.EXTRA_DIRECTION, ClusterFlySequencer.DIRECTION_LEFT),
        )

        FlyCommand.FLY_RIGHT -> sendBroadcast(
            Intent(ClusterFlySequencer.ACTION_THREE_FINGER)
                .setClassName(ClusterFlySequencer.PACKAGE_EXTRASERVICE, ClusterFlySequencer.RECEIVER_THREE_FINGER)
                .putExtra(ClusterFlySequencer.EXTRA_DIRECTION, ClusterFlySequencer.DIRECTION_RIGHT),
        )
    }
    if (command == FlyCommand.FLY_LEFT || command == FlyCommand.FLY_RIGHT) {
        FlyTrace.captureAfterGesture()
    }
}
```

函数上方的注释改为：导航广播和三指仍点名原厂接收器。1887 不走广播，直接写 `RpcService`。

- [ ] **Step 4: 再跑测试并编译**

Run:

```bash
./gradlew :common:testDebugUnitTest \
  --tests com.shilapi.xcertplay.instrument.fly.ClusterFlySequencerTest \
  --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyMcuTest
./gradlew :common:compileDebugKotlin
```

Expected: 测试 PASS，编译成功。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySequencer.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySequencerTest.kt \
  common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt
git commit -m "feat: arm the cluster 1000 ms before the fly page change"
```

---

### Task 3: 等画面的时刻跟着左滑走，盖上也带 1887 后台

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySession.kt`（`onLeft` 约第 71 行，`scheduleClose` 约第 227 行）
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySessionTest.kt`

**Interfaces:**
- Consumes: `ClusterFlySequencer.open` / `close` 的三步，`MCU_GAP_MILLIS`。
- Produces: `flyStepAt` 等于这次打开里 `FLY_LEFT` 的 `sendAtMillis`。推迟的盖上三步与 `close` 同形。`closeNavOffAt` 等于最后一步（`NAV_BACK`）的时刻。

左滑若仍在手势后 400 毫秒就进入等待，1885 还没发，提前来的 301 会被当成可以推流。`scheduleClose` 不能改成调用 `ClusterFlySequencer.close`：调用时「距上次指令」仍小于 1000，门会返回空列表。时刻要按边界自己排，形状与 `close` 相同。

- [ ] **Step 1: 改测试里的左滑时刻**

`pushing()` 改为：

```kotlin
/** Left at 1000 ms: `FLY_LEFT` is due at 2400 ms. */
private fun pushing(): ClusterFlySession {
    session.onLeft(1_000L, openConditions())
    session.onWaitTimeout(2_400L)
    session.onVideoAsk(VideoAsk.OPEN, 2_500L)
    return session
}
```

`video starts only after the cluster asks` 里的 `onWaitTimeout(1_400L)` 改为 `onWaitTimeout(2_400L)`。

`an open under radar is refused` 里的 `onWaitTimeout(1_400L)` 改为 `onWaitTimeout(2_400L)`。询问时刻 `1_500L` 改为 `2_500L`。

`an open during arming starts pushing at the fly step` 里的 `onWaitTimeout(1_400L)` 改为 `onWaitTimeout(2_400L)`。`1_200L` 的提前询问保持不动，那时仍是 `ARMING`。

`radar during playback closes once it clears` 的最后一行断言改为：

```kotlin
assertEquals(FlyCommand.NAV_BACK, cleared.steps.last().command)
```

`no video before the left step is due` 和 `a cluster that never asks gets no video` 的时刻不用改：1200 仍早于 2400，4500 仍晚于 2400。

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlySessionTest`

Expected: FAIL。`onLeft` 把 `flyStepAt` 写成手势后 400 毫秒，`onWaitTimeout(2400)` 的断言对不上新测试想固定的「左滑才开始等」；盖上的最后一步仍是 `NAV_OFF`。

- [ ] **Step 3: 实现**

`onLeft` 里赋值改为：

```kotlin
val steps = ClusterFlySequencer.open(nowMillis, conditions)
phase = FlyPhase.ARMING
flyStepAt = steps.first { it.command == FlyCommand.FLY_LEFT }.sendAtMillis
return effect(steps = steps)
```

`scheduleClose` 里的两步列表改为：

```kotlin
val steps = listOf(
    FlyStep(FlyCommand.FLY_RIGHT, at),
    FlyStep(FlyCommand.NAV_OFF, at + ClusterFlySequencer.STEP_DELAY_MILLIS),
    FlyStep(FlyCommand.NAV_BACK, at + ClusterFlySequencer.MCU_GAP_MILLIS),
)
```

`closeNavOffAt = at + ClusterFlySequencer.STEP_DELAY_MILLIS` 改为 `closeNavOffAt = steps.last().sendAtMillis`。

`close()` 已经用最后一步的时刻，序列改完后自然落到 `NAV_BACK`，不用再写一套。

- [ ] **Step 4: 再跑 SessionTest**

Run: 同上。

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySession.kt \
  common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlySessionTest.kt
git commit -m "fix: wait for video until the fly page command has gone out"
```

---

### Task 4: 控制器测试跟着新时刻走

**Files:**
- Test: `common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyControllerTest.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt` 里两处把窗口写成「400 毫秒」的注释（约第 304 行、约第 380 行）。行为不用改：步是会话给的，`sendStep` 已经会记每条指令并在 `FLY_LEFT` 时调用 `onWaitTimeout`。

**Interfaces:**
- Consumes: 任务 2、3 的三步和 `flyStepAt`。
- Produces: 无新类型。控制器测试与新时刻一致。

- [ ] **Step 1: 改控制器测试**

`pushing()` 改为：

```kotlin
/** Gesture at 1000, front at 1400, left at 2400, the cluster's ask at 2500. */
private fun pushing(frameExecutor: java.util.concurrent.ExecutorService? = null): ClusterFlyController {
    val controller = controller(frameExecutor)
    controller.onGesture(Decision.FLY_LEFT)
    now = 2_400L
    runScheduled()
    now = 2_500L
    controller.onPacket(openAsk())
    runScheduled()
    return controller
}
```

`left sends navigation before the fly` 整段换成：

```kotlin
@Test
fun `left sends navigation, then front, then the fly`() {
    controller().onGesture(Decision.FLY_LEFT)

    assertEquals(listOf(FlyCommand.NAV_ON), sent)
    assertEquals(listOf(400L, 1_400L), scheduled.map { it.first })
    assertTrue(lines.contains("fly.command NAV_ON"))
    assertFalse(lines.contains("fly.command NAV_FRONT"))
    assertFalse(lines.contains("fly.command FLY_LEFT"))

    now = 1_400L
    scheduled[0].second()
    assertEquals(listOf(FlyCommand.NAV_ON, FlyCommand.NAV_FRONT), sent)
    assertFalse(lines.contains("fly.command FLY_LEFT"))

    now = 2_400L
    scheduled[1].second()
    assertEquals(listOf(FlyCommand.NAV_ON, FlyCommand.NAV_FRONT, FlyCommand.FLY_LEFT), sent)
}
```

`an open before the fly step is answered when the step goes out`：提前询问仍在 `now = 1_200L`。把后面的 `now = 1_400L` 改成 `now = 2_400L`，再 `runScheduled()`。断言不变：那时才出现 `fly.reply ready` 和 `fly.reply start`。

`open is answered ready then start`：`now = 1_400L` 改成 `now = 2_400L`，然后再 `runScheduled()`，询问放在 `now = 2_500L`。

`car play ending closes the layer it opened`：`pushing()` 之后把 `now = 2_600L` 改成 `now = 3_400L`（距左滑发送已满 1000 毫秒，关闭不必再等）。断言改为：

```kotlin
assertEquals(
    listOf(FlyCommand.NAV_ON, FlyCommand.NAV_FRONT, FlyCommand.FLY_LEFT, FlyCommand.FLY_RIGHT),
    sent,
)
runScheduled()
assertEquals(FlyCommand.NAV_BACK, sent.last())
```

- [ ] **Step 2: 跑测试，确认在改注释之前就已经能通过**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyControllerTest`

Expected: PASS。若红，先看是不是还有一处写死的 `1_400L` 被当成左滑时刻；生产代码的发送循环不用为这个测试改逻辑。

- [ ] **Step 3: 改两处注释**

`handle` 里「those 400 ms」改成：监听从打开这组指令就开始，仪表可能在 1887 之后、左滑之前就来问，那次询问先记住。

`onPacket` 里「Asked during the 400 ms before the left step」改成：左滑发出之前来的打开询问先记住，ready 和 start 随左滑那一步发出。

- [ ] **Step 4: 再跑这一组和任务 1 到 3 的测试**

Run:

```bash
./gradlew :common:testDebugUnitTest \
  --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyMcuTest \
  --tests com.shilapi.xcertplay.instrument.fly.ClusterFlySequencerTest \
  --tests com.shilapi.xcertplay.instrument.fly.ClusterFlySessionTest \
  --tests com.shilapi.xcertplay.instrument.fly.ClusterFlyControllerTest
```

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add common/src/test/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyControllerTest.kt \
  common/src/main/java/com/shilapi/xcertplay/instrument/fly/ClusterFlyController.kt
git commit -m "test: expect the navigation-front step before the fly"
```

---

### Task 5: 上车看画面

这一节不改代码，不提交。装上任务 4 之后的包再上车。CarPlay 已经显示在中控上，然后三指向左滑。

打开方向，日志里按这个顺序出现：

- `fly.command NAV_ON`
- `fly.command NAV_FRONT`
- `fly.mcu 1887 front true`
- `fly.command FLY_LEFT`
- `fly.tcp accept`
- `fly.ask OPEN`
- `fly.reply ready`
- `fly.reply start`
- `fly.push start`

仪表地图层下面是 CarPlay 的画面。车速和挡位仍是仪表自己画的。

向右滑之后出现 `fly.command FLY_RIGHT`、`fly.command NAV_OFF`、`fly.command NAV_BACK`、`fly.mcu 1887 back true`。这一层盖上，推流停。

倒车、雷达、紧急电话时仍然不发，日志是原来的 `fly.blocked`。

`fly.mcu 1887 front false` 时，这一趟不要改网卡，也不要在 1028 上加别的地址。把这行和它前面的 `fly.tcp` 行留在日志里。false 表示 `RpcService` 没有接受这笔和原厂相同的调用，画面不会出现。

- [ ] **Step 1: 装包并完成上面的左滑和右滑**

通过的标准是左滑后仪表上看得到 CarPlay 画面，右滑后这一层消失，并且左滑日志里 `fly.mcu 1887 front true` 出现在 `fly.tcp accept` 之前。
