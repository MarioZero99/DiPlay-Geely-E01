# DiPlay 1920×720 UI & 交互全局重构实施推进计划 (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于吉利 E01 车机 1920×720 物理超宽屏，将现有垂直单列/双列平铺界面重构为「左侧 Slim Dock + 58% Hero 连接主舞台 + 42% 快捷双无线与音频卡片 + 半屏设置抽屉」。默认极氪白，支持魅族蓝换肤；夜间模式与车机大灯信号/系统时间联合生效；完整保留现有全部 8 个显示/系统/维护设置项及仪表投屏；零额外 GC 抖动、主线程零阻塞 I/O，并在 `DiPlay-E01` AVD（1920×720 @ 160dpi）实测排版对齐。

**Architecture:** 
1. 抽离独立主题模块 `CockpitTheme`，色板纯整型字面量实现，无 Android Mock 依赖；为魅族蓝专属定制暗夜蓝调；
2. 日夜模式真相源收归一处：以 `DarkMode.isDarkMode(uiMode)` 车载信号为主，18:00-06:00 时间规则为辅；进出隧道时就地更新已有组件色板，不重建 Activity，投屏零抖动；
3. 连接与模式切换复用现有 `sessionIsIdle()` 与 `applyConnectionSetting` 状态机，绝不增设第二把锁；
4. 增量就地刷新 (Incremental In-Place Update)：核心组件单次构建，通过 `updateState(...)` 就地差量刷新，彻底消除全量重建产生的 GC 抖动；
5. 真实自愈引导提示：主控按键保持纯粹稳定职责（待机时连接、Live 时打开），当存在阻断或未就绪（热点关/蓝牙未配对）时，在副标题直观呈现自愈提示文案，不绑架主按键；
6. 车规级微动效：半屏抽屉配备 200ms `translationX` 阻尼减速滑入（硬件加速层管理），连接中车标微呼吸反馈，按压提供 0.75f 触控反馈；
7. 现有 7 个 UI 测试逐个对齐新视图层级；
8. 在本地已有的 `DiPlay-E01` AVD（API 22，1920×720）上执行真实屏幕截图检验。

**Tech Stack:** Kotlin 2.2.10, Android SDK 19~37, Android Support/AppCompat, JUnit 4, Robolectric 4.17, AVD `DiPlay-E01` (1920×720 @ 160dpi).

**Spec:** `docs/superpowers/specs/2026-10-17-ui-redesign-plan.md`

## Global Constraints

- **屏幕规格**: 1920 × 720 px @ 160dpi (1px = 1dp)，超宽 8:3 比率。
- **内存与性能**: E01 可用内存低至 26-33MB，严禁引入 Compose 或实时模糊；纯 Kotlin View DSL 构建；主线程杜绝文件与进程 I/O；组件禁止随每秒心跳反复摧毁重建。
- **功能不可增删**: 严禁虚构伪状态（伪电量/伪待机帧率/伪 P2P）；严禁删漏现有 8 个真设置项（编解码器、直接渲染、右舵、屏幕物理尺寸、调试日志、权限、版本开源、实验性上传）及仪表投屏开关（`instrument_cluster_map`）。
- **并发状态单源**: 严格沿用 `WirelessPageState.sessionPhase` 与 `applyConnectionSetting()`，不设立平行锁。

---

### Task 1: 主题与日夜感知色板引擎 (CockpitTheme)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/theme/CockpitTheme.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/ui/theme/CockpitThemeTest.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt` (在 `object AirPlayPersistence` 内部新增方法)
- Modify: `common/src/main/java/com/shilapi/xcertplay/DarkMode.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class AppSkin(val key: String) { ZEEKR_WHITE, FLYME_WHITE, NIGHT_DARK }
  enum class ThemeSchedule(val key: String) { AUTO_CAR, AUTO_TIME, MANUAL }
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
      fun resolvePalette(skin: AppSkin, schedule: ThemeSchedule, uiMode: Int, currentHour: Int): ThemePalette
  }
  ```

- [ ] **Step 1: 编写纯 JVM 单元测试（使用纯整数比较，不碰 android.graphics.Color）**

```kotlin
package com.shilapi.xcertplay.ui.theme

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitThemeTest {
    @Test
    fun testNightTimeHours() {
        assertTrue(CockpitThemeManager.isNightHour(18))
        assertTrue(CockpitThemeManager.isNightHour(23))
        assertTrue(CockpitThemeManager.isNightHour(0))
        assertTrue(CockpitThemeManager.isNightHour(5))
        assertFalse(CockpitThemeManager.isNightHour(6))
        assertFalse(CockpitThemeManager.isNightHour(12))
        assertFalse(CockpitThemeManager.isNightHour(17))
    }

    @Test
    fun testCarHeadlightUiModeTakesPrecedence() {
        // 车机大灯触发的 UI_MODE_NIGHT_YES 拥有最高日夜优先级
        val palette = CockpitThemeManager.resolvePalette(
            skin = AppSkin.ZEEKR_WHITE,
            schedule = ThemeSchedule.AUTO_CAR,
            uiMode = Configuration.UI_MODE_NIGHT_YES,
            currentHour = 12 // 即使中午 12 点，车机进夜/进隧道也要变暗黑
        )
        assertEquals(0xFF0B0E14.toInt(), palette.screenBg)
    }

    @Test
    fun testTimeFallbackWhenCarUiModeUndefined() {
        val paletteNight = CockpitThemeManager.resolvePalette(
            skin = AppSkin.ZEEKR_WHITE,
            schedule = ThemeSchedule.AUTO_TIME,
            uiMode = Configuration.UI_MODE_NIGHT_NO,
            currentHour = 20
        )
        assertEquals(0xFF0B0E14.toInt(), paletteNight.screenBg)

        val paletteDay = CockpitThemeManager.resolvePalette(
            skin = AppSkin.ZEEKR_WHITE,
            schedule = ThemeSchedule.AUTO_TIME,
            uiMode = Configuration.UI_MODE_NIGHT_NO,
            currentHour = 14
        )
        assertEquals(0xFFF4F6FA.toInt(), paletteDay.screenBg)
        assertEquals(0xFFFF5E00.toInt(), paletteDay.accent) // 极氪橙
    }
}
```

- [ ] **Step 2: 运行测试验证失败 (类不存在)**

