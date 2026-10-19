# 车机主界面交互优化、抽屉扩展与线程/生命周期加固实施计划（修订版）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底解决车机主界面交互硬伤：重构连接模式为显式“有线/无线”对等选择并支持三态（未选/有线/无线）、连接中开放断开/取消按钮、将设置抽屉适配 1920×720@160dpi (1920dp) 宽屏、将音频通道选择网格重构为自适应等宽行布局并移除 `fullWidth` 补丁，同时加固主线程安全与生命周期管理。

**Architecture:** 
1. **单状态源（Single Source of Truth）三态管理**：复用现有 `KEY_WIRELESS_ENABLED`，在 `AirPlayPersistence` 中增加 `loadConnectionMode(context): Boolean?`（`null` 未选、`true` 无线、`false` 有线），统一管理状态，现有 5 个读取点无缝兼容且无分叉。
2. **连接模式选择与连接中取消**：在 `HeroStageCard` 中支持有线/无线平级选择；若用户选择有线且 `hasUsbHost == false` 时，明确展示禁用原因（硬件不支持 USB 主机模式）；在 `SessionPhase.Connecting` 下将 `disconnectBtn` 设为可见，点击防抖置灰并安全回调 `onDisconnect`。
3. **设置抽屉宽屏自适应**：以 E01 宽屏（1920×720@160dpi 即 1920dp 宽）为基准，将 `SettingsDrawerView` 展开宽度调整为宽屏自适应（800dp，约占 41.6%），留给左侧主界面 1120dp，并同步修正三处宽度使用点。
4. **音频通道自适应网格与 fullWidth 修复**：移除全局 `widthPixels` 算宽与 `fullWidth = true` 补丁；改用 3 行水平 `LinearLayout`（每行 7 个通道按钮，`layout_weight = 1f`），并在 `applyChannelEnabled` 中直接遍历 `channelButtons`。
5. **严谨的线程安全与生命周期管理**：给 `DiPlayActivity` 中的 `testToneTrack` 增加 `@Volatile` 修饰；对 `navigation_test.pcm` 做内存级缓存；保持原版 4500ms 超时并由 `toneStop` 安全管理；在 `onDestroy()` 中严密清除回调并释放音频 Track；在会话终止回调中增加 `!isFinishing && !isDestroyed` 安全守卫。

**Tech Stack:** Android SDK (API 21+), Kotlin, Robolectric, JUnit 4, AudioTrack.

**Spec:** 用户问题反馈与代码对齐评审（开放连接中断开、加长设置抽屉、音频网格自适应、禁止默认无线支持有线/无线显式选择、线程/生命周期加固）。

## Global Constraints
- 支持 Android 5.1+ (API 21+)。
- 内存受限环境（车机仅 26~33 MB 空闲堆内存），禁止在每秒 tick 中创建多余对象与频繁 GC。
- 绝不阻塞 Android 主线程（Main Looper），杜绝 UI 卡顿和 ANR。
- 保证既有单测 100% 通过，且新增单元测试必须在对应宽屏配置或生命周期模拟下真实通过。
- **每个新测试类都要写 `@Config(sdk = [28])`**：本机 JDK 下不 pin sdk 会走默认 targetSdk，
  Robolectric 在 `setUpApplicationState` 里就抛 `Failed to interact with raw FileDescriptor internals`，
  一条断言都跑不到。仓库里既有测试全部 pin 了 28，照抄。
- **执行前提**：工作区里还有一批未提交的 iAP2 识别改动（`Iap2Session`/`CarPlayController`/`Iap2CsmChannel`/
  `Iap2IdentificationClient`/`Iap2LinkChannel` + `Iap2ProfileSelection.kt` + `versionCode 43`）。
  先提交或 stash 再开这个计划，否则 Task 6 的构建失败无法归因。
- **建议顺序**：Task 1 → Task 2 → Task 4 → Task 3 → Task 5。
  Task 1 是数据层，Task 2 要读 `loadConnectionMode`，所以它必须在前；
  Task 4 只动设置页，和 Task 2 不冲突，可以并行；Task 5 最独立，放最后收尾。

---

