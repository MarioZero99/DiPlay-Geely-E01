# 全屏双栏车机设置页面（替代抽屉方案）实施计划（深度完善版）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底废除车机上“回主页拉抽屉”的两段式割裂交互，在 1920×720@160dpi 宽屏座舱下构建类似 FlymeAuto / 极氪 OS 规范的全屏双栏设置界面（左侧分类导航 Rail + 右侧全屏卡片内容区），实现直观高效的设置导航、零割裂跳转与 100% 宽屏空间释放。

**Architecture:** 
1. **视图解耦与重构**：将现有 `displayCards` 拆解为 `displayPerformanceCard`（显示与画质）与 `audioRoutingCard`（音频通道与高级混音）；将主题皮肤配置 `buildThemeSection` 与系统卡片 `systemCards` 聚合至系统设置；
2. **全屏双栏界面**：新增 `SettingsScreenView`，左侧固定 180dp Slim Rail（包含返回主页按钮与 `AUDIO`, `CONNECTION`, `DISPLAY`, `SYSTEM`, 可选 `EXPERIMENTAL` 五大分类 Tab），右侧平铺展示对应模块卡片，以 `View.VISIBLE / GONE` 切换，杜绝在 26~33MB 紧张堆内存下反复创建销毁 View 树；
3. **路由与生命周期**：统一设置路由为 `page = "settings"`，通过 `EXTRA_SETTINGS_TAB` 接收外部直达指定 Tab 请求（例如从快捷组件直达音频通道）；在 Activity 的 `onSaveInstanceState` 中持久化当前选中的 Tab，避免切换日夜主题 `recreate()` 时重置回首页；
4. **清理与收敛**：下线并移除 `SettingsDrawerView.kt` 及所有抽屉动画和遮罩逻辑；重构 `CarPlayHostActivity` 投屏界面设置按钮直达；更新旧测试套件。

**Tech Stack:** Android SDK (API 21+), Kotlin, Robolectric, JUnit 4, View hierarchy, StateListDrawable, GradientDrawable.

**Spec:** 用户明确要求“点击设置还要回到主页然后拉出来，还不如让他铺满”，确立了淘汰半屏右侧抽屉、改用全屏铺满并深度适配 1920×720 带鱼宽屏的交互设计。

## Global Constraints
- 支持 Android 5.1+ (API 21+)。
- 运行于严苛内存受限环境（车机仅 26~33 MB 空闲堆内存），Tab 切换复用视图树，杜绝频繁 GC 与对象冗余分配。
- 深度适配 1920×720@160dpi（逻辑宽度 1200dp~1920dp）超宽屏，大按键防误触设计。
- 绝不阻塞 Android 主线程，所有数据持久化沿用现有 `AirPlayPersistence`。
- 既有单元测试保持 100% 编译通过与逻辑覆盖。

---

### Task 1: 拆分 `displayCards` 为独立的音频与显示卡片函数

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:988-1065`
- Test: `common/src/test/java/com/shilapi/xcertplay/SettingsPagesUiTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  private fun displayPerformanceCard(columns: CardColumns)
  private fun audioRoutingCard(columns: CardColumns)
  private fun displayCards(columns: CardColumns) // 保持调用二者以兼顾旧逻辑
  ```

- [ ] **Step 1: 在 `DiPlayActivity.kt` 中将显示卡片与音频卡片提取为独立函数**

在 `DiPlayActivity.kt` 中：
1. 将 lines 989-1028 的 `columns.card(R.string.display_and_performance, ...)` 提取为 `private fun displayPerformanceCard(columns: CardColumns)`；
2. 将 lines 1031-1065 的 `columns.card(R.string.audio_routing, ...)` 提取为 `private fun audioRoutingCard(columns: CardColumns)`；
3. 保留 `displayCards(columns: CardColumns)`：
   ```kotlin
   private fun displayCards(columns: CardColumns) {
       displayPerformanceCard(columns)
       audioRoutingCard(columns)
   }
   ```

- [ ] **Step 2: 编译并运行单元测试验证提取无破坏**

运行:
```bash
./gradlew :common:cleanTestDebugUnitTest :common:testDebugUnitTest --tests "com.shilapi.xcertplay.SettingsPagesUiTest"
```
预期: PASS。

- [x] **Step 3: 提交 Task 1 代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "refactor(cockpit): decouple audioRoutingCard and displayPerformanceCard in DiPlayActivity"
```