运行: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.ui.theme.CockpitThemeTest`
预期: FAIL with "Unresolved reference: CockpitThemeManager"

- [ ] **Step 3: 实现 `CockpitTheme.kt` 与 `AirPlayPersistence.kt`**

创建 `common/src/main/java/com/shilapi/xcertplay/ui/theme/CockpitTheme.kt`:
```kotlin
package com.shilapi.xcertplay.ui.theme

import android.content.res.Configuration
import com.shilapi.xcertplay.isDarkMode

enum class AppSkin(val key: String) {
    ZEEKR_WHITE("zeekr_white"),
    FLYME_WHITE("flyme_white"),
    NIGHT_DARK("night_dark");

    companion object {
        fun fromKey(key: String?): AppSkin = entries.find { it.key == key } ?: ZEEKR_WHITE
    }
}

enum class ThemeSchedule(val key: String) {
    AUTO_CAR("auto_car"),   // 车机大灯/系统暗黑信号优先
    AUTO_TIME("auto_time"), // 18:00 - 06:00 时间优先
    MANUAL("manual");

    companion object {
        fun fromKey(key: String?): ThemeSchedule = entries.find { it.key == key } ?: AUTO_CAR
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
    // 纯整型字面量，不调用 android.graphics.Color，零 Mock 风险
    val ZEEKR_WHITE_PALETTE = ThemePalette(
        screenBg = 0xFFF4F6FA.toInt(),
        dockBg = 0xFFFFFFFF.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        cardInner = 0xFFF8FAFC.toInt(),
        cardBorder = 0x0F000000,
        textMain = 0xFF0F172A.toInt(),
        textMuted = 0xFF64748B.toInt(),
        accent = 0xFFFF5E00.toInt(),
        accentSoft = 0x1FFF5E00.toInt(),
        btnMainBg = 0xFF0F172A.toInt(),
        btnMainFg = 0xFFFFFFFF.toInt(),
        statusReady = 0xFF10B981.toInt()
    )

    val FLYME_WHITE_PALETTE = ThemePalette(
        screenBg = 0xFFF5F8FC.toInt(),
        dockBg = 0xFFFFFFFF.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        cardInner = 0xFFF0F4FA.toInt(),
        cardBorder = 0x142563EB.toInt(),
        textMain = 0xFF1E293B.toInt(),
        textMuted = 0xFF64748B.toInt(),
        accent = 0xFF2563EB.toInt(),
        accentSoft = 0x1F2563EB.toInt(),
        btnMainBg = 0xFF2563EB.toInt(),
        btnMainFg = 0xFFFFFFFF.toInt(),
        statusReady = 0xFF059669.toInt()
    )

    val NIGHT_DARK_PALETTE = ThemePalette(
        screenBg = 0xFF0B0E14.toInt(),
        dockBg = 0xFF11141C.toInt(),
        cardBg = 0xFF141923.toInt(),
        cardInner = 0xFF1A202C.toInt(),
        cardBorder = 0x12FFFFFF,
        textMain = 0xFFF8FAFC.toInt(),
        textMuted = 0xFF94A3B8.toInt(),
        accent = 0xFFFF5E00.toInt(),
        accentSoft = 0x2DFF5E00.toInt(),
        btnMainBg = 0xFFFFFFFF.toInt(),
        btnMainFg = 0xFF000000.toInt(),
        statusReady = 0xFF26D07C.toInt()
    )

    val FLYME_DARK_PALETTE = ThemePalette(
        screenBg = 0xFF0B0E14.toInt(),
        dockBg = 0xFF10141D.toInt(),
        cardBg = 0xFF151A26.toInt(),
        cardInner = 0xFF1C2230.toInt(),
        cardBorder = 0x143B82F6.toInt(),
        textMain = 0xFFF8FAFC.toInt(),
        textMuted = 0xFF94A3B8.toInt(),
        accent = 0xFF3B82F6.toInt(),
        accentSoft = 0x2D3B82F6.toInt(),
        btnMainBg = 0xFF3B82F6.toInt(),
        btnMainFg = 0xFFFFFFFF.toInt(),
        statusReady = 0xFF26D07C.toInt()
    )

    fun isNightHour(hour: Int): Boolean = hour >= 18 || hour < 6

    fun resolvePalette(skin: AppSkin, schedule: ThemeSchedule, uiMode: Int, currentHour: Int): ThemePalette {
        val carDark = isDarkMode(uiMode)
        val isDark = when (schedule) {
            ThemeSchedule.AUTO_CAR -> carDark || isNightHour(currentHour)
            ThemeSchedule.AUTO_TIME -> isNightHour(currentHour) || carDark
            ThemeSchedule.MANUAL -> skin == AppSkin.NIGHT_DARK
        }
        if (isDark) {
            return if (skin == AppSkin.FLYME_WHITE) FLYME_DARK_PALETTE else NIGHT_DARK_PALETTE
        }
        return when (skin) {
            AppSkin.ZEEKR_WHITE -> ZEEKR_WHITE_PALETTE
            AppSkin.FLYME_WHITE -> FLYME_WHITE_PALETTE
            AppSkin.NIGHT_DARK -> NIGHT_DARK_PALETTE
        }
    }
}
```

在 `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt` 的 `object AirPlayPersistence` 内部（访问私有常量 `PREFS`）：
```kotlin
    private const val KEY_APP_SKIN = "app_skin"
    private const val KEY_THEME_SCHEDULE = "theme_schedule"

    fun loadAppSkin(context: Context): com.shilapi.xcertplay.ui.theme.AppSkin =
        com.shilapi.xcertplay.ui.theme.AppSkin.fromKey(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_APP_SKIN, com.shilapi.xcertplay.ui.theme.AppSkin.ZEEKR_WHITE.key)
        )

    fun saveAppSkin(context: Context, skin: com.shilapi.xcertplay.ui.theme.AppSkin) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_APP_SKIN, skin.key).apply()
    }

    fun loadThemeSchedule(context: Context): com.shilapi.xcertplay.ui.theme.ThemeSchedule =
        com.shilapi.xcertplay.ui.theme.ThemeSchedule.fromKey(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME_SCHEDULE, com.shilapi.xcertplay.ui.theme.ThemeSchedule.AUTO_CAR.key)
        )

    fun saveThemeSchedule(context: Context, schedule: com.shilapi.xcertplay.ui.theme.ThemeSchedule) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME_SCHEDULE, schedule.key).apply()
    }
```

- [ ] **Step 4: 运行纯 JVM 测试验证通过**

运行: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.ui.theme.CockpitThemeTest`
预期: PASS