### Task 1: 存储层状态收敛：复用 `KEY_WIRELESS_ENABLED` 扩展三态（未选/无线/有线）

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt:178-188`
- Test: `common/src/test/java/com/shilapi/xcertplay/ConnectionModePersistenceTest.kt`

**Interfaces:**
- Consumes: `AirPlayPersistence.PREFS`, `AirPlayPersistence.KEY_WIRELESS_ENABLED`
- Produces: 
  - `AirPlayPersistence.loadConnectionMode(context: Context): Boolean?` （`null` = 未选择, `true` = 无线, `false` = 有线）
  - `AirPlayPersistence.loadWirelessEnabled(context: Context): Boolean` （保持向后兼容，未选时兜底为 true，供现有 5 处调用点使用）
  - `AirPlayPersistence.saveWirelessEnabled(context: Context, enabled: Boolean)` （异步 apply 写入）

- [ ] **Step 1: 编写三态持久化单元测试**

新建 `common/src/test/java/com/shilapi/xcertplay/ConnectionModePersistenceTest.kt`：
```kotlin
package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class ConnectionModePersistenceTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `loadConnectionMode returns null when user has never chosen`() {
        // AirPlayPersistence.PREFS 是 private 的，这里写字面量：清的是它自己的 prefs 文件
        // "xcertplay_airplay"，不是 "diplay_preferences"（那是通知权限用的另一份）。
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        assertNull("初次未选择时必须返回 null，不得假定为有线或无线", AirPlayPersistence.loadConnectionMode(context))
        assertTrue("向后兼容的 loadWirelessEnabled 未选择时仍默认 true", AirPlayPersistence.loadWirelessEnabled(context))
    }

    @Test
    fun `saving wireless enabled updates both connection mode and wireless enabled`() {
        AirPlayPersistence.saveWirelessEnabled(context, false)
        assertEquals(false, AirPlayPersistence.loadConnectionMode(context))
        assertFalse(AirPlayPersistence.loadWirelessEnabled(context))

        AirPlayPersistence.saveWirelessEnabled(context, true)
        assertEquals(true, AirPlayPersistence.loadConnectionMode(context))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(context))
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

运行: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.ConnectionModePersistenceTest"`
预期: 编译报错，`AirPlayPersistence.loadConnectionMode` 尚未声明。

- [ ] **Step 3: 在 `AirPlayPersistence` object 中实现三态读取与兼容写入**

修改 `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt` 中的相关方法：
```kotlin
    /**
     * The connection mode explicitly chosen by the user:
     * - null: Not chosen yet (first launch or reset, UI should present choice)
     * - true: Wireless
     * - false: Wired (USB)
     */
    fun loadConnectionMode(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_WIRELESS_ENABLED)) prefs.getBoolean(KEY_WIRELESS_ENABLED, true) else null
    }

    /** Last transport the user actually started. Defaults to true if never chosen. */
    fun loadWirelessEnabled(context: Context): Boolean =
        loadConnectionMode(context) ?: true

    fun saveWirelessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIRELESS_ENABLED, enabled)
            .apply()
    }
```

- [ ] **Step 4: 运行测试验证通过**

运行: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.ConnectionModePersistenceTest"`
预期: PASS。

