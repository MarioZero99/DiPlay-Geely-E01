# DiPlay 1920×720 UI & 交互全局重构实施推进计划 (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于吉利 E01 车机 1920×720 物理超宽屏，将现有的沉重纵向滚动界面全面重构为「左侧 Slim Dock + 58% Hero 连接主舞台 + 42% 快捷双无线卡片 + 半屏设置抽屉」的高奢白底座舱交互，默认极氪白，支持魅族蓝皮肤切换与 18:00-06:00 自动夜间模式，且 100% 对应真实系统代码，杜绝任何虚构功能与并发互锁死锁。

**Architecture:** 
1. 抽离独立主题引擎 `CockpitTheme`，统一管理极氪白、魅族蓝与夜间模式，通过时间调度器自动无缝换肤；
2. 建立原子防重入互锁层 `InteractionGuard`，在主线程微秒级阻断连接猛击与网络模式冲突；
3. 将 `DiPlayActivity` 的渲染流重构为模组化纯 Kotlin View 布局：`SlimDock`、`HeroStage`、`QuickTiles` 与 `SettingsDrawer`；
4. 保证所有重写组件纯原生、零 Compose、零高斯模糊，在 Android 4.4/5.1 MTK 车机上满 60fps 运行。

**Tech Stack:** Kotlin 1.9+, Android SDK (API 19~37), Android Core / AppCompat, GradientDrawable, JUnit 4, Robolectric (or plain JVM unit test).

**Spec:** `docs/superpowers/specs/2026-10-17-ui-redesign-design.md`

## Global Constraints

- **屏幕分辨率**: 1920 × 720 px @ 160dpi (1px = 1dp)，超宽 8:3 比率。
- **最低 SDK 兼容**: `minSdk = 19` (Android 4.4 KitKat)，`targetSdk = 37`。
- **渲染限制**: 禁止使用 Jetpack Compose；禁止使用实时 RenderEffect 高斯模糊；纯 Kotlin View DSL 构建。
- **真实性限制**: 严禁在界面上添加没有代码支撑的伪数据（如待机帧率、伪手机电量、车速胎压）；MFi 证书从 APK 内置载入，绝不对车主暴露。
- **线程安全性**: UI 主线程只处理轻量绘制与交互，严禁在主线程执行同步文件 I/O 或阻塞进程；异步任务统一做生命周期销毁守卫。

---

### Task 1: 主题与时间感知换肤引擎 (CockpitTheme & Auto Scheduler)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/theme/CockpitTheme.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/ui/theme/CockpitThemeTest.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class AppSkin(val key: String) { ZEEKR_WHITE, FLYME_WHITE, NIGHT_DARK }
  enum class ThemeSchedule(val key: String) { AUTO_TIME, MANUAL }
  data class ThemePalette(
      val screenBg: Int,
      val dockBg: Int,
      val cardBg: Int,
      val cardInner: Int,
      val cardBorder: Int,
      val textMain: Int,
      val textMuted: Int,
      val accent: Int,
      val accentSoft: Int,
      val btnMainBg: Int,
      val btnMainFg: Int,
      val statusReady: Int
  )
  object CockpitThemeManager {
      fun isNightTime(hour: Int): Boolean
      fun resolvePalette(skin: AppSkin, schedule: ThemeSchedule, currentHour: Int): ThemePalette
  }
  ```

- [ ] **Step 1: 编写主题解析与 18:00-06:00 时间调度单元测试**

```kotlin
package com.shilapi.xcertplay.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitThemeTest {
    @Test
    fun testNightTimeDetection() {
        assertTrue(CockpitThemeManager.isNightTime(18))
        assertTrue(CockpitThemeManager.isNightTime(23))
        assertTrue(CockpitThemeManager.isNightTime(0))
        assertTrue(CockpitThemeManager.isNightTime(5))
        assertFalse(CockpitThemeManager.isNightTime(6))
        assertFalse(CockpitThemeManager.isNightTime(12))
        assertFalse(CockpitThemeManager.isNightTime(17))
    }

    @Test
    fun testAutoTimeScheduleResolution() {
        // 白天默认极氪白
        val dayPalette = CockpitThemeManager.resolvePalette(
            AppSkin.ZEEKR_WHITE,
            ThemeSchedule.AUTO_TIME,
            currentHour = 14
        )
        assertEquals(0xFFF4F6FA.toInt(), dayPalette.screenBg)
        assertEquals(0xFFFF5E00.toInt(), dayPalette.accent) // 极氪橙

        // 夜间自动切换暗黑模式
        val nightPalette = CockpitThemeManager.resolvePalette(
            AppSkin.ZEEKR_WHITE,
            ThemeSchedule.AUTO_TIME,
            currentHour = 20
        )
        assertEquals(0xFF0B0E14.toInt(), nightPalette.screenBg)

        // 手动模式不随时间改变
        val manualDayPalette = CockpitThemeManager.resolvePalette(
            AppSkin.FLYME_WHITE,
            ThemeSchedule.MANUAL,
            currentHour = 22
        )
        assertEquals(0xFF2563EB.toInt(), manualDayPalette.accent) // 魅族蓝
    }
}
```

- [ ] **Step 2: 运行测试并验证失败**

运行: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.ui.theme.CockpitThemeTest`
预期: FAIL (类不存在)