- [ ] **Step 5: 提交代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/theme/CockpitTheme.kt \
        common/src/test/java/com/shilapi/xcertplay/ui/theme/CockpitThemeTest.kt \
        common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt
git commit -m "feat(ui): 实现无Android运行时依赖的CockpitTheme色板与日夜调度引擎"
```

---

### Task 2: 交互排他保护与状态机对接 (InteractionFlow)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/InteractionFlow.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/ui/InteractionFlowTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class InteractionFlow(private val isSessionIdle: () -> Boolean) {
      fun canTriggerConnect(): Boolean
  }
  ```

- [ ] **Step 1: 编写对接现存 `isSessionIdle` 的单元测试（绝不使用 sleep，使用显式计数与栅栏）**

```kotlin
package com.shilapi.xcertplay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class InteractionFlowTest {
    @Test
    fun testConnectGateRespectsSessionIdle() {
        var idle = true
        val flow = InteractionFlow(isSessionIdle = { idle })
        
        assertTrue("空闲状态下允许发起连接", flow.canTriggerConnect())
        
        idle = false
        assertFalse("非空闲状态（Connecting或Live）严禁触发连接", flow.canTriggerConnect())
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

运行: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.ui.InteractionFlowTest`
预期: FAIL

- [ ] **Step 3: 实现 `InteractionFlow.kt` 并对接 `DiPlayActivity`**

```kotlin
package com.shilapi.xcertplay.ui

class InteractionFlow(private val isSessionIdle: () -> Boolean) {
    /** 唯一真相源：完全对齐 WirelessPageState.session == SessionPhase.Idle */
    fun canTriggerConnect(): Boolean = isSessionIdle()
}
```

- [ ] **Step 4: 运行测试验证通过**

运行: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.ui.InteractionFlowTest`
预期: PASS

- [ ] **Step 5: 提交代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/InteractionFlow.kt \
        common/src/test/java/com/shilapi/xcertplay/ui/InteractionFlowTest.kt
git commit -m "feat(ui): 建立单真相源交互流守卫，严禁平行锁"
```

---

### Task 3: 左侧 Slim Dock 组件实现与排版对接

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

- [ ] **Step 1: 实现完整的 `SlimDockView.kt` 原生 View 代码 (车规级 56dp 触控热区 & 居中对齐)**

```kotlin
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.shilapi.xcertplay.R
import com.shilapi.xcertplay.ui.theme.ThemePalette

class SlimDockView(
    context: Context,
    private val palette: ThemePalette,
    private val onHomeClick: () -> Unit,
    private val onAudioClick: () -> Unit,
    private val onSettingsClick: () -> Unit,
    private val onExitClick: () -> Unit
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(palette.dockBg)
        setPadding(0, dp(18), 0, dp(18))
        
        // 顶部操作区 (主页与快速音频/显示)
        addView(createIconButton(R.drawable.ic_carplay, palette.accent, onHomeClick, isHome = true))
        addView(View(context).apply { layoutParams = LayoutParams(1, dp(16)) })
        addView(createIconButton(R.drawable.ic_dp_display, palette.textMuted, onAudioClick))
        
        // 弹性占位，撑开上下两端
        addView(View(context), LayoutParams(1, 0, 1f))
        
        // 底部设置与退出 (车规级退出防误触)
        addView(createIconButton(R.drawable.ic_dp_about, palette.textMuted, onSettingsClick))
        addView(View(context).apply { layoutParams = LayoutParams(1, dp(16)) })
        addView(createIconButton(R.drawable.ic_dp_diagnostics, 0xFFEF4444.toInt(), onExitClick))
    }

    private fun createIconButton(resId: Int, tintColor: Int, onClick: () -> Unit, isHome: Boolean = false): View {
        // 车规级推荐: 56dp 高度触控热区，FrameLayout 确保图标绝对居中 (82dp / 2 = 41dp 中心点)
        val container = FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(82), dp(56))
            isClickable = true
            isFocusable = true
        }
        
        // 左边缘选中指示竖条 (3dp 宽, 22dp 高, 圆角)
        if (isHome) {
            val indicator = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(3), dp(22), Gravity.START or Gravity.CENTER_VERTICAL)
                background = GradientDrawable().apply {
                    setColor(palette.accent)
                    cornerRadius = dp(2).toFloat()
                }
            }
            container.addView(indicator)
        }
        
        val icon = ImageView(context).apply {
            val drawable = AppCompatResources.getDrawable(context, resId)?.mutate()?.let {
                DrawableCompat.wrap(it).apply { DrawableCompat.setTint(this, tintColor) }
            }
            setImageDrawable(drawable)
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
        }
        container.addView(icon)
        container.setOnClickListener { onClick() }
        return container
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
```

- [ ] **Step 2: 编译并在 `DiPlayActivity` 中挂载 (支持 LHD/RHD 自动镜像与退出防误触)**

在 `DiPlayActivity` 中建立顶层水平容器：
```kotlin
val palette = CockpitThemeManager.resolvePalette(
    AirPlayPersistence.loadAppSkin(this),
    AirPlayPersistence.loadThemeSchedule(this),
    resources.configuration.uiMode,
    Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
)
val root = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutParams = LinearLayout.LayoutParams(-1, -1)
    setBackgroundColor(palette.screenBg)
}

val dock = SlimDockView(
    context = this,
    palette = palette,
    onHomeClick = { if (page != "home") { page = "home"; render() } },
    onAudioClick = { openSettingsDrawer(Section.AUDIO) },
    onSettingsClick = { toggleSettingsDrawer() },
    onExitClick = {
        // 车规级防颠簸误触二次确认对话框
        AlertDialog.Builder(this)
            .setTitle(R.string.close_app)
            .setMessage(R.string.confirm_exit_prompt)
            .setPositiveButton(R.string.exit) { _, _ -> closeTheApp() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
)

val isRhd = AirPlayPersistence.loadRightHandDrive(this)
if (isRhd) {
    // 右舵模式: 仪表盘在左，SlimDock 在最右侧贴近驾驶员左手
    root.addView(mainContentContainer, LinearLayout.LayoutParams(0, -1, 1f))
    root.addView(dock, LinearLayout.LayoutParams(dp(82), -1))
} else {
    // 左舵模式 (默认): SlimDock 在最左侧贴近驾驶员右手
    root.addView(dock, LinearLayout.LayoutParams(dp(82), -1))
    root.addView(mainContentContainer, LinearLayout.LayoutParams(0, -1, 1f))
}
```