- [ ] **Step 5: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt common/src/test/java/com/shilapi/xcertplay/ConnectionModePersistenceTest.kt
git commit -m "feat(persistence): support tri-state connection mode without duplicating key"
```

---

### Task 2: 主卡片（HeroStageCard）交互改造：有线/无线显式选择、无 USB 原因展示、连接中取消

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/ui/component/HeroStageCard.kt`
- Modify: `common/src/main/res/values/strings.xml`
- Modify: `common/src/main/res/values-zh-rCN/strings.xml`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:532-552`
- Test: `common/src/test/java/com/shilapi/xcertplay/ui/component/HeroStageCardTest.kt`

**Interfaces:**
- 保持原构造器签名兼容，追加两个可选参数：
  ```kotlin
  class HeroStageCard(
      context: Context,
      private val palette: ThemePalette,
      sessionPhase: SessionPhase,
      pairedPhoneName: String?,
      private val hasUsbHost: Boolean,
      setupError: String?,
      supportedConnectionView: TextView?,
      adapterStatusView: TextView?,
      hotspotOffNote: String?,
      private val onConnectWireless: () -> Unit,
      private val onConnectUsb: () -> Unit,
      private val onOpenProjection: () -> Unit,
      private val onDisconnect: () -> Unit,
      private val onOpenSettings: () -> Unit,
      initialConnectionMode: Boolean? = null,
      private val onModeSelected: ((Boolean) -> Unit)? = null,
  )
  ```
- Produces: 
  - 连接中（`SessionPhase.Connecting`）时 `disconnectBtn.visibility = View.VISIBLE`，点击调用 `onDisconnect()` 并做按钮防抖；
  - 空闲状态（`SessionPhase.Idle`）下支持切换有线/无线；
  - 当处于有线模式但 `hasUsbHost == false` 时，按钮禁用并显示文字“车机硬件不支持 USB 主机模式”。

- [ ] **Step 1: 编写新增测试用例（并保持原有 5 个单测兼容）**

在 `common/src/test/java/com/shilapi/xcertplay/ui/component/HeroStageCardTest.kt` 中添加：
```kotlin
    @Test
    fun `disconnect button is visible during connecting phase`() {
        val connectingCard = card(SessionPhase.Connecting)
        val disconnectBtn = descendants(connectingCard)
            .filterIsInstance<Button>()
            .firstOrNull { it.text == context.getString(R.string.cancel) || it.text == context.getString(R.string.disconnect) }
        org.junit.Assert.assertNotNull("断开/取消按钮在连接中必须存在", disconnectBtn)
        assertEquals("断开/取消按钮在连接中必须可见", View.VISIBLE, disconnectBtn?.visibility)
    }

    @Test
    fun `when wired is selected but hasUsbHost is false, action button is disabled and explanation is shown`() {
        val card = HeroStageCard(
            context = context,
            palette = palette,
            sessionPhase = SessionPhase.Idle,
            pairedPhoneName = null,
            hasUsbHost = false,
            setupError = null,
            supportedConnectionView = null,
            adapterStatusView = null,
            hotspotOffNote = null,
            onConnectWireless = {},
            onConnectUsb = {},
            onOpenProjection = {},
            onDisconnect = {},
            onOpenSettings = {},
            initialConnectionMode = false, // 有线
        )
        val texts = texts(card)
        val expectedReason = context.getString(R.string.usb_host_not_supported)
        assertTrue("必须显示不支持 USB 主机的原因", expectedReason in texts)
    }
```

- [ ] **Step 2: 运行测试验证失败**

运行: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.ui.component.HeroStageCardTest"`
预期: FAIL。

- [ ] **Step 3: 添加字符串资源**

在 `common/src/main/res/values-zh-rCN/strings.xml` 中增加：
```xml
    <string name="usb_host_not_supported">当前车机硬件未检测到 USB 主机支持，无法使用有线连接</string>
    <string name="choose_connection_mode">请选择连接方式：有线连接或无线连接</string>
    <string name="connect_wired_prompt">请使用数据线将 iPhone 连接至车机 USB 接口</string>
    <string name="connect_wired">有线连接</string>
    <string name="connect_wireless">无线连接</string>
    <string name="switch_to_wireless">切换为无线</string>
    <string name="switch_to_wired">切换为有线</string>
```
在 `common/src/main/res/values/strings.xml` 中增加：
```xml
    <string name="usb_host_not_supported">USB host mode is not supported by this head unit</string>
    <string name="choose_connection_mode">Choose connection mode: Wired or Wireless</string>
    <string name="connect_wired_prompt">Connect your iPhone with a USB cable</string>
    <string name="connect_wired">Wired</string>
    <string name="connect_wireless">Wireless</string>
    <string name="switch_to_wireless">Switch to wireless</string>
    <string name="switch_to_wired">Switch to wired</string>
```

- [ ] **Step 4: 改造 `HeroStageCard.kt`**