- [ ] **Step 3: 实现 `CockpitTheme.kt` 与 `AirPlayPersistence.kt` 持久化**

创建 `common/src/main/java/com/shilapi/xcertplay/ui/theme/CockpitTheme.kt`:
```kotlin
package com.shilapi.xcertplay.ui.theme

import android.graphics.Color

enum class AppSkin(val key: String) {
    ZEEKR_WHITE("zeekr_white"),
    FLYME_WHITE("flyme_white"),
    NIGHT_DARK("night_dark");

    companion object {
        fun fromKey(key: String?): AppSkin = entries.find { it.key == key } ?: ZEEKR_WHITE
    }
}

enum class ThemeSchedule(val key: String) {
    AUTO_TIME("auto_time"),
    MANUAL("manual");

    companion object {
        fun fromKey(key: String?): ThemeSchedule = entries.find { it.key == key } ?: AUTO_TIME
    }
}

data class ThemePalette(
    val screenBg: Int,
    val dockBg: Int,
    val cardBg: Int,
    val cardInner: Int,
    val cardBorder: Int,
    val textMain: Int,
    val textMuted: Int,
    val accent: Int,
    val accentSoft: Int,
    val btnMainBg: Int,
    val btnMainFg: Int,
    val statusReady: Int
)

object CockpitThemeManager {
    val ZEEKR_WHITE_PALETTE = ThemePalette(
        screenBg = Color.rgb(244, 246, 250),
        dockBg = Color.rgb(255, 255, 255),
        cardBg = Color.rgb(255, 255, 255),
        cardInner = Color.rgb(248, 250, 252),
        cardBorder = Color.argb(15, 0, 0, 0),
        textMain = Color.rgb(15, 23, 42),
        textMuted = Color.rgb(100, 116, 139),
        accent = Color.rgb(255, 94, 0), // 极氪橙
        accentSoft = Color.argb(30, 255, 94, 0),
        btnMainBg = Color.rgb(15, 23, 42),
        btnMainFg = Color.rgb(255, 255, 255),
        statusReady = Color.rgb(16, 185, 129)
    )

    val FLYME_WHITE_PALETTE = ThemePalette(
        screenBg = Color.rgb(245, 248, 252),
        dockBg = Color.rgb(255, 255, 255),
        cardBg = Color.rgb(255, 255, 255),
        cardInner = Color.rgb(240, 244, 250),
        cardBorder = Color.argb(20, 37, 99, 235),
        textMain = Color.rgb(30, 41, 59),
        textMuted = Color.rgb(100, 116, 139),
        accent = Color.rgb(37, 99, 235), // 魅族蓝
        accentSoft = Color.argb(30, 37, 99, 235),
        btnMainBg = Color.rgb(37, 99, 235),
        btnMainFg = Color.rgb(255, 255, 255),
        statusReady = Color.rgb(5, 150, 105)
    )

    val NIGHT_DARK_PALETTE = ThemePalette(
        screenBg = Color.rgb(11, 14, 20),
        dockBg = Color.rgb(17, 20, 28),
        cardBg = Color.rgb(20, 25, 35),
        cardInner = Color.rgb(26, 32, 44),
        cardBorder = Color.argb(18, 255, 255, 255),
        textMain = Color.rgb(248, 250, 252),
        textMuted = Color.rgb(148, 163, 184),
        accent = Color.rgb(255, 94, 0),
        accentSoft = Color.argb(45, 255, 94, 0),
        btnMainBg = Color.rgb(255, 255, 255),
        btnMainFg = Color.rgb(0, 0, 0),
        statusReady = Color.rgb(38, 208, 124)
    )

    fun isNightTime(hour: Int): Boolean = hour >= 18 || hour < 6

    fun resolvePalette(skin: AppSkin, schedule: ThemeSchedule, currentHour: Int): ThemePalette {
        if (schedule == ThemeSchedule.AUTO_TIME && isNightTime(currentHour)) {
            return NIGHT_DARK_PALETTE
        }
        return when (skin) {
            AppSkin.ZEEKR_WHITE -> ZEEKR_WHITE_PALETTE
            AppSkin.FLYME_WHITE -> FLYME_WHITE_PALETTE
            AppSkin.NIGHT_DARK -> NIGHT_DARK_PALETTE
        }
    }
}
```