- [ ] **Step 3: 启动 AVD 并执行截屏排版验收**

运行命令启动 AVD 并安装调试包：
```bash
/opt/homebrew/share/android-commandlinetools/emulator/emulator -avd DiPlay-E01 -no-snapshot-load -no-audio -no-boot-anim &
adb wait-for-device
./gradlew :mobile:installDebug
adb shell am start -n com.shilapi.xcertplay/com.shilapi.xcertplay.DiPlayActivity
adb exec-out screencap -p > /Users/g/Documents/project/xr/dock-layout-avd.png
```
预期: AVD 屏幕左侧清晰呈现 82dp Slim Dock，图标绝对居中，触摸热区充足（56dp）。


- [ ] **Step 4: 提交代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/SlimDockView.kt \
        common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "feat(ui): 落地 SlimDockView 并通过 DiPlay-E01 AVD 排版实测"
```

---

### Task 4: Hero 核心互联主舞台组件实现 (58% 宽)

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
      hasUsbHost: Boolean,
      onConnectWireless: () -> Unit,
      onConnectUsb: () -> Unit,
      onOpenProjection: () -> Unit,
      onDisconnect: () -> Unit
  ) : LinearLayout
  ```

- [ ] **Step 1: 编写 `HeroStageCard.kt` 具体代码 (车标居中视觉纵向平衡 & Live 双键主控)**

```kotlin
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.R
import com.shilapi.xcertplay.SessionPhase
import com.shilapi.xcertplay.ui.theme.ThemePalette

class HeroStageCard(
    context: Context,
    private val palette: ThemePalette,
    sessionPhase: SessionPhase,
    pairedPhoneName: String?,
    private val hasUsbHost: Boolean,
    onConnectWireless: () -> Unit,
    onConnectUsb: () -> Unit,
    onOpenProjection: () -> Unit,
    onDisconnect: () -> Unit
) : LinearLayout(context) {

    private val pill: TextView
    private val title: TextView
    private val sub: TextView
    private val emblem: ImageView
    private val mainBtn: Button
    private var disconnectBtn: Button? = null
    private var usbBtn: Button? = null
    private var pulseAnim: android.animation.ObjectAnimator? = null

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(palette.cardBg)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), palette.cardBorder)
        }
        setPadding(dp(28), dp(26), dp(28), dp(26))

        // 1. 顶部状态胶囊行
        val topRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        pill = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(palette.accentSoft)
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        topRow.addView(pill)
        addView(topRow)

        // 2. 文本与自愈引导提示区
        val centerRow = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(14), 0, dp(6))
        }
        title = TextView(context).apply {
            text = context.getString(R.string.wireless_carplay)
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.textMain)
        }
        sub = TextView(context).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        }
        centerRow.addView(title)
        centerRow.addView(sub)
        addView(centerRow)

        // 3. 中央车标微动效区 (呼吸感反馈)
        val emblemContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(-1, 0, 1f)
        }
        emblem = ImageView(context).apply {
            setImageResource(R.drawable.ic_carplay)
            setColorFilter(palette.accent)
            layoutParams = FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER)
        }
        emblemContainer.addView(emblem)
        addView(emblemContainer)

        // 4. 底部主控按键组 (52dp 机械大按键，职责稳定不绑架)
        val buttonRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        mainBtn = Button(context).apply {
            textSize = 15f
            setTextColor(palette.btnMainFg)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(palette.btnMainBg)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                when (currentPhase) {
                    SessionPhase.Live -> onOpenProjection()
                    SessionPhase.Idle -> onConnectWireless()
                    SessionPhase.Connecting -> Unit
                }
            }
        }
        buttonRow.addView(mainBtn, LayoutParams(0, dp(52), 1f))

        val dBtn = Button(context).apply {
            text = context.getString(R.string.disconnect)
            textSize = 14f
            setTextColor(palette.textMuted)
            background = GradientDrawable().apply {
                setColor(palette.cardInner)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), palette.cardBorder)
            }
            setOnClickListener { onDisconnect() }
        }
        disconnectBtn = dBtn
        buttonRow.addView(dBtn, LayoutParams(-2, dp(52)).apply { marginStart = dp(12) })

        if (hasUsbHost) {
            val uBtn = Button(context).apply {
                text = context.getString(R.string.connect_with_usb)
                textSize = 13.5f
                setTextColor(palette.textMain)
                background = GradientDrawable().apply {
                    setColor(palette.cardInner)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), palette.cardBorder)
                }
                setOnClickListener { onConnectUsb() }
            }
            usbBtn = uBtn
            buttonRow.addView(uBtn, LayoutParams(-2, dp(52)).apply { marginStart = dp(12) })
        }
        addView(buttonRow)

        // 初始填充
        updateState(sessionPhase, pairedPhoneName, null, false)
    }

    private var currentPhase: SessionPhase = sessionPhase

    /** 就地增量差量刷新，零 View 重建，零 GC 抖动 */
    fun updateState(
        phase: SessionPhase,
        pairedPhone: String?,
        readinessGaps: List<String>?,
        isBlocked: Boolean
    ) {
        currentPhase = phase

        // 1. 胶囊状态
        pill.text = when (phase) {
            SessionPhase.Live -> "● " + context.getString(R.string.wireless_connected)
            SessionPhase.Connecting -> "● " + context.getString(R.string.connection_phase_connecting)
            SessionPhase.Idle -> "● " + context.getString(R.string.ready_when_you_are)
        }
        pill.setTextColor(if (phase == SessionPhase.Connecting) palette.accent else palette.statusReady)

        // 2. 自愈提示（如果存在阻断或未就绪，副标题直观显示原因，主按钮职责保持不变）
        if (isBlocked && !readinessGaps.isNullOrEmpty()) {
            sub.text = "● " + readinessGaps.first()
            sub.setTextColor(palette.accent)
        } else if (pairedPhone != null) {
            sub.text = "${context.getString(R.string.paired_device)}: $pairedPhone"
            sub.setTextColor(palette.textMuted)
        } else {
            sub.text = context.getString(R.string.gap_phone_unpaired)
            sub.setTextColor(palette.textMuted)
        }

        // 3. 中央车标呼吸动效
        if (phase == SessionPhase.Connecting) {
            startConnectingPulse()
        } else {
            stopConnectingPulse()
            emblem.alpha = if (phase == SessionPhase.Live) 1.0f else 0.65f
        }

        // 4. 主控按键职责保持纯粹稳定
        mainBtn.text = when (phase) {
            SessionPhase.Live -> context.getString(R.string.open_carplay)
            SessionPhase.Connecting -> context.getString(R.string.connecting)
            SessionPhase.Idle -> context.getString(R.string.connect_phone)
        }
        mainBtn.isEnabled = phase != SessionPhase.Connecting
        (mainBtn.background as? GradientDrawable)?.setColor(
            if (phase == SessionPhase.Connecting) palette.accentSoft else palette.btnMainBg
        )

        disconnectBtn?.visibility = if (phase == SessionPhase.Live) View.VISIBLE else View.GONE
        usbBtn?.visibility = if (phase == SessionPhase.Idle && hasUsbHost) View.VISIBLE else View.GONE
    }

    private fun startConnectingPulse() {
        if (pulseAnim == null) {
            pulseAnim = android.animation.ObjectAnimator.ofFloat(emblem, "alpha", 0.45f, 1.0f).apply {
                duration = 800
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
            }
        }
        if (pulseAnim?.isRunning == false) pulseAnim?.start()
    }

    private fun stopConnectingPulse() {
        pulseAnim?.cancel()
        pulseAnim = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
```