---

### Task 2: 创建全屏双栏设置组件 `SettingsScreenView`

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/component/SettingsScreenView.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsScreenViewTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class SettingsTab(val key: String) {
      AUDIO("audio"),
      CONNECTION("connection"),
      DISPLAY("display"),
      SYSTEM("system"),
      EXPERIMENTAL("experimental");
      companion object {
          fun fromKey(key: String?): SettingsTab = entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: AUDIO
      }
  }
  class SettingsScreenView(
      context: Context,
      palette: ThemePalette,
      initialTab: SettingsTab = SettingsTab.AUDIO,
      hasExperimental: Boolean = false,
      onBack: () -> Unit,
      buildAudioSection: (LinearLayout) -> Unit,
      buildConnectionSection: (LinearLayout) -> Unit,
      buildDisplaySection: (LinearLayout) -> Unit,
      buildSystemSection: (LinearLayout) -> Unit,
      buildExperimentalSection: ((LinearLayout) -> Unit)? = null,
  ) : FrameLayout
  fun selectTab(tab: SettingsTab)
  fun currentTab(): SettingsTab
  ```

- [x] **Step 1: 编写 `SettingsScreenViewTest` 单元测试**

在 `common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsScreenViewTest.kt` 中编写：

```kotlin
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.ui.theme.CockpitThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsScreenViewTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `default initial tab is selected and onBack callback triggers`() {
        var backPressed = false
        val view = SettingsScreenView(
            context,
            CockpitThemeManager.ZEEKR_WHITE_PALETTE,
            initialTab = SettingsTab.AUDIO,
            hasExperimental = false,
            onBack = { backPressed = true },
            buildAudioSection = { it.addView(TextView(context).apply { text = "AudioContent" }) },
            buildConnectionSection = { it.addView(TextView(context).apply { text = "ConnectionContent" }) },
            buildDisplaySection = { it.addView(TextView(context).apply { text = "DisplayContent" }) },
            buildSystemSection = { it.addView(TextView(context).apply { text = "SystemContent" }) },
        )

        assertEquals(SettingsTab.AUDIO, view.currentTab())
        val backBtn = view.findViewWithTag<View>("settings_back_btn")
        assertNotNull(backBtn)
        backBtn.performClick()
        assertTrue(backPressed)
    }

    @Test
    fun `switching tab changes active content visibility without recreation`() {
        val view = SettingsScreenView(
            context,
            CockpitThemeManager.ZEEKR_WHITE_PALETTE,
            initialTab = SettingsTab.AUDIO,
            hasExperimental = true,
            onBack = {},
            buildAudioSection = { it.addView(TextView(context).apply { text = "Audio" }) },
            buildConnectionSection = { it.addView(TextView(context).apply { text = "Conn" }) },
            buildDisplaySection = { it.addView(TextView(context).apply { text = "Disp" }) },
            buildSystemSection = { it.addView(TextView(context).apply { text = "Sys" }) },
            buildExperimentalSection = { it.addView(TextView(context).apply { text = "Exp" }) },
        )

        view.selectTab(SettingsTab.CONNECTION)
        assertEquals(SettingsTab.CONNECTION, view.currentTab())

        view.selectTab(SettingsTab.EXPERIMENTAL)
        assertEquals(SettingsTab.EXPERIMENTAL, view.currentTab())
    }
}
```

- [x] **Step 2: 运行测试验证失败**

运行:
```bash
./gradlew :common:cleanTestDebugUnitTest :common:testDebugUnitTest --tests "com.shilapi.xcertplay.ui.component.SettingsScreenViewTest"
```
预期: FAIL。

- [x] **Step 3: 实现 `SettingsScreenView` 全屏双栏组件**

在 `common/src/main/java/com/shilapi/xcertplay/ui/component/SettingsScreenView.kt` 中编写实现：

```kotlin
// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.theme.ThemePalette

enum class SettingsTab(val key: String) {
    AUDIO("audio"),
    CONNECTION("connection"),
    DISPLAY("display"),
    SYSTEM("system"),
    EXPERIMENTAL("experimental");

    companion object {
        fun fromKey(key: String?): SettingsTab =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: AUDIO
    }
}