1. 构造函数加入 `initialConnectionMode: Boolean? = null` 和 `private val onModeSelected: ((Boolean) -> Unit)? = null`；
2. 维护成员变量 `private var currentMode: Boolean? = initialConnectionMode`；
3. 在 `buttonRow` 中，`mainBtn.text` 的取值必须写死成这张表（`updateState` 每 tick 都会跑，这里是纯赋值，不新建对象）：

   | phase | currentMode | mainBtn.text |
   |---|---|---|
   | Connecting | 任意 | `connecting_to_your_iphone` |
   | Live | 任意 | `open_carplay` |
   | Idle | `null` | `choose_connection_mode` |
   | Idle | `false` | `connect_wired` |
   | Idle | `true` | `connect_wireless` |

   点击行为：
   - Idle + `null`：主按钮不连接，只给出选择；`wiredBtn` / `wirelessBtn` 两个次级按钮平级展示，点击后先 `onModeSelected(mode)` 再直接触发 `onConnectUsb()` / `onConnectWireless()`（选完即连，不要逼用户点两次）；
   - Idle + `false`（有线）：主按钮触发 `onConnectUsb()`；`hasUsbHost == false` 时主按钮 `isEnabled = false`、`alpha = 0.4f`，并在 notes 区显示 `usb_host_not_supported`；`hasUsbHost == true` 时可用。另一个次级按钮是“切换为无线”；
   - Idle + `true`（无线）：主按钮触发 `onConnectWireless()`，次级按钮“切换为有线”；
   - `hasUsbHost == false` 时“有线”一律禁用（含 `null` 分支里的 `wiredBtn`），原因同上，不要出现能点但点了没反应的按钮。
4. 断开按钮：**listener 在 `init` 里设一次**，不能放进 `updateState`——那样每秒 tick 都会 new 一个 lambda，正是这张卡文件头注释里说要避免的分配。防抖与恢复都在 listener 内自洽完成，不依赖 phase 变化：
   ```kotlin
   // init
   disconnectBtn.setOnClickListener {
       if (!disconnectBtn.isEnabled) return@setOnClickListener
       disconnectBtn.isEnabled = false
       onDisconnect()
       // 1.2s 后自己恢复：连接失败会停在原 phase，没有 phase 变化可以依附，否则按钮永久灰掉
       postDelayed({ disconnectBtn.isEnabled = true }, 1200)
   }

   // updateState
   disconnectBtn.visibility = if (phase != SessionPhase.Idle) View.VISIBLE else View.GONE
   disconnectBtn.text = if (phase == SessionPhase.Connecting) {
       context.getString(R.string.cancel)
   } else {
       context.getString(R.string.disconnect)
   }
   ```
5. 既有 `HeroStageCardTest` 里 `mainButton()` 的 `labels` 列表要加上 `R.string.connect_wired` / `connect_wireless` / `choose_connection_mode`，否则 `the idle label keeps the foreground its fill was chosen for` 这条会 `NoSuchElementException`——这是本次改造唯一需要改的既有测试，改动只是放宽查找条件，不断言任何新行为。
6. 在 `DiPlayActivity.kt:532` 中接入：
   ```kotlin
   initialConnectionMode = AirPlayPersistence.loadConnectionMode(this),
   onModeSelected = { mode ->
       AirPlayPersistence.saveWirelessEnabled(this, mode)
       render()
   },
   ```

- [ ] **Step 5: 运行单元测试验证通过**

运行: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.ui.component.HeroStageCardTest"`
预期: 原有 5 个测试 + 2 个新增测试共 7 个全部 PASS。

- [ ] **Step 6: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/HeroStageCard.kt common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt common/src/main/res/values/strings.xml common/src/main/res/values-zh-rCN/strings.xml common/src/test/java/com/shilapi/xcertplay/ui/component/HeroStageCardTest.kt
git commit -m "feat(ui): support explicit wired/wireless choice, USB host guard, and cancel while connecting"
```

---

### Task 3: 设置抽屉（SettingsDrawerView）宽屏适配与测试修复

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/ui/component/SettingsDrawerView.kt:61,135,164,261`
- Test: `common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsDrawerWidthTest.kt`

**Interfaces:**
- Consumes: `resources.configuration.screenWidthDp`, `resources.displayMetrics`
- Produces: 动态计算抽屉宽度，在 1920dp 宽屏上拉伸至 800dp（约 41.6%），并修正全部 3 处使用点。

- [ ] **Step 1: 编写带真实车机分辨率配置的单元测试**