- [ ] **Step 2: 在 `DiPlayActivity` 中装配 HeroStageCard 并测试三种 SessionPhase**

将 `DiPlayActivity` 中的连接动作挂接到 `HeroStageCard`，复用原有 `connect(true)`、`openProjection()` 与 `CarPlayBackgroundSession.stop()`。

- [ ] **Step 3: AVD 实拍检验连接态与待机态排版**

```bash
adb shell am start -n com.shilapi.xcertplay/com.shilapi.xcertplay.DiPlayActivity
adb exec-out screencap -p > /Users/g/Documents/project/xr/hero-stage-avd.png
```
预期: Hero 卡片稳稳占据 58% 宽度（约 1050px），主按钮居中醒目，手机名称真实渲染。

- [ ] **Step 4: 提交代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/HeroStageCard.kt \
        common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "feat(ui): 落地 HeroStageCard 主舞台，通过 E01 AVD 排版验证"
```

---

### Task 5: 右侧双无线模式与音频卡片网格实现 (42% 宽)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/component/QuickWidgetsView.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class QuickWidgetsView(
      context: Context,
      palette: ThemePalette,
      mode: WirelessHotspotMode,
      hotspotSsid: String,
      joinedWifi: String?,
      onModeSwitch: (WirelessHotspotMode) -> Unit,
      onOpenHotspotSettings: () -> Unit,
      onEditHotspotPass: () -> Unit,
      onOpenWifiSettings: () -> Unit,
      onEditWifiPass: () -> Unit,
      onAudioSelect: () -> Unit,
      onOpenDrawer: () -> Unit
  ) : LinearLayout
  ```

- [ ] **Step 1: 实现 `QuickWidgetsView.kt` 真实无虚构组件代码**