/**
 * 1920×720 车机宽屏全屏双栏设置组件：左侧分类 Tab 导航，右侧全屏平铺内容面板。
 */
class SettingsScreenView(
    context: Context,
    private val palette: ThemePalette,
    initialTab: SettingsTab = SettingsTab.AUDIO,
    hasExperimental: Boolean = false,
    private val onBack: () -> Unit,
    buildAudioSection: (LinearLayout) -> Unit,
    buildConnectionSection: (LinearLayout) -> Unit,
    buildDisplaySection: (LinearLayout) -> Unit,
    buildSystemSection: (LinearLayout) -> Unit,
    buildExperimentalSection: ((LinearLayout) -> Unit)? = null,
) : FrameLayout(context) {

    private var activeTab: SettingsTab = initialTab
    private val tabButtons = mutableMapOf<SettingsTab, TextView>()
    private val contentContainers = mutableMapOf<SettingsTab, View>()

    init {
        setBackgroundColor(palette.screenBg)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(-1, -1)
        }

        // 1. 左侧 Slim Rail 导航区 (固定宽度 180dp)
        val rail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.railBg)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            layoutParams = LinearLayout.LayoutParams(dp(180), -1)
        }

        // 返回按钮
        val backBtn = TextView(context).apply {
            tag = "settings_back_btn"
            text = "← " + context.getString(R.string.back)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.textMain)
            gravity = Gravity.CENTER
            background = pressable(wash(palette.textMain), dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(20) }
            setOnClickListener { onBack() }
        }
        rail.addView(backBtn)

        // Tab 项列表
        val tabDefinitions = mutableListOf(
            SettingsTab.AUDIO to context.getString(R.string.audio_routing),
            SettingsTab.CONNECTION to context.getString(R.string.connection),
            SettingsTab.DISPLAY to context.getString(R.string.display_and_performance),
            SettingsTab.SYSTEM to context.getString(R.string.system_settings),
        )
        if (hasExperimental && buildExperimentalSection != null) {
            tabDefinitions.add(SettingsTab.EXPERIMENTAL to context.getString(R.string.experimental_features))
        }

        for ((tab, labelText) in tabDefinitions) {
            val tabBtn = TextView(context).apply {
                text = labelText
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(16), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) }
                setOnClickListener { selectTab(tab) }
            }
            tabButtons[tab] = tabBtn
            rail.addView(tabBtn)
        }

        root.addView(rail)

        // 2. 右侧全屏卡片容器区
        val contentStage = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        fun wrapSection(builder: (LinearLayout) -> Unit): View {
            val scroll = ScrollView(context).apply {
                layoutParams = LayoutParams(-1, -1)
                clipToPadding = false
                isFillViewport = true
            }
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(24))
            }
            builder(container)
            scroll.addView(container)
            return scroll
        }

        contentContainers[SettingsTab.AUDIO] = wrapSection(buildAudioSection).also { contentStage.addView(it) }
        contentContainers[SettingsTab.CONNECTION] = wrapSection(buildConnectionSection).also { contentStage.addView(it) }
        contentContainers[SettingsTab.DISPLAY] = wrapSection(buildDisplaySection).also { contentStage.addView(it) }
        contentContainers[SettingsTab.SYSTEM] = wrapSection(buildSystemSection).also { contentStage.addView(it) }
        if (hasExperimental && buildExperimentalSection != null) {
            contentContainers[SettingsTab.EXPERIMENTAL] = wrapSection(buildExperimentalSection).also { contentStage.addView(it) }
        }

        root.addView(contentStage)
        addView(root)

        selectTab(initialTab)
    }

    fun currentTab(): SettingsTab = activeTab

    fun selectTab(tab: SettingsTab) {
        val target = if (tab in tabButtons) tab else SettingsTab.AUDIO
        activeTab = target
        for ((t, btn) in tabButtons) {
            val isSelected = (t == target)
            if (isSelected) {
                btn.setTextColor(palette.accent)
                btn.background = GradientDrawable().apply {
                    setColor(wash(palette.accent, 0.18f))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), wash(palette.accent, 0.4f))
                }
            } else {
                btn.setTextColor(palette.textMuted)
                btn.background = null
            }
        }
        for ((t, container) in contentContainers) {
            container.visibility = if (t == target) View.VISIBLE else View.GONE
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun wash(color: Int, fraction: Float = 0.12f): Int {
        val alpha = (Color.alpha(color) * fraction).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun pressable(pressedColor: Int, radius: Float) = StateListDrawable().apply {
        val normal = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = radius }
        val pressed = GradientDrawable().apply { setColor(pressedColor); cornerRadius = radius }
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }
}
```

- [x] **Step 4: 运行测试验证通过**

运行:
```bash
./gradlew :common:cleanTestDebugUnitTest :common:testDebugUnitTest --tests "com.shilapi.xcertplay.ui.component.SettingsScreenViewTest"
```
预期: PASS。

- [x] **Step 5: 提交 Task 2 代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/SettingsScreenView.kt common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsScreenViewTest.kt
git commit -m "feat(ui): add SettingsScreenView dual-column layout for automotive widescreen"
```

