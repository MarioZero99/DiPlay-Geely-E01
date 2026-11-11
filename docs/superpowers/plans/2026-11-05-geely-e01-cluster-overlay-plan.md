# 已撤回：吉利星瑞 E01：三指把 CarPlay 盖上仪表

> **不要执行。** 2026-11-05 撤回。这份计划假设仪表在扫中控屏幕上的一块矩形。中控和华阳 QNX 仪表是两套硬件，中控上的白边到不了仪表。Opcode 1885 只切换仪表地图层，不搬运像素。画面按高德的发送端走 TCP 1028 / RTP 9026，见仓库根目录 `飞屏.md`。下文保留作废案，避免再走这条路。

# 吉利星瑞 E01：三指把 CarPlay 盖上仪表

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 三指左滑时，星瑞 E01 仪表中间出现 CarPlay 地图；三指右滑时这层盖上。中控继续显示原来的 CarPlay。

**Architecture:** 原厂三指只掀开仪表的地图层，不提供第二块 Android 屏幕。像素不在 1885 里，仪表会向 1028 要 H.264。这份作废案没有走那条口：DiPlay 自己发那两条原厂广播把层掀开或盖上，再把已经有的仪表地图流（流 111）画进中控上的一块矩形。校准模式给这块矩形画白边，驾驶员拖动、缩放，直到白边落在仪表看得见的范围里，位置存下来。原车若装了显示名为 LBSNavi 的应用，投屏期间只暂停这一包，结束时恢复；这台车的包列表里没有这个名字时，不猜别的包去停。

**Tech Stack:** Kotlin，Android 5.1 / API 22，JUnit4，现有 `AndroidMediaSink.setSurface(111, surface)` 与 `CarPlayClusterDisplay.config`。

**Spec:** 本文件就是规格。背景记录在 `/Users/g/Documents/project/xr/飞屏.md`。不要执行 `2026-11-05-geely-e01-cluster-flyscreen-implementation-plan.md` 和 `2026-11-05-geely-e01-cluster-projection-xcnaviservice-plan.md`：那两份要改系统包、反射 `DimInteraction`、或建 `VirtualDisplay`，这条路已经否定。

## Global Constraints

- 最低 API 是 22。不用 `MediaCodec.setOutputSurface`（API 23 才有）。
- 不改 `/system`，不替换 `XCNaviService`、`Navi.apk`、`Extraservice`。
- 不调用 `DimInteraction`、`setPresentationToDimSwitch`、`ecarx.intent.action.DIM_PRESENTATION_CTRL`，不建 `VirtualDisplay`，不写 `/dev/graphics/fb2`，不用 `mtk-su`。
- 不停止空调、方向盘按键服务、仪表服务、360、桌面、SystemUI、蓝牙、T-Box、ECARX 服务。包名黑名单写死：`ecarx.naviservice`、`com.ecarx.naviwidget`、`com.autonavi.amapauto`。即使显示名碰巧像导航，也不停这三包。
- 导航门禁广播与三指广播各是一条，两次手势之间至少隔 1000 毫秒。三指广播的 `direction` 必须是 `left` 或 `right`，不能缺。
- 打开的顺序是先 `EXTRA_STATE=8`，等 400 毫秒，再发 `direction=left`。盖上的顺序相反：先 `direction=right`，等 400 毫秒，再发 `EXTRA_STATE=9`。先清导航状态会导致右滑被 Extraservice 丢掉。
- 比亚迪那条 `ClusterMapPresentation.findDisplay()` 找到副屏时，原路径不动。E01 只有在找不到副屏、并且用户打开本功能时才走矩形覆盖。
- 流 111 的分辨率在打开功能时定一次（1920×720 经过 `CarPlayClusterDisplay.config` 的取整）。拖动矩形不重新向手机要流。
- 每个任务都带能在电脑上跑的 JUnit。车上验收只在最后一节，不写进单元测试。
- 流畅度、两路硬解、把矩形挪出中控可视区域，都不在本计划里。

---