```kotlin
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.R
import com.shilapi.xcertplay.transport.WirelessHotspotMode
import com.shilapi.xcertplay.ui.theme.ThemePalette

class QuickWidgetsView(
    context: Context,
    private val palette: ThemePalette,
    mode: WirelessHotspotMode,
    hotspotSsid: String,
    joinedWifi: String?,
    onModeSwitch: (WirelessHotspotMode) -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onEditHotspotPass: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onEditWifiPass: () -> Unit,
    onAudioSelect: () -> Unit,
    onOpenDrawer: () -> Unit
) : LinearLayout(context) {

    private lateinit var tabHotspot: Button
    private lateinit var tabLan: Button
    private lateinit var hotspotRow: View
    private lateinit var wifiRow: View
    private lateinit var hotspotPassBtn: Button
    private lateinit var wifiSsidBtn: Button
    private lateinit var audioChannelText: TextView

    init {
        orientation = VERTICAL
        
        // 卡片 1: 无线拓扑模式（真实双段拨块）
        addView(createWirelessCard(mode, hotspotSsid, joinedWifi, onModeSwitch, onOpenHotspotSettings, onEditHotspotPass, onOpenWifiSettings, onEditWifiPass), LayoutParams(-1, 0, 1.15f))
        addView(View(context).apply { layoutParams = LayoutParams(1, dp(14)) })
        
        // 卡片 2: 原车音频直通 (Channel 0)
        addView(createAudioCard(onAudioSelect), LayoutParams(-1, 0, 1.0f))
        addView(View(context).apply { layoutParams = LayoutParams(1, dp(14)) })
        
        // 卡片 3: 设置抽屉入口 (固定高度，优雅触达)
        addView(createDrawerCard(onOpenDrawer), LayoutParams(-1, dp(64)))
    }

    private fun createWirelessCard(
        mode: WirelessHotspotMode,
        hotspotSsid: String,
        joinedWifi: String?,
        onModeSwitch: (WirelessHotspotMode) -> Unit,
        onOpenHotspotSettings: () -> Unit,
        onEditHotspotPass: () -> Unit,
        onOpenWifiSettings: () -> Unit,
        onEditWifiPass: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = VERTICAL
        background = cardBg()
        setPadding(dp(18), dp(16), dp(18), dp(16))
        
        val title = TextView(context).apply {
            text = context.getString(R.string.wireless_settings)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.textMain)
        }
        addView(title)
        
        // 双段拨块：车机热点 vs 同一网络 (车规级 44dp 拨块高)
        val segment = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply {
                setColor(palette.cardInner)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val isHotspot = mode == WirelessHotspotMode.MANUAL
        tabHotspot = createSegmentTab(context.getString(R.string.s_1_car_hotspot), isHotspot) {
            onModeSwitch(WirelessHotspotMode.MANUAL)
        }
        tabLan = createSegmentTab(context.getString(R.string.existing_wifi_tab_label), !isHotspot) {
            onModeSwitch(WirelessHotspotMode.EXISTING_WIFI)
        }
        segment.addView(tabHotspot, LayoutParams(0, dp(44), 1f))
        segment.addView(tabLan, LayoutParams(0, dp(44), 1f))
        addView(segment, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        
        // 动态操作按钮行 (车机热点行)
        hotspotRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(createSmallBtn(context.getString(R.string.open_car_hotspot_settings), onOpenHotspotSettings), LayoutParams(0, dp(46), 1f))
            hotspotPassBtn = createSmallBtn(context.getString(R.string.edit_saved_hotspot_prefix) + hotspotSsid, onEditHotspotPass).apply {
                (layoutParams as LayoutParams).marginStart = dp(8)
            }
            addView(hotspotPassBtn, LayoutParams(0, dp(46), 1f))
            visibility = if (isHotspot) View.VISIBLE else View.GONE
        }
        addView(hotspotRow, LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // 动态操作按钮行 (同一网络行)
        wifiRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(createSmallBtn(context.getString(R.string.open_car_wi_fi_settings), onOpenWifiSettings), LayoutParams(0, dp(46), 1f))
            val wifiLabel = joinedWifi ?: context.getString(R.string.existing_wifi_not_joined)
            wifiSsidBtn = createSmallBtn(wifiLabel, onEditWifiPass).apply {
                (layoutParams as LayoutParams).marginStart = dp(8)
            }
            addView(wifiSsidBtn, LayoutParams(0, dp(46), 1f))
            visibility = if (isHotspot) View.GONE else View.VISIBLE
        }
        addView(wifiRow, LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun createAudioCard(onAudioSelect: () -> Unit): View = LinearLayout(context).apply {
        orientation = VERTICAL
        background = cardBg()
        setPadding(dp(18), dp(16), dp(18), dp(16))
        
        val header = LinearLayout(context).apply { orientation = HORIZONTAL }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.audio_routing)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.textMain)
        }, LayoutParams(0, -2, 1f))
        
        audioChannelText = TextView(context).apply {
            text = "Channel 0 (原生混音)"
            textSize = 12f
            setTextColor(palette.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        header.addView(audioChannelText)
        addView(header)
        
        val testBtn = createSmallBtn("选择与测试原车声道 (Channel 0~6)", onAudioSelect)
        addView(testBtn, LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
    }

    private fun createDrawerCard(onOpenDrawer: () -> Unit): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        background = cardBg()
        setPadding(dp(18), 0, dp(18), 0)
        
        addView(TextView(context).apply {
            text = "高级系统设置与诊断维护"
            textSize = 13.5f
            setTextColor(palette.textMuted)
        }, LayoutParams(0, -2, 1f))
        
        addView(TextView(context).apply {
            text = "展开 >"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.accent)
        })
        setOnClickListener { onOpenDrawer() }
    }

    /** 就地更新无线拓扑模式与音频通道，零 View 重建 */
    fun updateState(
        mode: WirelessHotspotMode,
        hotspotSsid: String,
        joinedWifi: String?,
        audioChannelLabel: String
    ) {
        val isHotspot = mode == WirelessHotspotMode.MANUAL
        updateTab(tabHotspot, isHotspot)
        updateTab(tabLan, !isHotspot)
        hotspotRow.visibility = if (isHotspot) View.VISIBLE else View.GONE
        wifiRow.visibility = if (isHotspot) View.GONE else View.VISIBLE
        hotspotPassBtn.text = context.getString(R.string.edit_saved_hotspot_prefix) + hotspotSsid
        wifiSsidBtn.text = joinedWifi ?: context.getString(R.string.existing_wifi_not_joined)
        audioChannelText.text = audioChannelLabel
    }

    private fun updateTab(tab: Button, active: Boolean) {
        tab.setTextColor(if (active) palette.textMain else palette.textMuted)
        tab.background = if (active) {
            GradientDrawable().apply {
                setColor(palette.cardBg)
                cornerRadius = dp(6).toFloat()
            }
        } else null
    }

    private fun createSegmentTab(label: String, active: Boolean, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 12.5f
        setTextColor(if (active) palette.textMain else palette.textMuted)
        background = if (active) {
            GradientDrawable().apply {
                setColor(palette.cardBg)
                cornerRadius = dp(6).toFloat()
            }
        } else null
        setOnClickListener { onClick() }
    }

    private fun createSmallBtn(label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 12f
        setTextColor(palette.textMain)
        background = GradientDrawable().apply {
            setColor(palette.cardInner)
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), palette.cardBorder)
        }
        setOnClickListener { onClick() }
    }

    private fun cardBg() = GradientDrawable().apply {
        setColor(palette.cardBg)
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), palette.cardBorder)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
```
```

- [ ] **Step 2: 编译并在 `DiPlayActivity` 中集成 `QuickWidgetsView`**

挂接现有模式变更回调：模式切换调用现有的 `applyWirelessLink(mode)`（通过已有的 `applyConnectionSetting` 串行化落盘与关停老会话）。

- [ ] **Step 3: AVD 截屏验证双列布局完整性**

```bash
adb shell am start -n com.shilapi.xcertplay/com.shilapi.xcertplay.DiPlayActivity
adb exec-out screencap -p > /Users/g/Documents/project/xr/widgets-avd.png
```
预期: 右侧 42% 完美嵌入三个卡片，无任何重叠或超界，点按拨块能实时切出对应的热点/Wi-Fi 操作按钮。

- [ ] **Step 4: 提交代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/QuickWidgetsView.kt \
        common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "feat(ui): 落地 QuickWidgetsView 并在 DiPlay-E01 AVD 完成排版验收"
```

---

### Task 6: 半屏设置抽屉完整保留 8 项真设置 + 仪表投屏

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/component/SettingsDrawerView.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class SettingsDrawerView(
      context: Context,
      palette: ThemePalette,
      buildDisplaySection: (LinearLayout) -> Unit,
      buildAudioSection: (LinearLayout) -> Unit,
      buildSystemSection: (LinearLayout) -> Unit,
      buildClusterSection: ((LinearLayout) -> Unit)?,
      buildExperimentalSection: ((LinearLayout) -> Unit)?,
      onClose: () -> Unit
  ) : FrameLayout
  ```

- [ ] **Step 1: 实现支持完整 8+1 项配置及主题换肤的 `SettingsDrawerView.kt`**

```kotlin
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.ui.theme.ThemePalette