新建 `common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsDrawerWidthTest.kt`：
```kotlin
package com.shilapi.xcertplay.ui.component

import android.content.Context
import com.shilapi.xcertplay.ui.theme.CockpitThemeManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsDrawerWidthTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    @Config(sdk = [28], qualifiers = "w1920dp-h720dp")
    fun `drawer panel width is at least 680dp on 1920dp widescreen cockpit`() {
        val drawer = SettingsDrawerView(
            context,
            CockpitThemeManager.ZEEKR_WHITE_PALETTE,
            {}, {}, {}, {}, null, {}
        )
        val computedWidth = drawer.computePanelWidthDp()
        assertTrue("1920dp宽屏车机上抽屉宽度应为800dp (>=680dp)，实际为 $computedWidth", computedWidth >= 680)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

运行: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.ui.component.SettingsDrawerWidthTest"`
预期: FAIL（`computePanelWidthDp` 不存在）。

- [ ] **Step 3: 修改 `SettingsDrawerView.kt` 中的宽度计算及 3 处使用点**

在 `SettingsDrawerView.kt` 中：
```kotlin
    companion object {
        // 只有在窄屏兜底分支里才会用到它；宽屏走上面的比例计算。
        const val PANEL_WIDTH_DP = 800
    }

    internal fun computePanelWidthDp(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return if (widthDp >= 1000) {
            // E01 宽屏为 1920dp，取 42% ≈ 806 后夹到 800dp，主界面留出 1120dp
            (widthDp * 0.42).toInt().coerceIn(680, 800)
        } else {
            // 窄屏：不超过屏幕减两侧留白，且不小于 320dp（Robolectric 默认 320dp 时命中这里）
            PANEL_WIDTH_DP.coerceAtMost((widthDp - 40).coerceAtLeast(320))
        }
    }

    private fun panelWidthPx(): Int = dp(computePanelWidthDp())
```
修改 3 处使用点：
1. **第 61 行**：
   ```kotlin
   layoutParams = LayoutParams(panelWidthPx(), -1).apply { gravity = Gravity.END }
   ```
2. **第 135 行（打开动画 translationX 兜底）**：
   ```kotlin
   panel.translationX = panel.width.takeIf { it > 0 }?.toFloat() ?: panelWidthPx().toFloat()
   ```
3. **第 164 行（关闭动画 translationX 兜底）**：
   ```kotlin
   panel.animate()
       .translationX(panel.width.takeIf { it > 0 }?.toFloat() ?: panelWidthPx().toFloat())
   ```

- [ ] **Step 4: 运行测试验证通过**

运行: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.ui.component.SettingsDrawerWidthTest"`
预期: PASS。

- [ ] **Step 5: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/ui/component/SettingsDrawerView.kt common/src/test/java/com/shilapi/xcertplay/ui/component/SettingsDrawerWidthTest.kt
git commit -m "feat(ui): adapt settings drawer width to 800dp for 1920dp cockpit"
```

---

### Task 4: 音频通道网格重构：自适应权重平铺、移除 fullWidth 补丁、修复 applyChannelEnabled

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:1005-1043,3123-3160`
- Test: `common/src/test/java/com/shilapi/xcertplay/ChannelSelectorLayoutTest.kt`

**Interfaces:**
- Consumes: `channelButtons: MutableList<Button>`, `navigationStreamType: Int`
- Produces: 
  - `channelSelector(): ViewGroup` （3 行 `LinearLayout`，每行 7 个通道按钮，`layout_weight = 1f`）
  - 移除 `fullWidth = true`，音频卡片回归标准布局
  - `applyChannelEnabled` 直接安全遍历 `channelButtons`

- [ ] **Step 1: 编写通道按钮网格的单元测试**

新建 `common/src/test/java/com/shilapi/xcertplay/ChannelSelectorLayoutTest.kt`：
```kotlin
package com.shilapi.xcertplay

import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class ChannelSelectorLayoutTest {
    @Test
    fun `channel selector contains 21 buttons in linear rows with weight 1f`() {
        // 必须带 page=settings：默认 page 是 "home"，而 channelSelector() 只在设置页里构建，
        // 不开设置页的话 channelButtons 是空的，断言 21 会红。
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", "settings")
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup()
        val activity = controller.get()
        val buttons = activity.getChannelButtonsForTest()
        assertEquals(21, buttons.size)
        // 验证每个按钮都是 0dp 宽加 1f weight，自适应卡片宽度
        buttons.forEach { btn ->
            val lp = btn.layoutParams as LinearLayout.LayoutParams
            assertEquals(1f, lp.weight, 0.01f)
            assertEquals(0, lp.width)
        }
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

运行: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.ChannelSelectorLayoutTest"`
预期: FAIL（`getChannelButtonsForTest` 不存在）。