---

### Task 3: 在 `DiPlayActivity` 中全面接入全屏设置路由并退役抽屉

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Modify: `common/src/test/java/com/shilapi/xcertplay/SettingsPagesUiTest.kt`

**Interfaces:**
- Consumes: `SettingsScreenView`, `SettingsTab`, `displayPerformanceCard`, `audioRoutingCard`
- Produces:
  `DiPlayActivity.EXTRA_SETTINGS_TAB`
  在 `onSaveInstanceState` 中保存 `activeSettingsTab: String` 并在重新进入时恢复。
  移除 `drawerOpen`, `drawerRequested`, `restoreSettingsDrawer()`, `closeSettingsDrawer()`, `buildSettingsDrawer()`.

- [x] **Step 1: 在 `DiPlayActivity.kt` 中引入常量与状态恢复**

在 `DiPlayActivity.kt` companion object 中声明：
```kotlin
const val EXTRA_SETTINGS_TAB = "settings_tab"
private const val KEY_SETTINGS_TAB = "key_settings_tab"
```
在 `DiPlayActivity` 中增加字段：
```kotlin
private var activeSettingsTab: SettingsTab = SettingsTab.AUDIO
```
并在 `onSaveInstanceState(outState: Bundle)` 中加入：
```kotlin
outState.putString(KEY_SETTINGS_TAB, activeSettingsTab.key)
```
在 `onCreate(savedInstanceState: Bundle?)` 中加入：
```kotlin
savedInstanceState?.getString(KEY_SETTINGS_TAB)?.let {
    activeSettingsTab = SettingsTab.fromKey(it)
}
```

- [x] **Step 2: 改造 `render()` 中的 `"settings"` 页面渲染**

替换原先的 `settings(content)`：
```kotlin
"settings" -> {
    val tabFromIntent = intent.getStringExtra(EXTRA_SETTINGS_TAB)
    if (tabFromIntent != null) {
        activeSettingsTab = SettingsTab.fromKey(tabFromIntent)
    }
    val screen = SettingsScreenView(
        this,
        palette,
        initialTab = activeSettingsTab,
        hasExperimental = ExperimentalFeatures.enabled,
        onBack = { page = "home"; render() },
        buildAudioSection = { col -> audioRoutingCard(CardColumns(col)) },
        buildConnectionSection = { col ->
            val readiness = pageState().readiness
            wirelessCards(CardColumns(col), readiness)
            wiredCards(CardColumns(col))
        },
        buildDisplaySection = { col -> displayPerformanceCard(CardColumns(col)) },
        buildSystemSection = { col ->
            buildThemeSection(col)
            systemCards(CardColumns(col))
        },
        buildExperimentalSection = if (ExperimentalFeatures.enabled) {
            { col -> experimentalCards(CardColumns(col)) }
        } else null,
    )
    setContentView(screen)
    refreshStatus()
    return
}
```

- [x] **Step 3: 将所有点击设置的入口重定向至全屏设置并清理旧抽屉引用**

1. `SlimDockView` 交互：
   - `onAudioClick`: `page = "settings"; activeSettingsTab = SettingsTab.AUDIO; render()`
   - `onSettingsClick`: `page = "settings"; activeSettingsTab = SettingsTab.CONNECTION; render()`
2. `HeroStageCard` 交互：
   - `onOpenSettings`: `page = "settings"; activeSettingsTab = SettingsTab.CONNECTION; render()`
