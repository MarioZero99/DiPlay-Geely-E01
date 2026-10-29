# 百度 CarLife 监听链路精简与守卫状态检测优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 优化百度 CarLife 治理与监听链路，消除探测阶段跨进程 Binder 异常与冗余 IPC 开销，修复无漏洞组件版本下的假阳性死循环，引入轻量级运行时进程存活守卫（Runtime Process Guard），并在 UI 层实现动态精准反馈。

**Architecture:** 
1. 核心治理层：在 `CarLifeGovernor` 中重构组件存在性探测机制，实行 Receiver 与 Service 精准分流查询，消除由 `getReceiverInfo` 抛出 `NameNotFoundException` 带来的 IPC 异常性能损耗；
2. 守卫决策层：重构 `isSuppressed()` 判定语义，当已安装但未包含目标监听组件时判定为天然免疫（安全），终结死循环漏洞；引入纯 Framework 层无 IPC 压力的 `isProcessRunning()` 运行态守卫；
3. Facade 与 UI 表现层：在 `VehicleGovernance` 与 `DiPlayActivity` 中打通运行态感知，直观呈现“插线自启已拦截 / 后台进程运行中”细分状态，提供更严密且流畅的守卫体验。

**Tech Stack:** Kotlin 1.9+, Android SDK API 22 (Android 5.1 Lollipop MR1), Android Automotive, JUnit 4, Robolectric.

**Spec:** User discussion on 2026-10-29: 审查 CarLife 监听链路是否存在优化点、更精简的代码实现、更好的守卫与状态检测。

## Global Constraints

- **Platform Target:** Android 5.1 (API 22) MTK6735 / Geely E01 & Generic Android Head Units.
- **Command Budget:** 单条包装后特权命令严格 ≤ 220 字节（`MAX_COMMAND_BYTES = 220`）。
- **Performance Budget:** 状态检测避免主线程产生无谓的跨进程异常（禁止通过 try-catch `NameNotFoundException` 来猜测组件类型）。
- **Execution Safety:** 特权执行保持在 `Dispatchers.IO` / `CommandQueue` 串行执行；主线程仅做轻量安全探测。
- **UI Integrity:** 保持现有双按钮与卡片交互规范，字符串统一抽取至 `strings.xml`。

---

### Task 1: 重构 `CarLifeGovernor` 探测机制与异常消除

**Files:**
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/governance/src/main/java/com/shilapi/governance/carlife/CarLifeGovernor.kt`
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/governance/src/test/java/com/shilapi/governance/carlife/CarLifeGovernorTest.kt`

**Interfaces:**
- Produces:
  - `data class ComponentSpec(val className: String, val isService: Boolean)`
  - `CarLifeGovernor.TARGET_COMPONENTS: List<ComponentSpec>`
  - `fun isSuppressed(): Boolean`（修复空组件死循环，精准分流）
  - `fun isProcessRunning(): Boolean`（运行时进程存活检测）

- [ ] **Step 1: 编写新增测试用例验证精准查询、空组件免疫与进程存活**

在 `CarLifeGovernorTest.kt` 中添加针对组件类型精准分流（不应跨界调用抛异常）、空组件判定以及进程存活状态的测试用例：

```kotlin
    @Test
    fun preciseComponentLookupAvoidsMisdirectedServiceQueries() {
        installCarLife()
        val queryLog = mutableListOf<Pair<String, Boolean>>() // Pair<className, isService>
        val governor = CarLifeGovernor(
            context = context,
            declared = { name, flags, isService ->
                queryLog += (name.className to isService)
                true
            },
        )
        governor.isSuppressed()

        val serviceQueries = queryLog.filter { it.second }
        val receiverQueries = queryLog.filter { !it.second }

        assertEquals(1, serviceQueries.size)
        assertEquals("com.baidu.carlifevehicle.connect.ConnectService", serviceQueries.first().first)
        assertEquals(2, receiverQueries.size)
    }

    @Test
    fun buildWithNoVulnerableComponentsReportsSuppressedRatherThanDeadlocking() {
        // CarLife is installed, but carries none of the 3 vulnerable components (e.g., custom stripped build)
        installCarLife(present = emptyList())
        val governor = CarLifeGovernor(
            context = context,
            declared = { _, _, _ -> false },
        )
        assertTrue("CarLife with no vulnerable entrypoints is inherently safe", governor.isSuppressed())
    }

    @Test
    fun processRunningDetectionIdentifiesActiveCarLifeProcess() {
        var running = false
        val governor = CarLifeGovernor(
            context = context,
            processRunningProbe = { running },
        )
        assertFalse(governor.isProcessRunning())
        running = true
        assertTrue(governor.isProcessRunning())
    }
```