### Task 1: 三指左滑和右滑

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/ui/ThreeFingerSwipeDetector.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/ui/ThreeFingerSwipeDetectorTest.kt`

**Interfaces:**
- Consumes: 现有 `onTouch(phase, pointerCount, eventTime, centroidX, centroidY): Decision`
- Produces: `Decision.FLY_LEFT`、`Decision.FLY_RIGHT`。`Decision.TRIGGER` 仍是三指下滑，现有测试不用改语义。

下滑条件保持现在的写法：向下的位移不小于 `distance`，并且不小于横向位移乘 `directionRatio`。左滑是横向位移为负、绝对值达到同样的门槛，并且横向不小于向下的绝对值乘 `directionRatio`。右滑是横向为正的对称情况。一条手势只触发一次。

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
fun `a short sideways move is consumed and is not a fly`() {
    threeFingers()
    assertEquals(Decision.CONSUME, detector.onTouch(Phase.MOVE, 3, 120L, 860f, 400f))
}

@Test
fun `a left flick that ends on the lift still flies left`() {
    threeFingers()
    assertEquals(Decision.CONSUME, detector.onTouch(Phase.MOVE, 3, 100L, 850f, 400f))
    assertEquals(Decision.FLY_LEFT, detector.onTouch(Phase.POINTER_UP, 3, 140L, 800f, 410f))
}
```

`threeFingers()` 已经把起点放在 `(900, 400)`。`distance` 是 72，所以 800 和 1000 过线，860 不过线。现有下滑测试的 x 保持 900，不受影响。

- [ ] **Step 2: 跑测试，确认新用例失败**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.ui.ThreeFingerSwipeDetectorTest`

Expected: 编译失败，`Decision.FLY_LEFT` 不存在。

- [ ] **Step 3: 加上两个决定**

在 `Decision` 里、`TRIGGER` 之后加上：

```kotlin
/** Three fingers moved left: uncover the cluster map layer. */
FLY_LEFT,

/** Three fingers moved right: cover the cluster map layer. */
FLY_RIGHT,
```

把 `travelledFarEnough()` 换成下面两个函数，并在 `TRACKING` 的 `MOVE` 和 `POINTER_UP` 里用 `flyDecision()` 的结果。返回 null 时，`MOVE` 仍是 `CONSUME`；`POINTER_UP` 在没触发时仍进入 `DRAINING` 并 `CONSUME`。触发时进入 `DRAINING` 并返回对应的 `FLY_*` 或原来的 `TRIGGER`。

```kotlin
private fun flyDecision(): Decision? {
    val down = lastY - startY
    val across = lastX - startX
    val absAcross = kotlin.math.abs(across)
    val absDown = kotlin.math.abs(down)
    if (down >= distance && down >= absAcross * directionRatio) return Decision.TRIGGER
    if (absAcross >= distance && absAcross >= absDown * directionRatio) {
        return if (across < 0f) Decision.FLY_LEFT else Decision.FLY_RIGHT
    }
    return null
}
```

`MOVE` 分支：

```kotlin
Phase.MOVE -> flyDecision()?.also { state = State.DRAINING } ?: Decision.CONSUME
```

`POINTER_UP` 分支保持“手指还在三根以上才算数”，把原来的 `travelledFarEnough()` 换成 `flyDecision() != null` 的判断，触发时返回 `flyDecision()`，否则 `CONSUME`。`flyDecision()` 在同一次事件里算两次没有状态变化，可以直接写成：

```kotlin
Phase.POINTER_UP -> {
    val fired = if (pointerCount >= FINGERS) flyDecision() else null
    state = State.DRAINING
    fired ?: Decision.CONSUME
}
```

删掉不再使用的 `travelledFarEnough()`。

- [ ] **Step 4: 再跑同一条测试**

Expected: `ThreeFingerSwipeDetectorTest` 全部通过，包括原来的下滑用例。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/ThreeFingerSwipeDetector.kt \
        common/src/test/java/com/shilapi/xcertplay/ui/ThreeFingerSwipeDetectorTest.kt
git commit -m "feat(ui): distinguish three-finger left and right swipes"
```

---

### Task 2: 掀层和盖层的两条广播

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/cluster/ClusterLayerSignals.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/cluster/ClusterLayerSignalsTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `class ClusterBroadcast(val action: String, val delayMs: Long)`，另有 `intExtra(name): Int?` 与 `stringExtra(name): String?`
  - `class ClusterLayerSignals`
  - `fun open(nowMs: Long): List<ClusterBroadcast>`
  - `fun close(nowMs: Long): List<ClusterBroadcast>`
  - `fun isOpen(): Boolean`

打开返回两条，延迟分别是 0 和 400。盖上同样。层已经打开时 `open` 返回空列表。层已经盖上时 `close` 返回空列表。距离上一次非空返回不到 1000 毫秒时，也返回空列表，并且不改变开关状态。