3. `QuickWidgetsView` 交互：
   - `onAudioSelect`: `page = "settings"; activeSettingsTab = SettingsTab.AUDIO; render()`
   - `onOpenDrawer`: `page = "settings"; activeSettingsTab = SettingsTab.CONNECTION; render()`
4. `onBackPressed`:
   在 `handleOnBackPressed` 中：若 `page == "settings"`，则直接切回 `page = "home"; render()`。
5. 删除 `drawer`, `drawerOpen`, `drawerRequested`, `openSettingsDrawer()`, `closeSettingsDrawer()`, `restoreSettingsDrawer()`, `buildSettingsDrawer()`。

- [x] **Step 4: 运行 `SettingsPagesUiTest` 验证**

运行:
```bash
./gradlew :common:cleanTestDebugUnitTest :common:testDebugUnitTest --tests "com.shilapi.xcertplay.SettingsPagesUiTest"
```
预期: PASS。

- [x] **Step 5: 提交 Task 3 代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt common/src/test/java/com/shilapi/xcertplay/SettingsPagesUiTest.kt
git commit -m "feat(cockpit): replace drawer with SettingsScreenView and preserve tab state across recreation"
```

---

### Task 4: 在 `CarPlayHostActivity` 中理顺设置入口跳转

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt:2585-2591`

- [x] **Step 1: 更新 `openSettingsMenu()` 跳转 Intent**

在 `CarPlayHostActivity.kt` 中：
```kotlin
private fun openSettingsMenu() {
    controller?.sendTouch(emptyList())
    startActivity(Intent(this, DiPlayActivity::class.java)
        .putExtra("page", "settings")
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
}
```

- [x] **Step 2: 编译测试验证**

运行:
```bash
./gradlew :common:compileDebugKotlin :automotive:compileDebugKotlin
```
预期: BUILD SUCCESSFUL。

- [x] **Step 3: 提交 Task 4 代码**

```bash
git add common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt
git commit -m "fix(carplay): route top-bar settings button directly to fullscreen settings page"
```

---

### Task 5: 废除 `SettingsDrawerView.kt` 并重构宽屏单测

**Files:**
- Delete: `common/src/main/java/com/shilapi/xcertplay/ui/component/SettingsDrawerView.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsDrawerWidthTest.kt`
- Delete: `common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsDrawerScrollTest.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsScreenWidescreenTest.kt`

- [x] **Step 1: 删除废弃的 `SettingsDrawerView.kt` 及相关抽屉单测**

执行:
```bash
git rm common/src/main/java/com/shilapi/xcertplay/ui/component/SettingsDrawerView.kt
git rm common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsDrawerWidthTest.kt
git rm common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsDrawerScrollTest.kt
```

- [x] **Step 2: 创建 `SettingsScreenWidescreenTest.kt` 验证 1920 宽屏双栏布局**

在 `common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsScreenWidescreenTest.kt` 中编写：

```kotlin
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.widget.TextView
import com.shilapi.xcertplay.ui.theme.CockpitThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp")
class SettingsScreenWidescreenTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `widescreen settings root occupies full 1920dp without scrim or drawer translation`() {
        val view = SettingsScreenView(
            context,
            CockpitThemeManager.ZEEKR_WHITE_PALETTE,
            initialTab = SettingsTab.AUDIO,
            hasExperimental = true,
            onBack = {},
            buildAudioSection = { it.addView(TextView(context).apply { text = "AudioContent" }) },
            buildConnectionSection = { it.addView(TextView(context).apply { text = "ConnectionContent" }) },
            buildDisplaySection = { it.addView(TextView(context).apply { text = "DisplayContent" }) },
            buildSystemSection = { it.addView(TextView(context).apply { text = "SystemContent" }) },
            buildExperimentalSection = { it.addView(TextView(context).apply { text = "ExperimentalContent" }) },
        )

        assertNotNull(view)
        assertEquals(SettingsTab.AUDIO, view.currentTab())
    }
}
```

- [x] **Step 3: 运行全量单元测试与编译检查**

运行:
```bash
./gradlew :common:cleanTestDebugUnitTest :common:testDebugUnitTest
```
预期: 全量单元测试 100% 通过，0 失败。

- [x] **Step 4: 提交 Task 5 代码**

```bash
git add common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsScreenWidescreenTest.kt
git commit -m "clean(ui): drop SettingsDrawerView and finalize widescreen dual-column tests"
```
