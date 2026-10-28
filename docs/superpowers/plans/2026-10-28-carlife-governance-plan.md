# 百度 CarLife 车机治理 (防抢 USB 与蓝牙自启) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为车机治理模块新增百度 CarLife 检测与双按钮防御治理功能，通过 Android 5.1 原生组件级特权控制精准禁用 CarLife 的 USB 监听广播与后台连接服务，彻底杜绝插线抢占 USB 与蓝牙唤醒冲突，同时保留桌面手动启动与一键恢复能力。

**Architecture:** 
采用分层治理架构：
1. 底层在 `:governance` 模块建立 `CarLifeGovernor`，基于 Android 原生 `PackageManager` 零耗时嗅探 CarLife 安装与组件状态，并通过 `CommandQueue` 将 `pm disable`/`pm enable`/`am force-stop` 拆解为单条严格 ≤ 220 字节的原子命令，调度至 `E01PrivilegedBridge` (uid 0) 执行；
2. 中间层在 `VehicleGovernance` Facade 统一管控持久化意图与开机自愈免疫；
3. UI 层在 `:common` 模块的 `DiPlayActivity`【车机治理】Tab 下新增双按钮操作卡片，提供实时状态徽章、防抖加载态与防误触确认弹窗。

**Tech Stack:** Kotlin 1.9+, Android SDK API 22 (Android 5.1 Lollipop MR1), Android Automotive, Coroutines (Flow/StateFlow), JUnit 4, Robolectric.

**Spec:** In-conversation spec approved by user on 2026-10-28 (Double-button card in Vehicle Governance, fine-grained component disablement, defensive command queue, Android 5.1 compatibility).

## Global Constraints

- **Platform Target:** Android 5.1 (API 22) MTK6735 / Geely E01 & Generic Android Head Units.
- **Command Budget:** 单条包装后命令严格 ≤ 220 字节（`MAX_COMMAND_BYTES = 220`），绝对禁止多组件分号拼接成超长行。
- **Component Precision:** 必须使用完整全限定类名，禁止缩写：
  - `com.baidu.carlifevehicle/com.baidu.carlifevehicle.broadcast.BroadcastActionReceiver`
  - `com.baidu.carlifevehicle/com.baidu.carlifevehicle.connect.AOAAccessoryReceiver`
  - `com.baidu.carlifevehicle/com.baidu.carlifevehicle.connect.ConnectService`
- **Execution Safety:** 所有特权命令必须在 `Dispatchers.IO` 执行，并施加 3000ms 硬超时保护；主线程仅做原生状态读取（零 IPC / 零阻塞）。
- **Design System:** 保持 DiPlay 1920×720 车机深色/浅色调色板（`ThemePalette`）与卡片布局（`CardColumns`），全部文案提取至 `strings.xml`。

---

### Task 1: 治理核心层 `CarLifeGovernor.kt` 封装与单元测试

**Files:**
- Create: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/governance/src/main/java/com/shilapi/governance/carlife/CarLifeGovernor.kt`
- Create: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/governance/src/test/java/com/shilapi/governance/carlife/CarLifeGovernorTest.kt`

**Interfaces:**
- Consumes: `com.shilapi.governance.core.CommandQueue`, `com.shilapi.governance.platform.E01PrivilegedBridge`
- Produces: 
  - `class CarLifeGovernor(context: Context, queue: CommandQueue, bridge: E01PrivilegedBridge)`
  - `fun isInstalled(): Boolean`
  - `fun isSuppressed(): Boolean`
  - `suspend fun applySuppression(): Boolean`
  - `suspend fun applyRestoration(): Boolean`

- [ ] **Step 1: 编写失败测试 `CarLifeGovernorTest.kt`**