在 `AirPlayPersistence.kt` 中添加持久化方法：
```kotlin
fun loadAppSkin(context: Context): AppSkin =
    AppSkin.fromKey(preferences(context).getString("app_skin", AppSkin.ZEEKR_WHITE.key))

fun saveAppSkin(context: Context, skin: AppSkin) {
    preferences(context).edit().putString("app_skin", skin.key).apply()
}

fun loadThemeSchedule(context: Context): ThemeSchedule =
    ThemeSchedule.fromKey(preferences(context).getString("theme_schedule", ThemeSchedule.AUTO_TIME.key))

fun saveThemeSchedule(context: Context, schedule: ThemeSchedule) {
    preferences(context).edit().putString("theme_schedule", schedule.key).apply()
}
```

- [ ] **Step 4: 运行测试并验证通过**

运行: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.ui.theme.CockpitThemeTest`
预期: PASS

- [ ] **Step 5: 提交任务代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/theme/CockpitTheme.kt \
        common/src/test/java/com/shilapi/xcertplay/ui/theme/CockpitThemeTest.kt \
        common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt
git commit -m "feat(ui): 增加座舱主题引擎与 18:00-06:00 自动夜间模式调度"
```

---

### Task 2: 并发互锁防护与非阻塞调度器 (InteractionGuard)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/InteractionGuard.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/ui/InteractionGuardTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class InteractionGuard {
      fun tryBeginConnect(): Boolean
      fun endConnect()
      fun isConnecting(): Boolean
      fun queueWirelessSwitch(action: () -> Unit)
  }
  ```

- [ ] **Step 1: 编写防重入互锁与串行化切换测试**

```kotlin
package com.shilapi.xcertplay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InteractionGuardTest {
    @Test
    fun testConnectAntiReentrancy() {
        val guard = InteractionGuard()
        assertTrue("首次点击应允许触发连接", guard.tryBeginConnect())
        assertFalse("连接进行中再次点击必须阻断", guard.tryBeginConnect())
        assertTrue(guard.isConnecting())
        
        guard.endConnect()
        assertFalse(guard.isConnecting())
        assertTrue("复位后允许重新发起连接", guard.tryBeginConnect())
    }

    @Test
    fun testWirelessSwitchSerialExecution() {
        val guard = InteractionGuard()
        val counter = AtomicInteger(0)
        guard.queueWirelessSwitch { counter.incrementAndGet() }
        guard.queueWirelessSwitch { counter.incrementAndGet() }
        Thread.sleep(50)
        assertTrue(counter.get() == 2)
    }
}
```

- [ ] **Step 2: 运行测试并验证失败**

运行: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.ui.InteractionGuardTest`
预期: FAIL

- [ ] **Step 3: 实现 `InteractionGuard.kt`**