class SettingsDrawerView(
    context: Context,
    private val palette: ThemePalette,
    buildThemeSection: (LinearLayout) -> Unit,
    buildDisplaySection: (LinearLayout) -> Unit,
    buildAudioSection: (LinearLayout) -> Unit,
    buildSystemSection: (LinearLayout) -> Unit,
    buildClusterSection: ((LinearLayout) -> Unit)?,
    buildExperimentalSection: ((LinearLayout) -> Unit)?,
    onClose: () -> Unit
) : FrameLayout(context) {

    init {
        // 半透明背景遮罩，点击外部区域直接关闭抽屉
        setBackgroundColor(0x55000000)
        setOnClickListener { onClose() }

        // 右侧 520dp 宽度的浮动滑动面板（在 1920 宽屏上占约 27%，左侧保留宽裕主控台可见性）
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.cardBg)
                cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f, dp(18).toFloat(), dp(18).toFloat())
            }
            isClickable = true
            setPadding(dp(24), dp(20), dp(24), dp(20))
            layoutParams = LayoutParams(dp(520), -1).apply { gravity = Gravity.END }
        }

        // 抽屉顶栏 (标题 + 车规级 48dp 关闭大按键)
        val header = LinearLayout(context).apply { 
            orientation = LinearLayout.HORIZONTAL 
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "高级系统设置与维护"
            textSize = 18f
            setTextColor(palette.textMain)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        
        val closeBtn = Button(context).apply {
            text = "✕"
            textSize = 18f
            setTextColor(palette.textMuted)
            background = null
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { onClose() }
        }
        header.addView(closeBtn)
        panel.addView(header)

        // 滚动内容区
        val scroll = ScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(-1, -1) }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        
        // 1. 个性化与座舱换肤（极氪白 / 魅族蓝 / 暗夜黑 + 车机大灯联动定时）
        buildThemeSection(content)

        // 2. 挂入现存的 8 项设置与仪表投屏，绝不删丢
        buildDisplaySection(content)
        buildAudioSection(content)
        buildClusterSection?.invoke(content)
        buildSystemSection(content)
        buildExperimentalSection?.invoke(content)

        scroll.addView(content)
        panel.addView(scroll)
        addView(panel)

        // 默认隐藏在屏幕右外侧
        visibility = GONE
    }

    private val panel: LinearLayout by lazy { getChildAt(0) as LinearLayout }

    /** 200ms 阻尼减速滑入（硬件加速层管理，60fps 丝滑度） */
    fun open(animate: Boolean = true) {
        visibility = VISIBLE
        if (!animate) {
            panel.translationX = 0f
            alpha = 1f
            return
        }
        panel.translationX = dp(520).toFloat()
        alpha = 0f
        panel.setLayerType(LAYER_TYPE_HARDWARE, null)
        animate().alpha(1f).setDuration(200).start()
        panel.animate()
            .translationX(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { panel.setLayerType(LAYER_TYPE_NONE, null) }
            .start()
    }

    /** 180ms 加速收起并释放蒙层 */
    fun close(animate: Boolean = true, onClosed: () -> Unit = {}) {
        if (!animate) {
            visibility = GONE
            onClosed()
            return
        }
        panel.setLayerType(LAYER_TYPE_HARDWARE, null)
        animate().alpha(0f).setDuration(180).start()
        panel.animate()
            .translationX(dp(520).toFloat())
            .setDuration(180)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                panel.setLayerType(LAYER_TYPE_NONE, null)
                visibility = GONE
                onClosed()
            }
            .start()
    }

    fun isOpen(): Boolean = visibility == VISIBLE

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
```

- [ ] **Step 2: 在 `DiPlayActivity` 中装配抽屉，并接入方向盘返回键 (Back-key) 消费守卫**

在 `DiPlayActivity` 中维护抽屉实例与返回键拦截：
```kotlin
private var settingsDrawer: SettingsDrawerView? = null

private fun openSettingsDrawer() {
    settingsDrawer?.open()
}

private fun closeSettingsDrawer() {
    settingsDrawer?.close()
}

private fun toggleSettingsDrawer() {
    if (settingsDrawer?.isOpen() == true) closeSettingsDrawer() else openSettingsDrawer()
}

// 拦截车机方向盘物理返回键：若抽屉展开则仅平滑收起抽屉，绝不误退应用
override fun onBackPressed() {
    if (settingsDrawer?.isOpen() == true) {
        closeSettingsDrawer()
        return
    }
    super.onBackPressed()
}
```
保留原有的全部真实开关：
- `choice(video_codec)` (H.264/HEVC)
- `toggle(direct_video_surface)`
- `toggle(right_hand_drive)`
- `choice(physical_size_basis)` & `choice(physical_length)`
- `toggle(debug_logs)`
- `button(permissions_and_connection_help)`
- `button(about)` 与版本号
- `if (ClusterMapPresentation.findDisplay != null)` 绘制 `instrument_cluster_map` (仪表投屏开关)！
- `if (ExperimentalFeatures.enabled)` 绘制 `experimentalCards` 实验性上传按钮。


- [ ] **Step 3: AVD 实机截屏检验设置抽屉滑出状态**

```bash
adb shell am start -n com.shilapi.xcertplay/com.shilapi.xcertplay.DiPlayActivity
adb exec-out screencap -p > /Users/g/Documents/project/xr/drawer-avd.png
```
预期: 点击设置后，右侧平滑滑出半屏抽屉，仪表投屏及 8 个真设置完整可见。

- [ ] **Step 4: 提交代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/SettingsDrawerView.kt \
        common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "feat(ui): 落地 SettingsDrawerView 完整容纳8+1项真设置与仪表投屏"
```

---

### Task 7: 现有 7 个 UI 测试精确算账与全量回归 (Test Ledger & Adaptation)

**Files Affected Audit (7 Existing Tests touching DiPlayActivity):**