```kotlin
package com.shilapi.governance.carlife

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.shilapi.governance.platform.E01PrivilegedBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CarLifeGovernorTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun testCommandByteBudgetsAreStrictlyWithin220Bytes() {
        val components = CarLifeGovernor.COMPONENTS_TO_SUPPRESS
        for (comp in components) {
            val disableCmd = "pm disable $comp"
            val wrappedDisable = E01PrivilegedBridge.wrap(disableCmd)
            assertTrue(
                "Disable command for $comp exceeds 220 bytes: ${wrappedDisable.toByteArray().size}",
                wrappedDisable.toByteArray(Charsets.UTF_8).size <= E01PrivilegedBridge.MAX_COMMAND_BYTES
            )

            val enableCmd = "pm enable $comp"
            val wrappedEnable = E01PrivilegedBridge.wrap(enableCmd)
            assertTrue(
                "Enable command for $comp exceeds 220 bytes: ${wrappedEnable.toByteArray().size}",
                wrappedEnable.toByteArray(Charsets.UTF_8).size <= E01PrivilegedBridge.MAX_COMMAND_BYTES
            )
        }

        val forceStopCmd = "am force-stop ${CarLifeGovernor.PACKAGE_NAME}"
        val wrappedStop = E01PrivilegedBridge.wrap(forceStopCmd)
        assertTrue(
            "Force stop command exceeds 220 bytes",
            wrappedStop.toByteArray(Charsets.UTF_8).size <= E01PrivilegedBridge.MAX_COMMAND_BYTES
        )
    }

    @Test
    fun testDetectInstalledReturnsFalseWhenNotInstalled() {
        val governor = CarLifeGovernor(context)
        assertFalse(governor.isInstalled())
        assertFalse(governor.isSuppressed())
    }
}
```

- [ ] **Step 2: 运行测试以验证失败**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :governance:testDebugUnitTest --tests com.shilapi.governance.carlife.CarLifeGovernorTest`
Expected: FAIL (CarLifeGovernor 未定义)

- [ ] **Step 3: 编写 `CarLifeGovernor.kt` 最小实现**

```kotlin
package com.shilapi.governance.carlife

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.shilapi.governance.VehicleGovernance
import com.shilapi.governance.core.CommandQueue
import com.shilapi.governance.platform.E01PrivilegedBridge
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Governor for managing Baidu CarLife vehicle package and preventing USB/BT hijacking.
 */