```kotlin
package com.shilapi.xcertplay.ui

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class InteractionGuard {
    private val connectingLock = AtomicBoolean(false)
    private val serialExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wireless-switch-queue").apply { isDaemon = true }
    }

    fun tryBeginConnect(): Boolean = connectingLock.compareAndSet(false, true)

    fun endConnect() {
        connectingLock.set(false)
    }

    fun isConnecting(): Boolean = connectingLock.get()

    fun queueWirelessSwitch(action: () -> Unit) {
        serialExecutor.execute {
            runCatching { action() }
        }
    }
}
```

- [ ] **Step 4: 运行测试并验证通过**

运行: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.ui.InteractionGuardTest`
预期: PASS

- [ ] **Step 5: 提交任务代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/InteractionGuard.kt \
        common/src/test/java/com/shilapi/xcertplay/ui/InteractionGuardTest.kt
git commit -m "feat(ui): 增加交互原子防重入互锁与串行化切换队列"
```

---

### Task 3: 左侧 Slim Dock 导航栏组件实现

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/component/SlimDockView.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class SlimDockView(
      context: Context,
      palette: ThemePalette,
      onHomeClick: () -> Unit,
      onAudioClick: () -> Unit,
      onSettingsClick: () -> Unit,
      onExitClick: () -> Unit
  ) : LinearLayout
  ```

- [ ] **Step 1: 创建 `SlimDockView.kt` 原生 View 组件**

在 `common/src/main/java/com/shilapi/xcertplay/ui/component/SlimDockView.kt` 中：
- 宽度固定 82dp，铺满高度 MATCH_PARENT；
- 采用 `ThemePalette.dockBg` 与 `ThemePalette.cardBorder` 作为右边框；
- 包含主页（带竖向 3dp 指示高光）、音频通道、设置抽屉和退出按键，按压使用 `alpha = 0.7f` 触控反馈；
- 每个图标均使用 `DrawableCompat.setTint` 结合 `palette.textMuted` / `palette.accent` 动态着色。

- [ ] **Step 2: 在 `DiPlayActivity` 中替换原有顶部返回栏**

将 Activity 根布局从垂直滚动改为水平分栏布局：
```kotlin
val root = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutParams = LayoutParams(-1, -1)
    setBackgroundColor(palette.screenBg)
}
root.addView(SlimDockView(this, palette, ...), LayoutParams(dp(82), -1))
```

- [ ] **Step 3: 编译验证**

运行: `./gradlew :common:assembleDebug`
预期: BUILD SUCCESSFUL

- [ ] **Step 4: 提交代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/SlimDockView.kt \
        common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "feat(ui): 实现左侧 82dp Slim Dock 侧边栏，取代旧顶栏"
```

---

### Task 4: Hero 核心互联主舞台组件 (58% 黄金视线)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/component/HeroStageCard.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class HeroStageCard(
      context: Context,
      palette: ThemePalette,
      sessionPhase: SessionPhase,
      pairedPhoneName: String?,
      onConnectWireless: () -> Unit,
      onConnectUsb: () -> Unit,
      onOpenProjection: () -> Unit,
      onDisconnect: () -> Unit
  ) : LinearLayout
  ```

- [ ] **Step 1: 实现 `HeroStageCard.kt`**

- 胶囊状态：根据 `SessionPhase` 展示呼吸绿灯（Idle）、闪烁橙灯（Connecting）、常亮绿灯（Live）；
- 目标设备：展示读取到的 `pairedPhoneName`；
- 矢量能量图腾：纯代码 `GradientDrawable` 双环与内置手机剪影图标；
- 动作按钮：52dp 高度，`btnMainBg` 扁平圆角，未连接时为「进入 CARPLAY 投屏」，已连接时为「打开投屏画面」与「断开连接」；次按键为「USB 有线投屏」。

- [ ] **Step 2: 绑定 `InteractionGuard` 防重入与 `connect()` 调用**

在 `onConnectWireless` 回调中接入 `interactionGuard.tryBeginConnect()`，成功才调用底层 `connect(true)`；在 `CarPlayBackgroundSession` 回调或异常时调用 `interactionGuard.endConnect()`。

- [ ] **Step 3: 编译并运行单元测试**

运行: `./gradlew :common:testDebugUnitTest`
预期: BUILD SUCCESSFUL

- [ ] **Step 4: 提交代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/HeroStageCard.kt \
        common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "feat(ui): 构建 58% 宽 Hero 互联主舞台，接入原子防重入"
```