- [ ] **Step 2: 运行测试验证编译失败**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :governance:testDebugUnitTest --tests com.shilapi.governance.carlife.CarLifeGovernorTest`
Expected: FAIL（`declared` 参数不匹配或新方法未定义）

- [ ] **Step 3: 实现 `CarLifeGovernor.kt` 精准分流与守卫逻辑**

在 `CarLifeGovernor.kt` 中：
1. 声明 `data class ComponentSpec(val className: String, val isService: Boolean = false)`；
2. 定义 `TARGET_COMPONENTS`，明确指定 Receiver 与 Service；
3. `declared` 签名升级为 `(ComponentName, Int, Boolean) -> Boolean`，生产默认实现根据 `isService` 精准调用 `getServiceInfo` 或 `getReceiverInfo`；
4. `isSuppressed()` 逻辑修正：若已安装且 `declaredComponents.isEmpty()`，说明无风险监听组件，直接返回 `true`（天然免疫）；
5. 新增 `processRunningProbe` 注入并默认实现 `isProcessRunning()`，通过 `ActivityManager.getRunningAppProcesses()` 无阻塞扫描当前运行进程。

```kotlin
data class ComponentSpec(val className: String, val isService: Boolean = false)

class CarLifeGovernor(
    private val context: Context,
    private val runCommand: (String) -> E01PrivilegedBridge.Result = E01PrivilegedBridge::execute,
    private val declared: (ComponentName, Int, Boolean) -> Boolean = { name, flags, isService ->
        try {
            if (isService) {
                context.packageManager.getServiceInfo(name, flags)
            } else {
                context.packageManager.getReceiverInfo(name, flags)
            }
            true
        } catch (_: Throwable) {
            false
        }
    },
    private val processRunningProbe: () -> Boolean = {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.runningAppProcesses?.any {
                it.processName == PACKAGE_NAME || it.processName.startsWith("$PACKAGE_NAME:")
            } ?: false
        } catch (_: Throwable) {
            false
        }
    },
    private val queue: CommandQueue = CommandQueue(),
) {
    companion object {
        private const val TAG = "CarLifeGovernor"
        private const val DEFAULT_TIMEOUT_MS = 3000L

        const val PACKAGE_NAME = "com.baidu.carlifevehicle"

        const val RECEIVER_BROADCAST = "$PACKAGE_NAME.broadcast.BroadcastActionReceiver"
        const val RECEIVER_AOA = "$PACKAGE_NAME.connect.AOAAccessoryReceiver"
        const val SERVICE_CONNECT = "$PACKAGE_NAME.connect.ConnectService"

        val TARGET_COMPONENTS = listOf(
            ComponentSpec(RECEIVER_BROADCAST, isService = false),
            ComponentSpec(RECEIVER_AOA, isService = false),
            ComponentSpec(SERVICE_CONNECT, isService = true),
        )

        val COMPONENTS = TARGET_COMPONENTS.map { it.className }

        const val FORCE_STOP_COMMAND = "am force-stop $PACKAGE_NAME"

        fun qualified(className: String) = "$PACKAGE_NAME/$className"
        fun disableCommand(className: String) = "pm disable ${qualified(className)}"
        fun enableCommand(className: String) = "pm enable ${qualified(className)}"
    }

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun isProcessRunning(): Boolean = processRunningProbe()

    fun isSuppressed(): Boolean {
        if (!isInstalled()) return false

        val declaredList = TARGET_COMPONENTS.filter { target ->
            val name = ComponentName(PACKAGE_NAME, target.className)
            declared(name, PackageManager.GET_DISABLED_COMPONENTS, target.isService)
        }
        if (declaredList.isEmpty()) {
            return true
        }

        return declaredList.all { target ->
            val name = ComponentName(PACKAGE_NAME, target.className)
            isOff(try {
                context.packageManager.getComponentEnabledSetting(name)
            } catch (_: Throwable) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            })
        }
    }

    private fun isOff(setting: Int) =
        setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            setting == 4 // COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED

    // applySuppression, applyRestoration, eachCarried 保持不变，并使用 TARGET_COMPONENTS 遍历
    ...
```

- [ ] **Step 4: 适配现有单元测试并运行验证**

更新 `CarLifeGovernorTest.kt` 中旧的 `declared` mock 调用签名，执行测试：
Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :governance:testDebugUnitTest`
Expected: 全部测试通过（包含原有 10 个用例与新增 3 个用例，共 13 个用例全部 PASS）。

- [ ] **Step 5: 提交更改**

```bash
git add governance/src/main/java/com/shilapi/governance/carlife/CarLifeGovernor.kt \
        governance/src/test/java/com/shilapi/governance/carlife/CarLifeGovernorTest.kt
git commit -m "perf(governance): eliminate IPC exceptions in CarLife detection and fix empty components loop"
```