class CarLifeGovernor(
    private val context: Context,
    private val queue: CommandQueue = CommandQueue(),
) {
    companion object {
        private const val TAG = "CarLifeGovernor"
        const val PACKAGE_NAME = "com.baidu.carlifevehicle"

        const val RECEIVER_BROADCAST = "com.baidu.carlifevehicle.broadcast.BroadcastActionReceiver"
        const val RECEIVER_AOA = "com.baidu.carlifevehicle.connect.AOAAccessoryReceiver"
        const val SERVICE_CONNECT = "com.baidu.carlifevehicle.connect.ConnectService"

        val COMPONENTS_TO_SUPPRESS = listOf(
            "$PACKAGE_NAME/$RECEIVER_BROADCAST",
            "$PACKAGE_NAME/$RECEIVER_AOA",
            "$PACKAGE_NAME/$SERVICE_CONNECT"
        )
    }

    fun isInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Reads live component state directly from Android's packages.xml via PackageManager.
     * Zero-shell IPC overhead (<0.1ms).
     */
    fun isSuppressed(): Boolean {
        if (!isInstalled()) return false
        val pm = context.packageManager
        val comp = ComponentName(PACKAGE_NAME, RECEIVER_BROADCAST)
        return try {
            val state = pm.getComponentEnabledSetting(comp)
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun applySuppression(timeoutMs: Long = 3000L): Boolean = queue.execute {
        if (!isInstalled()) {
            VehicleGovernance.log(TAG, "CarLife is not installed, skipping suppression")
            return@execute true
        }

        withTimeoutOrNull(timeoutMs) {
            VehicleGovernance.log(TAG, "Suppressing CarLife components...")
            var allSucceeded = true

            // 1. Disable each component atomically
            for (comp in COMPONENTS_TO_SUPPRESS) {
                val cmd = "pm disable $comp"
                val res = E01PrivilegedBridge.execute(cmd)
                if (res !is E01PrivilegedBridge.Result.Success) {
                    VehicleGovernance.log(TAG, "Warning: disable $comp failed: $res")
                    // Do not abort immediately: attempt remaining components
                }
            }

            // 2. Force-stop to release already claimed USB & audio resources
            val stopCmd = "am force-stop $PACKAGE_NAME"
            val stopRes = E01PrivilegedBridge.execute(stopCmd)
            if (stopRes !is E01PrivilegedBridge.Result.Success) {
                VehicleGovernance.log(TAG, "Warning: force-stop failed: $stopRes")
            }

            // 3. Verify actual state via native PackageManager
            val verified = isSuppressed()
            VehicleGovernance.log(TAG, "CarLife suppression completed, verified=$verified")
            verified
        } ?: run {
            VehicleGovernance.log(TAG, "CarLife suppression timed out after ${timeoutMs}ms")
            false
        }
    }

    suspend fun applyRestoration(timeoutMs: Long = 3000L): Boolean = queue.execute {
        if (!isInstalled()) return@execute true

        withTimeoutOrNull(timeoutMs) {
            VehicleGovernance.log(TAG, "Restoring CarLife components...")
            for (comp in COMPONENTS_TO_SUPPRESS) {
                val cmd = "pm enable $comp"
                val res = E01PrivilegedBridge.execute(cmd)
                if (res !is E01PrivilegedBridge.Result.Success) {
                    VehicleGovernance.log(TAG, "Warning: enable $comp failed: $res")
                }
            }
            val restored = !isSuppressed()
            VehicleGovernance.log(TAG, "CarLife restoration completed, restored=$restored")
            restored
        } ?: run {
            VehicleGovernance.log(TAG, "CarLife restoration timed out after ${timeoutMs}ms")
            false
        }
    }
}
```

- [ ] **Step 4: 运行测试以验证通过**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :governance:testDebugUnitTest --tests com.shilapi.governance.carlife.CarLifeGovernorTest`
Expected: PASS

- [ ] **Step 5: 提交更改**

```bash
git add governance/src/main/java/com/shilapi/governance/carlife/CarLifeGovernor.kt
git add governance/src/test/java/com/shilapi/governance/carlife/CarLifeGovernorTest.kt
git commit -m "feat(governance): add CarLifeGovernor with defensive atomic commands"
```

---

### Task 2: 门面 `VehicleGovernance` 与持久化存储对接

**Files:**
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/governance/src/main/java/com/shilapi/governance/store/GovernancePersistence.kt`
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/governance/src/main/java/com/shilapi/governance/VehicleGovernance.kt`
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/governance/src/test/java/com/shilapi/governance/VehicleGovernanceTest.kt`

**Interfaces:**
- Consumes: `CarLifeGovernor`
- Produces:
  - `GovernancePersistence.isCarLifeSuppressionConfigured(): Boolean`
  - `GovernancePersistence.setCarLifeSuppressionConfigured(enabled: Boolean)`
  - `VehicleGovernance.isCarLifeInstalled(): Boolean`
  - `VehicleGovernance.isCarLifeSuppressed(): Boolean`
  - `VehicleGovernance.setCarLifeSuppressionEnabled(enabled: Boolean, onComplete: ((Boolean) -> Unit)?)`

- [ ] **Step 1: 编写持久化与门面测试用例**

在 `governance/src/test/java/com/shilapi/governance/VehicleGovernanceTest.kt` 中添加：
```kotlin
    @Test
    fun testCarLifeGovernancePersistenceAndFacade() {
        val app = RuntimeEnvironment.getApplication()
        VehicleGovernance.install(app, isExperimentalAllowed = true)

        assertFalse(VehicleGovernance.isCarLifeInstalled())
        assertFalse(VehicleGovernance.isCarLifeSuppressed())
    }
```

- [ ] **Step 2: 在 `GovernancePersistence.kt` 中增加 CarLife 持久化键**

```kotlin
    companion object {
        private const val PREFS_NAME = "vehicle_governance_prefs"
        private const val KEY_SUPPRESSION_CONFIGURED = "suppression_configured"
        private const val KEY_CARLIFE_SUPPRESSION_CONFIGURED = "carlife_suppression_configured"
    }

    fun isCarLifeSuppressionConfigured(): Boolean {
        return prefs.getBoolean(KEY_CARLIFE_SUPPRESSION_CONFIGURED, false)
    }

    fun setCarLifeSuppressionConfigured(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CARLIFE_SUPPRESSION_CONFIGURED, enabled).apply()
    }
```

- [ ] **Step 3: 在 `VehicleGovernance.kt` 中接入 `CarLifeGovernor` 并实现自愈检查**

```kotlin
    @Volatile
    private var carLifeGovernor: CarLifeGovernor? = null

    // 在 install() 方法中初始化:
    carLifeGovernor = CarLifeGovernor(application)

    // 在 startup scope.launch 检查中注入自愈:
    if (store.isCarLifeSuppressionConfigured()) {
        carLifeGovernor?.applySuppression()
    }

    fun isCarLifeInstalled(): Boolean = carLifeGovernor?.isInstalled() ?: false
    fun isCarLifeSuppressed(): Boolean = carLifeGovernor?.isSuppressed() ?: false

    fun setCarLifeSuppressionEnabled(enabled: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        persistence?.setCarLifeSuppressionConfigured(enabled)
        val gov = carLifeGovernor ?: run {
            onComplete?.invoke(false)
            return
        }
        scope.launch {
            val success = if (enabled) {
                gov.applySuppression()
            } else {
                gov.applyRestoration()
            }
            onComplete?.invoke(success)
        }
    }
```

- [ ] **Step 4: 运行治理测试验证**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :governance:testDebugUnitTest`
Expected: ALL PASS

- [ ] **Step 5: 提交更改**

```bash
git add governance/src/main/java/com/shilapi/governance/store/GovernancePersistence.kt
git add governance/src/main/java/com/shilapi/governance/VehicleGovernance.kt
git add governance/src/test/java/com/shilapi/governance/VehicleGovernanceTest.kt
git commit -m "feat(governance): expose CarLife governance in VehicleGovernance facade with persistence"
```

---

### Task 3: 车机 UI 资源与双按钮卡片接入

**Files:**
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/common/src/main/res/values/strings.xml`
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/common/src/main/res/values-zh-rCN/strings.xml`
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`

**Interfaces:**
- Consumes: `VehicleGovernance.isCarLifeInstalled()`, `VehicleGovernance.isCarLifeSuppressed()`, `VehicleGovernance.setCarLifeSuppressionEnabled()`
- Produces: `DiPlayActivity.carLifeGovernanceCard(columns: CardColumns)`

- [ ] **Step 1: 在 `strings.xml` 与 `values-zh-rCN/strings.xml` 中添加多语言字符串**

`strings.xml`:
```xml
    <string name="carlife_governance_title">Baidu CarLife Governance</string>
    <string name="carlife_not_installed_desc">Baidu CarLife is not detected on this head unit. USB and Bluetooth channels have no hijacking risk.</string>
    <string name="carlife_installed_desc">Baidu CarLife is installed. By default, attaching USB or connecting Bluetooth awakens CarLife and hijacks the USB connection, causing CarPlay handshake failures.</string>
    <string name="carlife_status_normal">Normal Autostart (USB Hijack Risk)</string>
    <string name="carlife_status_suppressed">Autostart Disabled (Anti-Hijack Active)</string>
    <string name="carlife_btn_disable_autostart">Disable CarLife Autostart</string>
    <string name="carlife_btn_restore_autostart">Restore CarLife Autostart</string>
    <string name="carlife_confirm_disable_title">Disable CarLife Autostart?</string>
    <string name="carlife_confirm_disable_body">This will disable CarLife\'s USB attachment receiver and background service to prevent it from preempting CarPlay. The app icon remains on your launcher for manual use.</string>
    <string name="carlife_confirm_restore_title">Restore CarLife Autostart?</string>
    <string name="carlife_confirm_restore_body">This will restore CarLife\'s background components to default state.</string>
```

`values-zh-rCN/strings.xml`:
```xml
    <string name="carlife_governance_title">百度 CarLife 治理</string>
    <string name="carlife_not_installed_desc">车机未检测到百度 CarLife (com.baidu.carlifevehicle)，USB 与蓝牙通道无被抢占风险。</string>
    <string name="carlife_installed_desc">车机已安装百度 CarLife。默认在插入 USB 数据线或连接蓝牙时自动唤醒拉起，抢占系统 USB 节点导致 CarPlay 无法正常握手。</string>
    <string name="carlife_status_normal">正常自启 (可能抢USB)</string>
    <string name="carlife_status_suppressed">已禁用自启 (防抢占生效)</string>
    <string name="carlife_btn_disable_autostart">禁用自启动 (防抢USB)</string>
    <string name="carlife_btn_restore_autostart">恢复 CarLife 自启动</string>
    <string name="carlife_confirm_disable_title">确认禁用 CarLife 自启动？</string>
    <string name="carlife_confirm_disable_body">将通过系统特权禁用 CarLife 的 USB 插入广播监听器与后台连接服务，彻底杜绝其在插线或连蓝牙时抢占通道。车机桌面图标仍会保留，您需要时依然可以手动点击打开。</string>
    <string name="carlife_confirm_restore_title">确认恢复 CarLife 自启动？</string>
    <string name="carlife_confirm_restore_body">将恢复 CarLife 的后台自启组件至原厂默认状态。</string>
```

- [ ] **Step 2: 在 `DiPlayActivity.kt` 中实现 `carLifeGovernanceCard`**

在 `vehicleGovernanceCards` 方法中调用 `carLifeGovernanceCard(columns)`：
```kotlin
    private fun vehicleGovernanceCards(columns: CardColumns) {
        carLifeGovernanceCard(columns)
        voiceGovernanceCard(columns)
        diagnosticsCard(columns)
    }

    private fun carLifeGovernanceCard(columns: CardColumns) {
        columns.card(R.string.carlife_governance_title, R.drawable.ic_dp_permissions) { card ->
            val isInstalled = VehicleGovernance.isCarLifeInstalled()
            val isSuppressed = VehicleGovernance.isCarLifeSuppressed()

            val statusText = when {
                !isInstalled -> ""
                isSuppressed -> getString(R.string.carlife_status_suppressed)
                else -> getString(R.string.carlife_status_normal)
            }
            val descText = if (isInstalled) {
                getString(R.string.carlife_installed_desc) + "\n\n" + statusText
            } else {
                getString(R.string.carlife_not_installed_desc)
            }

            card.addView(label(descText, 14, MUTED).apply { setPadding(0, dp(4), 0, dp(12)) })

            if (isInstalled) {
                val btnRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                }

                // 禁用自启按钮
                val disableBtn = Button(this).apply {
                    text = getString(R.string.carlife_btn_disable_autostart)
                    isAllCaps = false
                    textSize = 13f
                    isEnabled = !isSuppressed
                    setTextColor(if (!isSuppressed) palette.btnMainFg else palette.textMuted)
                    background = GradientDrawable().apply {
                        setColor(if (!isSuppressed) palette.btnMainBg else palette.cardInner)
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), palette.cardBorder)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
                    setOnClickListener {
                        AlertDialog.Builder(this@DiPlayActivity)
                            .setTitle(getString(R.string.carlife_confirm_disable_title))
                            .setMessage(getString(R.string.carlife_confirm_disable_body))
                            .setNegativeButton(getString(R.string.cancel), null)
                            .setPositiveButton(getString(R.string.maintenance_continue)) { _, _ ->
                                isEnabled = false
                                VehicleGovernance.setCarLifeSuppressionEnabled(true) {
                                    runOnUiThread { render() }
                                }
                            }
                            .show()
                    }
                }

                // 恢复自启按钮
                val restoreBtn = Button(this).apply {
                    text = getString(R.string.carlife_btn_restore_autostart)
                    isAllCaps = false
                    textSize = 13f
                    isEnabled = isSuppressed
                    setTextColor(if (isSuppressed) palette.btnMainFg else palette.textMuted)
                    background = GradientDrawable().apply {
                        setColor(if (isSuppressed) palette.btnMainBg else palette.cardInner)
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), palette.cardBorder)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
                    setOnClickListener {
                        AlertDialog.Builder(this@DiPlayActivity)
                            .setTitle(getString(R.string.carlife_confirm_restore_title))
                            .setMessage(getString(R.string.carlife_confirm_restore_body))
                            .setNegativeButton(getString(R.string.cancel), null)
                            .setPositiveButton(getString(R.string.maintenance_continue)) { _, _ ->
                                isEnabled = false
                                VehicleGovernance.setCarLifeSuppressionEnabled(false) {
                                    runOnUiThread { render() }
                                }
                            }
                            .show()
                    }
                }

                btnRow.addView(disableBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
                btnRow.addView(restoreBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
                card.addView(btnRow)
            }
        }
    }
```

- [ ] **Step 3: 运行全量单元测试与 UI 测试**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest`
Expected: PASS

- [ ] **Step 4: 提交更改**

```bash
git add common/src/main/res/values/strings.xml
git add common/src/main/res/values-zh-rCN/strings.xml
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt
git commit -m "feat(ui): add Baidu CarLife dual-button governance card to vehicle settings"
```

---

### Task 4: 端到端完整编译与回归验证

**Files:**
- Repository root

- [ ] **Step 1: 运行所有相关模块的单元测试**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew testDebugUnitTest`
Expected: 编译通过，所有既有与新增测试均通过 (BUILD SUCCESSFUL)。

- [ ] **Step 2: 验证编译 APK 构建**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew assembleDebug`
Expected: 成功构建 `automotive-debug.apk` 和 `mobile-debug.apk`。

- [ ] **Step 3: 最终 Review 与 Git Log 确认**

Run: `git status && git log -n 4 --oneline`
Expected: 工作区干净，包含清晰的 3 次原子提交。