---

### Task 5: 右侧双无线模式与音频卡片网格 (42% 宽度)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/component/QuickWidgetsView.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class QuickWidgetsView(
      context: Context,
      palette: ThemePalette,
      currentMode: WirelessHotspotMode,
      hotspotSsid: String,
      joinedWifi: String?,
      currentAudioChannel: Int,
      onModeChanged: (WirelessHotspotMode) -> Unit,
      onHotspotSettings: () -> Unit,
      onEditHotspotPass: () -> Unit,
      onWifiSettings: () -> Unit,
      onEditWifiPass: () -> Unit,
      onChannelSelect: () -> Unit,
      onOpenDrawer: () -> Unit
  ) : LinearLayout
  ```

- [ ] **Step 1: 实现 `QuickWidgetsView.kt`**

- 卡片 1（无线模式）：双段拨块「车机热点」与「同一网络」，点击触发 `onModeChanged`（排他队列串行切换）；下挂真实操作小按钮（热点设置/密码，或 Wi-Fi 设置/密码）；
- 卡片 2（音频输出）：展示 E01 原生混音直通通道（Channel 0），点击弹层试听选择；
- 卡片 3（维护触发）：点击触发 `onOpenDrawer()`。

- [ ] **Step 2: 集成进 `DiPlayActivity` 右侧区域**

将原有散落的无线与音频卡片迁移进 `QuickWidgetsView`，删除冗余占位与调试伪字段。

- [ ] **Step 3: 编译并验证**

运行: `./gradlew :common:assembleDebug`
预期: BUILD SUCCESSFUL

- [ ] **Step 4: 提交代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/QuickWidgetsView.kt \
        common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "feat(ui): 实现右侧 42% 双无线模式与音频快捷卡片网格"
```

---

### Task 6: 半屏高级设置与极客维护抽屉 (SettingsDrawer)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/component/SettingsDrawerView.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class SettingsDrawerView(
      context: Context,
      palette: ThemePalette,
      onClose: () -> Unit,
      onThemeSelected: (AppSkin) -> Unit
  ) : FrameLayout
  ```

- [ ] **Step 1: 实现 `SettingsDrawerView.kt`**

- 占屏 52% 宽度，支持滑入动画与点击外部蒙层收起；
- **外观分区**：极氪白（默认）、魅族蓝皮肤切换，自动时间感应开关；
- **显示分区**：分辨率 100%/80%/60%、缓冲 300/500/1000ms、帧率 30/60、全屏开关；
- **系统分区**：开机自启、位置上报、语言切换；
- **固件与诊断**：原车 GOC 蓝牙跳板状态与维护按钮（`hopMaintenanceCards`），诊断日志一键导出按钮（`exportDiagnostics()`）。

- [ ] **Step 2: 绑定抽屉滑入滑出动效**

在 `DiPlayActivity` 顶层 FrameLayout 挂载该抽屉，支持平滑显隐与返回键拦截。

- [ ] **Step 3: 编译并验证**

运行: `./gradlew :common:assembleDebug`
预期: BUILD SUCCESSFUL

- [ ] **Step 4: 提交代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/SettingsDrawerView.kt \
        common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "feat(ui): 实现半屏高级设置与极客维护抽屉，收拢原有全部系统设置"
```

---

### Task 7: 整车实机回归验证与构建测试

**Files:**
- Modify: `common/src/test/java/com/shilapi/xcertplay/DiPlayActivityTest.kt` (或相关测试用例)

- [ ] **Step 1: 运行全量单元测试**

运行: `./gradlew test`
预期: 所有单元测试全部 PASS。

- [ ] **Step 2: 构建 Standalone Release 离线完整安装包**

运行: `./gradlew :mobile:assembleRelease`
预期: 成功生成 APK，且体积受控，DEX 无额外膨胀。

- [ ] **Step 3: 提交并生成回归报告**

```bash
git commit --allow-empty -m "chore(release): UI 全局重构完成并经受全量验证"
```