---

### Task 2: 扩展 `VehicleGovernance` 外观接口

**Files:**
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/governance/src/main/java/com/shilapi/governance/VehicleGovernance.kt:180-190`

**Interfaces:**
- Produces:
  - `fun isCarLifeProcessRunning(): Boolean`

- [ ] **Step 1: 在 `VehicleGovernance` 中增加进程存活查询接口**

```kotlin
    /**
     * Whether Baidu CarLife's process is currently alive in memory.
     *
     * In-memory process probe via ActivityManager: zero IPC cost, non-blocking.
     */
    fun isCarLifeProcessRunning(): Boolean = carLife?.isProcessRunning() ?: false
```

- [ ] **Step 2: 验证编译并运行 `:governance` 测试**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :governance:testDebugUnitTest`
Expected: PASS

- [ ] **Step 3: 提交更改**

```bash
git add governance/src/main/java/com/shilapi/governance/VehicleGovernance.kt
git commit -m "feat(governance): expose isCarLifeProcessRunning in VehicleGovernance facade"
```

---

### Task 3: UI 表现层状态反馈优化与轻量缓存

**Files:**
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:174, 1040-1126`
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/common/src/main/res/values-zh-rCN/strings.xml`
- Modify: `/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/common/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes:
  - `VehicleGovernance.isCarLifeInstalled(): Boolean`
  - `VehicleGovernance.isCarLifeSuppressed(): Boolean`
  - `VehicleGovernance.isCarLifeProcessRunning(): Boolean`
- Produces:
  - `data class CarLifeUiState(val installed: Boolean, val suppressed: Boolean, val running: Boolean)`

- [ ] **Step 1: 在 `strings.xml` 中增加进程运行中文案**

在 `values-zh-rCN/strings.xml`：
```xml
    <string name="carlife_status_suppressed_running">开机拦截已生效 · 后台运行中</string>
    <string name="carlife_installed_suppressed_running_desc">CarLife 开机与插线自启已被拦截，但当前进程仍在运行（可能来自手动打开）。若遇音频或连接冲突，可点击【禁用】强行停止进程。</string>
```

在 `values/strings.xml`：
```xml
    <string name="carlife_status_suppressed_running">Interception Active · Running in Background</string>
    <string name="carlife_installed_suppressed_running_desc">Auto-start intercepted, but CarLife process is running in background. Tap Disable to force stop if conflicts occur.</string>
```

- [ ] **Step 2: 升级 `DiPlayActivity` 中的状态读取与卡片渲染**

将 `cachedCarLifeState: Pair<Boolean, Boolean>?` 升级为三元状态 `CarLifeUiState`:
```kotlin
    data class CarLifeUiState(val installed: Boolean, val suppressed: Boolean, val running: Boolean)
    private var cachedCarLifeState: CarLifeUiState? = null

    private fun carLifeState(): CarLifeUiState = cachedCarLifeState ?: CarLifeUiState(
        installed = VehicleGovernance.isCarLifeInstalled(),
        suppressed = VehicleGovernance.isCarLifeSuppressed(),
        running = VehicleGovernance.isCarLifeProcessRunning(),
    ).also { cachedCarLifeState = it }
```

在 `carLifeGovernanceCard` 中呈现细化状态：
- 若 `!installed`：展示未安装状态；
- 若 `suppressed && !running`：展示 `carlife_status_suppressed` (ACCENT 绿标) 与 `carlife_installed_suppressed_desc`；
- 若 `suppressed && running`：展示 `carlife_status_suppressed_running` (WARNING 橙黄标) 与 `carlife_installed_suppressed_running_desc`；
- 若 `!suppressed`：展示 `carlife_status_active` (WARNING 橙黄标) 与 `carlife_installed_active_desc`。

禁用按钮的高亮逻辑：当处于活跃状态或处于 `running` 状态时，【禁用/停止】按钮均作为 primary 建议操作高亮显示。

- [ ] **Step 3: 运行完整模块编译与单测**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest :governance:testDebugUnitTest`
Expected: PASS

- [ ] **Step 4: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt \
        common/src/main/res/values/strings.xml \
        common/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(ui): refine CarLife governance card with runtime process status detection"
```

---

### Task 4: 端到端与整机回归验证

**Files:**
- Verification only

- [ ] **Step 1: 运行所有工程单元测试**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew testDebugUnitTest`
Expected: 所有的 Unit Tests 全部通过（0 failure）。

- [ ] **Step 2: 执行全量 Debug APK 编译验证构建产物**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL，生成正常 apk 产物。

- [ ] **Step 3: 最终 Git 检查与分支状态确认**

Run: `git status && git log -n 4 --oneline`
Expected: Working tree clean，4 个专注的 commits 清晰记录改进。