- [ ] **Step 3: 修改 `DiPlayActivity.kt`**

1. 在 `DiPlayActivity.kt` 暴露 internal 测试方法：
   ```kotlin
   internal fun getChannelButtonsForTest(): List<Button> = channelButtons.toList()
   ```
2. **移除 `fullWidth = true` 补丁**（第 1005-1008 行）：
   ```kotlin
   // 移除历史注释与 fullWidth = true
   columns.card(R.string.audio_routing) { card ->
   ```
3. **重构 `applyChannelEnabled`**（第 1016-1028 行）：
   ```kotlin
   fun applyChannelEnabled(enabled: Boolean) {
       val alpha = if (enabled) 1f else 0.4f
       listOf(channelTitle, channelHint, saveButton).forEach {
           it.isEnabled = enabled
           it.alpha = alpha
       }
       channelButtons.forEach { child ->
           child.isEnabled = enabled
           child.alpha = alpha
       }
   }
   ```
4. **重构 `channelSelector()`**（第 3123-3158 行）：
   ```kotlin
   private fun channelSelector(): ViewGroup {
       channelButtons.clear()
       val container = LinearLayout(this).apply {
           orientation = LinearLayout.VERTICAL
           setPadding(0, dp(8), 0, dp(8))
       }
       val cols = 7
       var currentRow: LinearLayout? = null
       for (i in 0..20) {
           if (i % cols == 0) {
               currentRow = LinearLayout(this).apply {
                   orientation = LinearLayout.HORIZONTAL
                   layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                       if (i > 0) topMargin = dp(6)
                   }
               }
               container.addView(currentRow)
           }
           val btn = Button(this).apply {
               text = i.toString()
               isAllCaps = false
               textSize = 15f
               minHeight = dp(44)
               setPadding(0, 0, 0, 0)
               if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
               setOnClickListener {
                   val previous = navigationStreamType
                   navigationStreamType = i
                   if (previous != i) {
                       paintChannel(previous, false)
                       paintChannel(i, true)
                   }
                   playTestTone(i)
               }
           }
           val params = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
               val colIndex = i % cols
               marginStart = if (colIndex > 0) dp(3) else 0
               marginEnd = if (colIndex < cols - 1) dp(3) else 0
           }
           currentRow?.addView(btn, params)
           channelButtons.add(btn)
           paintChannel(i, i == navigationStreamType)
       }
       return container
   }
   ```

- [ ] **Step 4: 运行测试验证通过**

运行: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.ChannelSelectorLayoutTest"`
预期: PASS。