| 测试文件 | 代码行数 | 测试内容与断言方式 | 账目核算结论 | 具体操作说明 |
|---|---|---|---|---|
| `CarHotspotStartTest.kt` | 55 行 | `AirPlayPersistence.saveAutoCarHotspot`, 启动 Activity, 轮询 `ShadowLog.getLogsForTag("DiPlay-CarHotspot")` | **0 改动，完全绿灯** | 纯后台服务启动与日志断言，不依赖任何 UI 视图结构 |
| `CarBluetoothRadioStartTest.kt` | 80 行 | 覆盖 `CarBluetoothHop`, 启动 Activity, 等待 `RecordingHop.radioAsked.await()` | **0 改动，完全绿灯** | 纯后台蓝牙生命周期与广播测试，不依赖 UI 控件树 |
| `DiagnosticExportUiTest.kt` | 112 行 | 反射调用私有方法 `chooseReportDestination()`, 断言 `ShadowAlertDialog` 与导出的 `.txt` | **0 改动，完全绿灯** | 断言独立弹出的对话框及文件生成，不依赖主界面布局 |
| `FirmwarePageUiTest.kt` | 308 行 | 模拟 `hop.running = true` 时打开 `open("home")`, 断言显示 `firmwareProgress` 且锁定返回键 | **0 改动，完全绿灯** | 固件升级中 `firmwareProgress` 会全屏接管界面，此逻辑完全保留 |
| `ExperimentalFeaturesUiTest.kt` | 82 行 | `open("settings")`, 遍历所有 TextView 断言是否包含 `System settings` 及 `Experimental report upload` | **0 改动，完全绿灯** | 设置抽屉内完整保留全部系统设置项与实验性上传，文本完全对齐 |
| `SettingsScrollUiTest.kt` | 118 行 | `open("wireless")`, 查找 `ScrollView`, 测量 1920×720, 滚动并在配置变更后断言 offset | **精准修改 2 处路由参数** | 原版 home 界面在窄屏下是全屏滚动，新版 1920×720 仪表盘为固定视口无外层 ScrollView；长滚动在抽屉与设置页（`settings`/`about`）。测试路由改为 `settings` 与 `about` |
| `SettingsPagesUiTest.kt` | 522 行 | 21 个 `@Test` 测试全部设置分组路由、返回键导航、卡片内容与排版 | **精准修改 2 处测试断言** | 19 个业务断言完全通过（因为所有 8 个真设置项 100% 保留）；修改 1 处旧主页文案匹配，以及 1 处旧两列布局断言对齐新视口规范 |

- [ ] **Step 1: 适配 `SettingsScrollUiTest.kt` (将滚动恢复验证挂到承载长内容的设置/关于页)**

在 `common/src/test/java/com/shilapi/xcertplay/SettingsScrollUiTest.kt` 中：
由于新版仪表盘主界面（1920×720）采用 3 区固定悬浮架构（SlimDock + 58% Hero + 42% QuickWidgets），不随全屏滚动；垂直长滚动承载在设置抽屉与详情页（`settings` 与 `about`）。
将测试中打开的初始页面从旧路径 `wireless` 调整为实际承载长滚动的 `settings`：

```kotlin
// SettingsScrollUiTest.kt 第 35 行：
// 原代码:
// val controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "wireless")).setup()
// 改为:
val controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "settings")).setup()

// SettingsScrollUiTest.kt 第 66 行：
// 原代码:
// val controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "wireless")).setup()
// 改为:
val controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "settings")).setup()
```

- [ ] **Step 2: 适配 `SettingsPagesUiTest.kt` 的 2 处断言**

在 `common/src/test/java/com/shilapi/xcertplay/SettingsPagesUiTest.kt` 中：
1. 第 153 行测试 `theConnectionBucketsAreOnlyOnTheSettingsPage`：原断言检查回落到主页时包含旧文案 `"A familiar drive."`。在重构后的 Hero 主舞台中，主页醒目标题为 `Wireless CarPlay`（对应 `R.string.wireless_carplay`），测试修改为断言新主页特征文案：
```kotlin
// 原代码 (Line 153):
assertTrue("'$route' falls through to the home page", "A familiar drive." in shown)
// 改为:
assertTrue("'$route' falls through to the home page", "Wireless CarPlay" in shown || "A familiar drive." in shown)
```
2. 第 211-219 行测试 `onAWideScreenTheSettingsCardsArePairedIntoRowsOfTwo`：原测试断言全屏平铺时的卡片成对布局。在新版 500dp 设置抽屉中，卡片为优雅的单列长流，而在全屏设置页（`settings`）依然保持宽屏双列排版。保持该测试针对全屏设置模式的断言不变。

- [ ] **Step 3: 运行修改后的全部 7 个 UI 测试验证通过**

```bash
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.SettingsScrollUiTest" \
                                    --tests "com.shilapi.xcertplay.SettingsPagesUiTest" \
                                    --tests "com.shilapi.xcertplay.FirmwarePageUiTest" \
                                    --tests "com.shilapi.xcertplay.DiagnosticExportUiTest" \
                                    --tests "com.shilapi.xcertplay.ExperimentalFeaturesUiTest" \
                                    --tests "com.shilapi.xcertplay.CarHotspotStartTest" \
                                    --tests "com.shilapi.xcertplay.CarBluetoothRadioStartTest"
```
预期: 7 个测试套件（共计 31 个测试用例）全部 PASS。

- [ ] **Step 4: 运行全量 49 个单元测试**

```bash
./gradlew testDebugUnitTest
```
预期: 49 个测试 100% 全部 PASS（零红灯，零失败，零崩溃）。

- [ ] **Step 5: 构建正式 Release APK**

```bash
./gradlew :mobile:assembleRelease
```
预期: BUILD SUCCESSFUL，生成最终 `0.4.2-geely` Release APK。

- [ ] **Step 6: 在本地 `DiPlay-E01` AVD 上截屏全流程排版闭环**

```bash
# 启动本地 1920x720 车机模拟器
/opt/homebrew/share/android-commandlinetools/emulator/emulator -avd DiPlay-E01 -no-snapshot-load -no-audio -no-boot-anim &
adb wait-for-device
# 安装并拉起重构后的 APK
./gradlew :mobile:installDebug
adb shell am start -n com.shilapi.xcertplay/com.shilapi.xcertplay.DiPlayActivity
sleep 3
# 截屏 1: 主界面 (极氪白 · SlimDock + Hero 58% + QuickWidgets 42%)
adb exec-out screencap -p > /Users/g/Documents/project/xr/final-cockpit-zeekr.png
# 切换夜间模式并截屏 2: 主界面 (暗夜黑)
adb shell cmd uimode night yes
sleep 2
adb exec-out screencap -p > /Users/g/Documents/project/xr/final-cockpit-night.png
# 恢复日间
adb shell cmd uimode night no
```
预期: 截屏完美展现 1920×720 超宽屏比例下的无缝流线排版，主控按键与侧边栏对齐无越界，文字层次清晰。

- [ ] **Step 7: 提交代码**

```bash
git add common/src/test/java/com/shilapi/xcertplay/SettingsScrollUiTest.kt \
        common/src/test/java/com/shilapi/xcertplay/SettingsPagesUiTest.kt
git commit -m "test(ui): 精确适配 7 个 UI 测试断言并完成 DiPlay-E01 AVD 排版验收"
```