- [ ] **Step 1: 写会失败的测试**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterLayerSignalsTest {
    private val signals = ClusterLayerSignals()

    @Test
    fun `open sets the nav gate before the left swipe`() {
        val sent = signals.open(1_000L)
        assertEquals(2, sent.size)
        assertEquals("AUTONAVI_STANDARD_BROADCAST_SEND", sent[0].action)
        assertEquals(0L, sent[0].delayMs)
        assertEquals(10019, sent[0].intExtra("KEY_TYPE"))
        assertEquals(8, sent[0].intExtra("EXTRA_STATE"))
        assertEquals("com.neusoft.alfus.three_finger", sent[1].action)
        assertEquals(400L, sent[1].delayMs)
        assertEquals("left", sent[1].stringExtra("direction"))
        assertTrue(signals.isOpen())
    }

    @Test
    fun `close covers the layer before clearing the nav gate`() {
        signals.open(1_000L)
        val sent = signals.close(3_000L)
        assertEquals("right", sent[0].stringExtra("direction"))
        assertEquals(0L, sent[0].delayMs)
        assertEquals(9, sent[1].intExtra("EXTRA_STATE"))
        assertEquals(400L, sent[1].delayMs)
        assertTrue(!signals.isOpen())
    }

    @Test
    fun `a second gesture inside one second is dropped`() {
        signals.open(1_000L)
        assertEquals(emptyList<ClusterBroadcast>(), signals.close(1_500L))
        assertTrue(signals.isOpen())
    }

    @Test
    fun `open while the layer is already open does nothing`() {
        signals.open(1_000L)
        assertEquals(emptyList<ClusterBroadcast>(), signals.open(3_000L))
        assertTrue(signals.isOpen())
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.cluster.ClusterLayerSignalsTest`

Expected: 编译失败，类不存在。

- [ ] **Step 3: 实现**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.cluster

class ClusterBroadcast(
    val action: String,
    val delayMs: Long,
    private val extras: Map<String, Any>,
) {
    fun intExtra(name: String): Int? = extras[name] as? Int
    fun stringExtra(name: String): String? = extras[name] as? String
}

class ClusterLayerSignals(
    private val gapMs: Long = 1_000L,
    private val fingerDelayMs: Long = 400L,
) {
    private var open = false
    private var lastAcceptedMs = Long.MIN_VALUE

    fun isOpen(): Boolean = open

    fun open(nowMs: Long): List<ClusterBroadcast> {
        if (open || nowMs - lastAcceptedMs < gapMs) return emptyList()
        lastAcceptedMs = nowMs
        open = true
        return listOf(nav(8, 0L), finger("left", fingerDelayMs))
    }

    fun close(nowMs: Long): List<ClusterBroadcast> {
        if (!open || nowMs - lastAcceptedMs < gapMs) return emptyList()
        lastAcceptedMs = nowMs
        open = false
        return listOf(finger("right", 0L), nav(9, fingerDelayMs))
    }

    private fun nav(state: Int, delayMs: Long) = ClusterBroadcast(
        action = "AUTONAVI_STANDARD_BROADCAST_SEND",
        delayMs = delayMs,
        extras = mapOf("KEY_TYPE" to 10019, "EXTRA_STATE" to state),
    )

    private fun finger(direction: String, delayMs: Long) = ClusterBroadcast(
        action = "com.neusoft.alfus.three_finger",
        delayMs = delayMs,
        extras = mapOf("direction" to direction),
    )
}
```

- [ ] **Step 4: 再跑同一条测试**

Expected: 通过。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/cluster/ClusterLayerSignals.kt \
        common/src/test/java/com/shilapi/xcertplay/cluster/ClusterLayerSignalsTest.kt
git commit -m "feat(cluster): sequence the E01 map-layer open and close broadcasts"
```

---

### Task 3: 矩形的位置和缩放

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/cluster/ClusterOverlayFrame.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/cluster/ClusterOverlayFrameTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class ClusterOverlayFrame(val left: Int, val top: Int, val width: Int, val height: Int)`
  - `fun movedBy(dx: Int, dy: Int, boundsWidth: Int, boundsHeight: Int): ClusterOverlayFrame`
  - `fun scaledBy(percent: Int, boundsWidth: Int, boundsHeight: Int): ClusterOverlayFrame`
  - `companion fun defaultIn(boundsWidth: Int, boundsHeight: Int): ClusterOverlayFrame`
  - `companion fun fromStored(left: Int, top: Int, width: Int, height: Int, boundsWidth: Int, boundsHeight: Int): ClusterOverlayFrame`
  - `const val MIN_EDGE = 160`

`defaultIn` 在宿主里居中放一块宽为宿主一半、高为宿主一半的矩形，边长用 `MIN_EDGE` 托底。`scaledBy` 的 `percent` 是相对当前边长的增量，取 -20 或 +20 两档即可，实现按百分数乘。放大后仍须整个落在宿主内；放不下就保持原样。缩到任一边小于 `MIN_EDGE` 时保持原样。`fromStored` 遇到宽或高小于 `MIN_EDGE`、或矩形完全在宿主外时，返回 `defaultIn`。

- [ ] **Step 1: 写会失败的测试**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterOverlayFrameTest {
    @Test
    fun `default frame sits inside a 1920 by 720 host`() {
        val frame = ClusterOverlayFrame.defaultIn(1920, 720)
        assertEquals(960, frame.width)
        assertEquals(360, frame.height)
        assertEquals(480, frame.left)
        assertEquals(180, frame.top)
    }

    @Test
    fun `drag stops at the host edge`() {
        val frame = ClusterOverlayFrame(0, 0, 200, 200)
        val moved = frame.movedBy(-50, -50, 1920, 720)
        assertEquals(0, moved.left)
        assertEquals(0, moved.top)
    }

    @Test
    fun `growing past the host keeps the old frame`() {
        val frame = ClusterOverlayFrame(0, 0, 1800, 600)
        assertEquals(frame, frame.scaledBy(20, 1920, 720))
    }

    @Test
    fun `a stored frame smaller than the minimum is replaced`() {
        val frame = ClusterOverlayFrame.fromStored(0, 0, 10, 10, 1920, 720)
        assertEquals(ClusterOverlayFrame.defaultIn(1920, 720), frame)
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.cluster.ClusterOverlayFrameTest`

Expected: 编译失败，类不存在。

- [ ] **Step 3: 实现**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.cluster

data class ClusterOverlayFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    fun movedBy(dx: Int, dy: Int, boundsWidth: Int, boundsHeight: Int): ClusterOverlayFrame {
        val nextLeft = (left + dx).coerceIn(0, (boundsWidth - width).coerceAtLeast(0))
        val nextTop = (top + dy).coerceIn(0, (boundsHeight - height).coerceAtLeast(0))
        return copy(left = nextLeft, top = nextTop)
    }

    fun scaledBy(percent: Int, boundsWidth: Int, boundsHeight: Int): ClusterOverlayFrame {
        val nextWidth = width * (100 + percent) / 100
        val nextHeight = height * (100 + percent) / 100
        if (nextWidth < MIN_EDGE || nextHeight < MIN_EDGE) return this
        if (left + nextWidth > boundsWidth || top + nextHeight > boundsHeight) return this
        return copy(width = nextWidth, height = nextHeight)
    }

    companion object {
        const val MIN_EDGE = 160

        fun defaultIn(boundsWidth: Int, boundsHeight: Int): ClusterOverlayFrame {
            val width = (boundsWidth / 2).coerceAtLeast(MIN_EDGE).coerceAtMost(boundsWidth)
            val height = (boundsHeight / 2).coerceAtLeast(MIN_EDGE).coerceAtMost(boundsHeight)
            return ClusterOverlayFrame(
                left = ((boundsWidth - width) / 2).coerceAtLeast(0),
                top = ((boundsHeight - height) / 2).coerceAtLeast(0),
                width = width,
                height = height,
            )
        }

        fun fromStored(
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            boundsWidth: Int,
            boundsHeight: Int,
        ): ClusterOverlayFrame {
            val stored = ClusterOverlayFrame(left, top, width, height)
            val inside = width >= MIN_EDGE && height >= MIN_EDGE &&
                left >= 0 && top >= 0 &&
                left + width <= boundsWidth && top + height <= boundsHeight
            return if (inside) stored else defaultIn(boundsWidth, boundsHeight)
        }
    }
}
```

- [ ] **Step 4: 再跑同一条测试**

Expected: 通过。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/cluster/ClusterOverlayFrame.kt \
        common/src/test/java/com/shilapi/xcertplay/cluster/ClusterOverlayFrameTest.kt
git commit -m "feat(cluster): add the calibrated rectangle for the E01 map layer"
```

---

### Task 4: 记住矩形，并只暂停显示名是 LBSNavi 的包

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt`（`KEY_CLUSTER_MAP` 那一组旁边）
- Create: `common/src/main/java/com/shilapi/xcertplay/cluster/FactoryMapClaim.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/cluster/ClusterOverlayStore.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/cluster/FactoryMapClaimTest.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/cluster/ClusterOverlayStoreTest.kt`

**Interfaces:**
- Consumes: `AirPlayPersistence` 的 `PREFS = "xcertplay_airplay"`
- Produces:
  - `AirPlayPersistence.loadE01ClusterOverlayEnabled(context: Context): Boolean`，默认 false
  - `AirPlayPersistence.saveE01ClusterOverlayEnabled(context: Context, enabled: Boolean)`
  - `AirPlayPersistence.loadE01ClusterFrame(context: Context): ClusterOverlayFrame?`，四个值缺任何一个时返回 null
  - `AirPlayPersistence.saveE01ClusterFrame(context: Context, frame: ClusterOverlayFrame)`
  - `FactoryMapClaim.packageToPause(installed: List<InstalledPackage>): String?`
  - `data class InstalledPackage(val packageName: String, val label: String)`

`packageToPause` 只返回显示名去掉首尾空白后等于 `LBSNavi` 的包，忽略大小写。黑名单里的包即使显示名匹配也返回 null。没有匹配时返回 null。多个匹配时返回 null，避免停错。矩形的读写先做不依赖 Android 的 `ClusterOverlayStore`。`AirPlayPersistence` 使用同一组键，把 store 的四个字段放进已经在用的 `xcertplay_airplay`。

- [ ] **Step 1: 写会失败的测试**

`FactoryMapClaimTest`：

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FactoryMapClaimTest {
    @Test
    fun `a package labelled LBSNavi is the one to pause`() {
        val found = FactoryMapClaim.packageToPause(
            listOf(InstalledPackage("com.example.lbs", " LBSNavi ")),
        )
        assertEquals("com.example.lbs", found)
    }

    @Test
    fun `the factory navigation packages are never paused`() {
        val found = FactoryMapClaim.packageToPause(
            listOf(
                InstalledPackage("com.autonavi.amapauto", "LBSNavi"),
                InstalledPackage("ecarx.naviservice", "LBSNavi"),
                InstalledPackage("com.ecarx.naviwidget", "LBSNavi"),
            ),
        )
        assertNull(found)
    }

    @Test
    fun `two matches pause nothing`() {
        val found = FactoryMapClaim.packageToPause(
            listOf(
                InstalledPackage("com.example.one", "LBSNavi"),
                InstalledPackage("com.example.two", "lbsnavi"),
            ),
        )
        assertNull(found)
    }
}
```

`ClusterOverlayStoreTest`：

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterOverlayStoreTest {
    @Test
    fun `a saved frame round-trips`() {
        val store = MemoryClusterOverlayStore()
        val frame = ClusterOverlayFrame(10, 20, 300, 200)
        store.saveEnabled(true)
        store.saveFrame(frame)
        assertEquals(true, store.loadEnabled())
        assertEquals(frame, store.loadFrame())
    }

    @Test
    fun `missing frame stays missing`() {
        assertEquals(null, MemoryClusterOverlayStore().loadFrame())
    }
}
```

- [ ] **Step 2: 跑这两个测试类，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.cluster.FactoryMapClaimTest --tests com.shilapi.xcertplay.cluster.ClusterOverlayStoreTest`

Expected: 编译失败。

- [ ] **Step 3: 实现**

`FactoryMapClaim.kt`：

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.cluster

data class InstalledPackage(val packageName: String, val label: String)

object FactoryMapClaim {
    private val neverPause = setOf(
        "ecarx.naviservice",
        "com.ecarx.naviwidget",
        "com.autonavi.amapauto",
    )

    fun packageToPause(installed: List<InstalledPackage>): String? {
        val matches = installed.filter { pkg ->
            pkg.label.trim().equals("LBSNavi", ignoreCase = true) &&
                pkg.packageName !in neverPause
        }
        return if (matches.size == 1) matches[0].packageName else null
    }
}
```

`ClusterOverlayStore.kt`，和帧放在一起：

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.cluster

interface ClusterOverlayStore {
    fun loadEnabled(): Boolean
    fun saveEnabled(enabled: Boolean)
    fun loadFrame(): ClusterOverlayFrame?
    fun saveFrame(frame: ClusterOverlayFrame)
}

class MemoryClusterOverlayStore : ClusterOverlayStore {
    private var enabled = false
    private var frame: ClusterOverlayFrame? = null
    override fun loadEnabled(): Boolean = enabled
    override fun saveEnabled(enabled: Boolean) { this.enabled = enabled }
    override fun loadFrame(): ClusterOverlayFrame? = frame
    override fun saveFrame(frame: ClusterOverlayFrame) { this.frame = frame }
}
```

在 `AirPlayPersistence` 增加键，并在读写时使用：

```kotlin
private const val KEY_E01_CLUSTER_OVERLAY = "e01_cluster_overlay_enabled"
private const val KEY_E01_CLUSTER_FRAME_LEFT = "e01_cluster_frame_left"
private const val KEY_E01_CLUSTER_FRAME_TOP = "e01_cluster_frame_top"
private const val KEY_E01_CLUSTER_FRAME_WIDTH = "e01_cluster_frame_width"
private const val KEY_E01_CLUSTER_FRAME_HEIGHT = "e01_cluster_frame_height"
```

`loadE01ClusterOverlayEnabled` 缺省 false。`loadE01ClusterFrame` 在四个键没有全部存在时返回 null。用 `prefs.contains` 判断，不要把 0 当成“没存过”。`saveE01ClusterFrame` 把 `ClusterOverlayFrame` 的四个字段写进去。

- [ ] **Step 4: 再跑 Step 2 的命令**

Expected: 通过。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/cluster/FactoryMapClaim.kt \
        common/src/main/java/com/shilapi/xcertplay/cluster/ClusterOverlayStore.kt \
        common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt \
        common/src/test/java/com/shilapi/xcertplay/cluster/FactoryMapClaimTest.kt \
        common/src/test/java/com/shilapi/xcertplay/cluster/ClusterOverlayStoreTest.kt
git commit -m "feat(cluster): remember the E01 frame and name the only package that may be paused"
```

---

### Task 5: 把矩形画到中控上，并接上流 111

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/cluster/E01ClusterOverlay.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`（`clusterDisplayConfig`、`onHostTouch`、`onDestroy`）
- Modify: `common/src/main/res/values/strings.xml` 与 `values-zh-rCN/strings.xml`，只加下面三条文案

**Interfaces:**
- Consumes: `ClusterOverlayFrame`、`ClusterOverlayStore`、`CarPlayClusterDisplay.config`、`AndroidMediaSink.setSurface(111, surface)` / `clearSurface(111, surface)`、现有 `onClusterSurface`
- Produces: `E01ClusterOverlay`。宿主在找不到 `ClusterMapPresentation.findDisplay()` 且 `loadE01ClusterOverlayEnabled` 为 true 时创建它。找到副屏时不创建。

矩形第一版允许挡住中控。白边只在校准模式出现。拖动用一根手指移动，两个按钮各缩放 -20% 和 +20%。点“完成”后隐藏按钮和白边，把帧写入 `AirPlayPersistence.saveE01ClusterFrame`。

文案：

- `e01_cluster_overlay_done`：完成
- `e01_cluster_overlay_smaller`：缩小
- `e01_cluster_overlay_larger`：放大

- [ ] **Step 1: 写视图类**

`E01ClusterOverlay` 继承 `FrameLayout`。构造参数是 `frame: ClusterOverlayFrame`、`calibrating: Boolean`、`onFrameChanged: (ClusterOverlayFrame) -> Unit`、`onSurface: (Surface?) -> Unit`。

内部放一个 `SurfaceView`，铺满本视图。`holder.addCallback` 里 `surfaceCreated` 和 `surfaceChanged` 调用 `onSurface(holder.surface)`，`surfaceDestroyed` 调用 `onSurface(null)`。

校准模式时，本视图 `setWillNotDraw(false)`，`onDraw` 用 `Paint` 画 4 像素白边，`Style.STROKE`。非校准模式不画。

拖动：`setOnTouchListener` 在校准模式记录 `ACTION_DOWN` 的坐标，`ACTION_MOVE` 用位移调用 `frame.movedBy`。宿主宽高用 `(parent as View).width` 和 `height`。新帧通过 `onFrameChanged` 交回去，并由调用方 `applyFrame`。非校准模式返回 false，让底下的 CarPlay 收到触摸。事件发生在矩形上时校准模式返回 true。

两个按钮和一个完成按钮用 `Button`，`API 22` 可以直接 `addView`。完成按钮调用一个 `onCalibrationFinished: () -> Unit`。缩放按钮调用 `frame.scaledBy(20, ...)` 或 `scaledBy(-20, ...)`。

`applyFrame(frame)` 设置 `LayoutParams(frame.width, frame.height)` 和 `translationX/Y` 或 `leftMargin/topMargin`。用 margin，并把父布局设成允许子视图用 margin 的 `FrameLayout`。

- [ ] **Step 2: 宿主接上**

在 `CarPlayHostActivity` 增加字段：

```kotlin
private var e01Overlay: E01ClusterOverlay? = null
private val clusterSignals = ClusterLayerSignals()
private val clusterSignalPosts = mutableListOf<Runnable>()
```

`clusterDisplayConfig()` 在 `findDisplay()` 返回 null 时不要直接 return null。改成：

```kotlin
val display = ClusterMapPresentation.findDisplay(this, theme)
if (display == null) {
    if (!AirPlayPersistence.loadE01ClusterOverlayEnabled(this)) return null
    return CarPlayClusterDisplay.config(1920, 720)
}
```

后面的比亚迪分支保持原样。E01 这条不读主题、不读标记偏移。

`onHostTouch` 的 `when` 增加：

```kotlin
ThreeFingerSwipeDetector.Decision.FLY_LEFT -> {
    showE01ClusterOverlay()
    postClusterSignals(clusterSignals.open(SystemClock.uptimeMillis()))
    return true
}
ThreeFingerSwipeDetector.Decision.FLY_RIGHT -> {
    postClusterSignals(clusterSignals.close(SystemClock.uptimeMillis()))
    hideE01ClusterOverlay()
    return true
}
```

`postClusterSignals` 先 `clusterSignalPosts.forEach { mainHandler.removeCallbacks(it) }` 并清空列表。对每条 `ClusterBroadcast` 做一个 `Runnable`：构造 `Intent(action)`，`intExtra` 用 `putExtra(name, value)`，`stringExtra` 同样，然后 `sendBroadcast`。`appendLog` 只打一行，格式固定为 `E01 cluster layer: <action> <delayMs>`。不要把每次触摸打进去。`mainHandler.postDelayed(runnable, broadcast.delayMs)`。

`showE01ClusterOverlay`：已有 overlay 时只 `visibility = VISIBLE`。否则读存储的帧；null 时用 `ClusterOverlayFrame.defaultIn(hostWidth, hostHeight)`，并把 `calibrating` 设为 true。有存储帧时 `calibrating` 为 false。把视图加到 `videoView` 的父布局上，父布局不是 `FrameLayout` 时把它包一层的工作不要做，直接 `return` 并 `appendLog("E01 cluster overlay: host is not a FrameLayout")`。`onSurface` 接到现有的 `onClusterSurface`。帧变化时调用 `AirPlayPersistence.saveE01ClusterFrame`。

`hideE01ClusterOverlay`：`visibility = GONE`，并对当前 `clusterSurface` 调用 `sink?.clearSurface(SCREEN_TYPE_ALT, it)`，然后把 `clusterSurface` 置空。不要 `dismiss()` 比亚迪的 Presentation。

`onDestroy` 里在 `dismissClusterPresentation()` 旁边调用 `hideE01ClusterOverlay()`，并清掉 `clusterSignalPosts`。

功能开关第一版没有设置页。`loadE01ClusterOverlayEnabled` 默认 false 时，开发用的打开方式是：左滑仍然调用 `showE01ClusterOverlay`，并在显示前执行 `AirPlayPersistence.saveE01ClusterOverlayEnabled(this, true)`。这样第一次左滑就会向手机要流 111。右滑不把这个开关写回 false，避免下次还要重新协商；右滑只藏矩形并清表面。用户要关功能时再把存储写成 false，那一步不在本任务。

- [ ] **Step 3: 编译 common 模块**

Run: `./gradlew :common:compileDebugKotlin :common:testDebugUnitTest --tests com.shilapi.xcertplay.ui.ThreeFingerSwipeDetectorTest --tests com.shilapi.xcertplay.cluster.ClusterLayerSignalsTest --tests com.shilapi.xcertplay.cluster.ClusterOverlayFrameTest --tests com.shilapi.xcertplay.cluster.FactoryMapClaimTest --tests com.shilapi.xcertplay.cluster.ClusterOverlayStoreTest`

Expected: 编译和上述测试通过。这一步没有新的单元测试，视图和宿主靠车上那一节验收。

- [ ] **Step 4: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/cluster/E01ClusterOverlay.kt \
        common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt \
        common/src/main/res/values/strings.xml \
        common/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(cluster): draw the CarPlay cluster stream into a movable E01 rectangle"
```

---

### Task 6: 投屏时暂停 LBSNavi，结束时恢复

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/cluster/FactoryMapPauser.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/cluster/FactoryMapPauserTest.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`（`showE01ClusterOverlay` / `hideE01ClusterOverlay`）

**Interfaces:**
- Consumes: `FactoryMapClaim.packageToPause`
- Produces:
  - `interface PackageEnabledStore { fun enabled(packageName: String): Boolean?; fun setEnabled(packageName: String, enabled: Boolean): Boolean }`
  - `class FactoryMapPauser(private val store: PackageEnabledStore)`
  - `fun pause(installed: List<InstalledPackage>): String?`
  - `fun resume()`

`pause` 得到唯一包名时，先记下 `store.enabled` 的原值，再 `setEnabled(packageName, false)`。`setEnabled` 返回 false 时当作没暂停，`resume` 不做任何事，并让 `pause` 返回 null。`resume` 把原值写回去；原值未知时写 true。没有可暂停的包时返回 null。`resume` 可以反复调用。

宿主的 `PackageEnabledStore` 用 `PackageManager.getApplicationEnabledSetting` 和 `setApplicationEnabledSetting`。`COMPONENT_ENABLED_STATE_DISABLED` 与 `DISABLED_USER` 都算停用。捕获 `SecurityException` 后返回 false。不要用 `su`，不要用 `pm disable`。

- [ ] **Step 1: 写会失败的测试**

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FactoryMapPauserTest {
    @Test
    fun `pause disables LBSNavi and resume restores it`() {
        val store = MemoryPackageEnabledStore(mutableMapOf("com.example.lbs" to true))
        val pauser = FactoryMapPauser(store)
        assertEquals(
            "com.example.lbs",
            pauser.pause(listOf(InstalledPackage("com.example.lbs", "LBSNavi"))),
        )
        assertEquals(false, store.enabled("com.example.lbs"))
        pauser.resume()
        assertEquals(true, store.enabled("com.example.lbs"))
    }

    @Test
    fun `a refused disable is not resumed later`() {
        val store = MemoryPackageEnabledStore(mutableMapOf("com.example.lbs" to true), acceptWrites = false)
        val pauser = FactoryMapPauser(store)
        assertNull(pauser.pause(listOf(InstalledPackage("com.example.lbs", "LBSNavi"))))
        pauser.resume()
        assertEquals(true, store.enabled("com.example.lbs"))
    }
}

class MemoryPackageEnabledStore(
    private val values: MutableMap<String, Boolean>,
    private val acceptWrites: Boolean = true,
) : PackageEnabledStore {
    override fun enabled(packageName: String): Boolean? = values[packageName]
    override fun setEnabled(packageName: String, enabled: Boolean): Boolean {
        if (!acceptWrites) return false
        values[packageName] = enabled
        return true
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.cluster.FactoryMapPauserTest`

Expected: 编译失败。

- [ ] **Step 3: 实现并接到宿主**

`FactoryMapPauser` 按接口写。宿主在 `showE01ClusterOverlay` 成功把视图设为可见之后调用 `pause`。已安装包的列表用 `packageManager.getInstalledApplications(0)`，标签用 `packageManager.getApplicationLabel`。`pause` 返回 null 时 `appendLog("E01 cluster layer: factory map claim not paused")`。返回包名时打 `E01 cluster layer: paused <package>`。

`hideE01ClusterOverlay` 和 `onDestroy` 调用同一个 `pauser.resume()`。日志一行：`E01 cluster layer: resumed factory map claim`。没暂停过则不打这行。

- [ ] **Step 4: 再跑 FactoryMapPauserTest 和 Task 5 的编译命令**

Expected: 通过。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/cluster/FactoryMapPauser.kt \
        common/src/test/java/com/shilapi/xcertplay/cluster/FactoryMapPauserTest.kt \
        common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt
git commit -m "feat(cluster): pause only an app labelled LBSNavi while the E01 map layer is open"
```

---

## 车上验收

单元测试代替不了这一节。装到星瑞 E01 上，CarPlay 已经连上之后做：

1. 三指左滑。中控上出现一块带白边的 CarPlay 地图，挡住一部分主画面。这一版就是这样。
2. 拖动、点放大或缩小，直到仪表中间看见同一圈白边，并且白边落在仪表屏幕里面。点完成。白边和按钮消失，地图还在。
3. 看仪表自己画的车速还在地图上面。
4. 三指右滑。仪表地图层盖上，中控矩形消失，主画面还在。
5. 再左滑一次。矩形用上次的位置，不再进入校准。
6. 倒车或雷达报警时左滑。层不应打开。Extraservice 会自己丢掉这次手势。
7. 日志里每次手势只有 `E01 cluster layer:` 那几行，没有逐指的日志。

停的条件：白边在中控上怎么拖，仪表都没有这圈白边。到这里就停，不要写 `fb2`，不要提权，不要改系统包。把这个结果记到计划下面，再决定下一条路。

## 本计划故意不做

- 不把两路 H.264 合成一路，不降帧，不把矩形挪到中控看不见的地方。
- 不处理方向盘地图键。三指左滑已经会发同一条掀层广播。
- 不做设置页开关。第一次左滑即打开功能。