- [ ] **Step 5: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt common/src/test/java/com/shilapi/xcertplay/ChannelSelectorLayoutTest.kt
git commit -m "fix(audio): refactor channel selector to responsive linear rows and remove fullWidth hack"
```

---

### Task 5: 真实线程/生命周期加固：测试音 PCM 缓存、清理 toneStop 与 onDestroy 资源安全

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:180-185,385-390,549,3077-3110`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiPlayLifecycleAudioTest.kt`

**Interfaces:**
- Consumes: `AudioTrack`, `handler`, `isFinishing`, `isDestroyed`
- Produces: 
  - `@Volatile private var testToneTrack: AudioTrack? = null`
  - 内存级 PCM 缓存（杜绝连点重复读磁盘）
  - `onDestroy()` 严密清除 `toneStop` 回调并安全 release `testToneTrack`
  - 终止会话回调守卫 `if (!isFinishing && !isDestroyed) refreshStatus()`

- [ ] **Step 1: 编写生命周期资源释放与异步安全单元测试**

新建 `common/src/test/java/com/shilapi/xcertplay/DiPlayLifecycleAudioTest.kt`：
```kotlin
package com.shilapi.xcertplay

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class DiPlayLifecycleAudioTest {
    @Test
    fun `destroying activity clears test tone track and callbacks without leaking`() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        // 触发测试音播放
        activity.playTestToneForTest(1)
        // 先证明确实建出了 Track 和回调：否则下面两个 assertNull 在「AudioTrack 没建起来」时
        // 一样是绿的，这个测试就成了空转。Robolectric 的 ShadowAudioTrack 若返回未初始化，
        // 这条会红，那就说明这条路要在测试里 pin 一个可用的 track，而不是把断言删软。
        assertNotNull("测试音没能建出 AudioTrack，后面的清理断言等于空转", activity.getTestToneTrackForTest())
        assertNotNull("测试音没排上 toneStop，后面的取消断言等于空转", activity.getToneStopForTest())
        // 执行销毁
        controller.destroy()
        assertNull("onDestroy 必须将 testToneTrack 释放并置空", activity.getTestToneTrackForTest())
        assertNull("onDestroy 必须取消 toneStop 回调", activity.getToneStopForTest())
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

运行: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiPlayLifecycleAudioTest"`
预期: FAIL。

- [ ] **Step 3: 加固 `DiPlayActivity.kt` 线程与生命周期**

1. 将 `testToneTrack` 加上 `@Volatile`：
   ```kotlin
   @Volatile private var testToneTrack: AudioTrack? = null
   private var cachedTestTonePcm: ByteArray? = null
   ```
2. 在 `DiPlayActivity.kt:549` 的 `onDisconnect` 回调中加入 Activity 存活守卫：
   ```kotlin
   onDisconnect = {
       CarPlayBackgroundSession.stop {
           runOnUiThread {
               if (!isFinishing && !isDestroyed) {
                   refreshStatus()
               }
           }
       }
   }
   ```
3. 在 `playTestTone` 中加入 PCM 缓存与更严密的异常保护：
   ```kotlin
   private fun playTestTone(streamType: Int) {
       toneStop?.let { handler.removeCallbacks(it) }
       toneStop = null
       testToneTrack?.let { runCatching { it.stop(); it.release() } }
       testToneTrack = null
       var candidate: AudioTrack? = null
       val track = try {
           val pcm = cachedTestTonePcm ?: assets.open("navigation_test.pcm").use { it.readBytes() }
               .also { cachedTestTonePcm = it }
           AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
               AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
               candidate = it
               check(it.state == AudioTrack.STATE_INITIALIZED)
               check(it.write(pcm, 0, pcm.size) == pcm.size)
               it.play()
           }
       } catch (error: Exception) {
           val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
           candidate?.let { runCatching { it.release() } }
           Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
           toast(getString(R.string.audio_stream_unavailable, streamType, state.toString()))
           return
       }
       testToneTrack = track
       val stop = Runnable {
           testToneTrack?.let { runCatching { it.stop(); it.release() } }
           if (testToneTrack === track) testToneTrack = null
           toneStop = null
       }
       toneStop = stop
       handler.postDelayed(stop, 4500)
   }
   ```
4. 在 `onDestroy()`（第 385 行）中补全清理：
   ```kotlin
   override fun onDestroy() {
       handler.removeCallbacks(maintenanceTick)
       toneStop?.let { handler.removeCallbacks(it); toneStop = null }
       testToneTrack?.let { runCatching { it.stop(); it.release() }; testToneTrack = null }
       releaseAdapterPage()
       super.onDestroy()
   }
   ```
5. 暴露包级/internal 测试辅助方法：
   ```kotlin
   internal fun playTestToneForTest(streamType: Int) = playTestTone(streamType)
   internal fun getTestToneTrackForTest() = testToneTrack
   internal fun getToneStopForTest() = toneStop
   ```

- [ ] **Step 4: 运行测试验证通过**

运行: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiPlayLifecycleAudioTest"`
预期: PASS。

- [ ] **Step 5: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt common/src/test/java/com/shilapi/xcertplay/DiPlayLifecycleAudioTest.kt
git commit -m "fix(lifecycle): cache test tone pcm, guard activity destruction, and cleanup audio track"
```

---

### Task 6: 全模块联调测试验证与 APK 构建

**Files:**
- 全局变更文件
- Verification: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`

- [ ] **Step 1: 运行所有模块的 Debug 单元测试**

运行: `./gradlew testDebugUnitTest`
预期: `:shared`, `:common`, `:mobile`, `:automotive` 全部 BUILD SUCCESSFUL。
（基线：`--rerun-tasks` 真跑一遍是 `:common` 290 条 0 失败。注意 `-q` + 缓存命中会显示 BUILD SUCCESSFUL
而根本没跑测试，确认基线要用 `--rerun-tasks` 或看 `build/test-results` 的计数。）

- [ ] **Step 2: 运行 Debug APK 构建**

运行: `./gradlew assembleDebug`
预期: 成功生成 APK，且无 Lint/编译致命报错。

- [ ] **Step 3: 按仓库口径出正式包（真机验证用）**

```bash
DIPLAY_VENDOR_HOP_DIR=/Users/g/Documents/project/diplay-vendor-hop scripts/package-geely.sh official
```
只出 official 上车验证；`local` 包带云日志 key，不走 Release。装车后重点看三件事：
连接中点“取消”能真的退出来、抽屉在 1920 宽屏上的实际观感、音频通道 21 格都能点。

- [ ] **Step 4: 检查 Git 工作区干净度**

运行: `git status`
预期: 无多余临时文件或遗漏未跟踪文件。

---

## 执行记录（2026-10-19）

**状态**：Task 1–5 全部落地；Task 6 Step 1–2 通过；Step 3（出 official 包上车）未做——工作区里还有一批
未提交的 iAP2 改动，先让它落定再出包，否则构建失败无法归因。

**验证**：`:common` 304 条、`:shared` 517 条，0 失败；`./gradlew assembleDebug` 通过（17s，mobile 与
automotive 都出了 APK）。改动文件见 `git status`：`AirPlayPersistence.kt`、`DiPlayActivity.kt`、
`HeroStageCard.kt`、`SettingsDrawerView.kt`、两份 `strings.xml`，加 4 个新测试类与 3 个既有测试的标记修正。

**与计划的偏差（都已在代码注释里写清原因）**：

1. `@Volatile` 没加。原计划的前提是“测试音改到后台线程播放”，那个方案在评审时已经被否掉：`AudioTrack`
   的创建、写入、释放现在全在主线程，`testToneTrack` / `toneStop` 没有任何跨线程读写，加 `@Volatile`
   只是噪音。真正修掉的是 `onDestroy` 没清回调、没放 Track。
2. 没有新增 `connect_wired_prompt`：`connect_your_iphone_with_a_usb_cable` 已经是同一句文案（“Connect your
   iPhone with a USB cable”），重复一条只会让两个键各自漂移。
3. 多加了两条文案 `wired_carplay` / `carplay_title`。卡片标题原本硬编码 “WIRELESS CARPLAY”，选了有线还顶着
   这行字就是自相矛盾；标题现在随模式变。
4. `onModeSelected` 只写偏好，不 `render()`。卡片自己原地重绘（`paintActions`），而点击随即发起连接、状态马上
   又变一次，重建整页等于把驱动者正在看的树扔掉再画一遍。
5. 计划外必须改的既有测试：`SettingsPagesUiTest`（3 处）与 `SettingsDrawerUiTest`（2 处）一直拿
   "Connect phone" 当“这里是首页”的标记。首次进入没有偏好时主按钮现在说“Choose connection mode…”，
   标记换成了这一句。**这是全量跑出来的，不是预判到的**——单跑 `HeroStageCardTest` 是绿的。
6. 有线模式还要压掉无线 readiness 行和 `hotspotOffNote`。Activity 交进来的 readiness 是无线那条路的，
   选了有线却被告知“打开车机蓝牙 / 保存热点名字密码”，等于把另一条路的话念给这条路听。
7. `channelButtonsForTest` 放在 `playTestTone` 附近（`channelButtons` 字段旁），没有放到 `channelSelector`
   后面；`applyChannelEnabled` 改成遍历 `channelButtons`，因为单元格现在是“列→行→按钮”的孙节点，
   走 `grid.childCount` 只会把三行变灰、21 个按钮全亮着。
8. 抽屉宽度：`PANEL_WIDTH_DP` 改成 800（窄屏兜底用），宽屏走 `screenWidthDp * 0.42` 夹到 680–800。
   E01 上实际 806→800dp，占 41.6%。

