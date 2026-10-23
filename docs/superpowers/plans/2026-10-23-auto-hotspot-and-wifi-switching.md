# 统一网络环境对齐与自动热点/Wi-Fi双向联动 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 
在吉利星瑞 E01（Android 5.1 / MT6735 单射频芯片）车机上，建立统一且绝对闭环的无线网络环境对齐器（Wireless Environment Reconciler）：
1. **热点模式（`MANUAL`，且开启自动热点）**：目标为 `[Station=OFF, Hotspot=ON]`。必须彻底关闭 Station 模式（常规 API 失败则 root `svc wifi disable` 兜底），等待确认硬件释放后再开启热点（常规反射失败则 root `PrivilegedHotspotTool enable` 兜底），直到热点确认广播就绪。
2. **同网 Wi-Fi 模式（`EXISTING_WIFI`）**：目标为 `[Hotspot=OFF, Station=ON]`。必须彻底关闭车机热点（常规反射失败则 root `PrivilegedHotspotTool disable` 兜底），等待确认热点彻底关闭后再开启 Wi-Fi Station（常规 API 失败则 root `svc wifi enable` 兜底），直到 Wi-Fi 客户端确认处于开启。
3. **统一触发与防踩踏调度**：将启动（`onCreate`）、模式切换（`applyWirelessLink`）、前台恢复（`onResume`）、设置开关即时点击、点击“连接”统一收拢至代数驱动（`generation`）的单线程调度器中，杜绝并发竞态冲突与死循环闪烁。

**Architecture:** 
1. **四大射频动作 100% 双层闭环（Native API + Root 提权）**：
   - 关 Station：`wifi.isWifiEnabled = false` -> 超时未关 -> Root 执行 `/system/bin/svc wifi disable` -> 轮询等 `DISABLED`。
   - 开 Station：`wifi.isWifiEnabled = true` -> 超时未开 -> Root 执行 `/system/bin/svc wifi enable` -> 轮询等 `ENABLED`。
   - 关 Hotspot：反射 `setWifiApEnabled(null, false)` -> 失败/超时 -> Root 执行 `PrivilegedHotspotTool disable` -> 轮询等 `DISABLED`。
   - 开 Hotspot：反射 `setWifiApEnabled(null, true)` -> 失败/超时 -> Root 执行 `PrivilegedHotspotTool enable` -> 轮询等 `ENABLED`。
2. **通用独立 RootShellBridge**：
   在 `shared` 模块直接内建基于东软 `IExtraUtilsService` (uid 0) 的 `RootShellBridge`，零外部依赖，编译无需依赖 `diplay-vendor-hop`，保证 `./gradlew testDebugUnitTest` 在任何环境下 100% 绿灯，在非车机平台（如手机或 AVD）优雅降级为 null。
3. **统一网络状态对齐器（`syncWirelessEnvironment`）**：
   采用代数锁机制（`AtomicInteger generation`）。任何新触发事件递增 `generation`，如果旧任务正在跑，其在每个阻塞等待前后都会检测 `taskGeneration == currentGeneration`，若不相等立即主动终止，保证最新意图绝对权威生效。
4. **失败冷却机制（Backoff）**：
   如果单次对齐发生异常或超时，记录时间戳，施加 15 秒冷却保护，冷却期内不执行破坏性重试，杜绝车机 Wi-Fi 高频闪烁。

**Tech Stack:** Kotlin 1.9, Android 5.1 (API 22, minSdk 19), WifiManager (Hidden API), Android Binder (`IExtraUtilsService`), `app_process`, JUnit4, Robolectric.

---

## Global Constraints

- **严控超时时延（最多 2 秒）**：车机正常射频切换通常在 200ms~800ms 内完成。每个动作（关 Station、开 Station、关 AP、开 AP）的第一阶段 Native 轮询最多给 1000ms，一旦未完成立即触发 Root 提权兜底，整个动作总超时严格卡在 **最多 2000ms（2 秒）**，轮询步长 100ms，兼顾极速响应与可靠性。
- **最低 API 兼容**：严格遵循 minSdk 19 / 目标 API 22（Android 5.1 Lollipop），严禁引入 API 23+ 类或库方法（如 `getSystemService(Class)`、`Settings.System.canWrite()` 等）。
- **单射频互斥时序**：MT6735 不能同时维持 Station 模式与 SoftAP 模式。从 Station 切 AP 必须等 Station 真正关闭；从 AP 切 Station 必须等 AP 真正关闭。
- **UI 与主线程隔离**：所有 Wi-Fi/热点状态切换、等待轮询及 root 命令执行必须在后台线程（如 `car-hotspot-worker` 守护线程）中执行，完成后切回 `runOnUiThread` 刷新页面。
- **非侵入式降级**：所有 Root 提权调用必须支持优雅降级，当 `RootShellBridge` 不可用（例如在普通手机或 AVD 模拟器上）时，退回纯 AOSP/反射路径，严禁 crash。

---

## File Structure

```
DiPlay-Legacy-Android-Geely/
├── common/src/main/AndroidManifest.xml                              (Modify: 增加 WRITE_SETTINGS 权限)
├── shared/src/main/AndroidManifest.xml                              (Modify: 增加 WRITE_SETTINGS 权限)
├── shared/src/main/java/com/shilapi/xcertplay/
│   ├── system/
│   │   ├── RootShellBridge.kt                                       (Create: 基于 ExtraUtilsService 的通用 uid 0 执行器)
│   │   └── PrivilegedHotspotTool.kt                                 (Create: app_process root 执行的 AP 提权控制入口)
│   └── network/
│       └── CarHotspotController.kt                                  (Modify: 实现 4 象限双层闭环与状态轮询等待)
├── shared/src/test/java/com/shilapi/xcertplay/network/
│   ├── RootShellBridgeTest.kt                                       (Create: 测试命令包装、回包解析与容错)
│   └── CarHotspotControllerTest.kt                                  (Modify: 增加四大操作的状态流转与 root 兜底测试)
├── common/src/main/java/com/shilapi/xcertplay/
│   ├── CarHotspotAutoStart.kt                                       (Modify: 完善热点自启判定条件)
│   └── DiPlayActivity.kt                                            (Modify: 启动/切换/前台/开关统一接入 syncWirelessEnvironment)
└── common/src/test/java/com/shilapi/xcertplay/
    ├── CarHotspotAutoStartTest.kt                                   (Modify: 补充模式切换与自动启动测试)
    └── WirelessEnvironmentReconcilerTest.kt                         (Create: 验证统一对齐器的代数取消、防抖与状态对齐)
```

---

## Tasks

### Task 1: 补全 Manifest 权限、创建 `RootShellBridge` 与 `PrivilegedHotspotTool`

**Files:**
- Modify: `common/src/main/AndroidManifest.xml`
- Modify: `shared/src/main/AndroidManifest.xml`
- Create: `shared/src/main/java/com/shilapi/xcertplay/system/RootShellBridge.kt`
- Create: `shared/src/main/java/com/shilapi/xcertplay/system/PrivilegedHotspotTool.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/system/RootShellBridgeTest.kt`

**Interfaces:**
- Produces: 
  - `RootShellBridge.isAvailable(): Boolean`
  - `RootShellBridge.execute(command: String): Boolean`
  - `PrivilegedHotspotTool.main(args: Array<String>)`

- [ ] **Step 1: 在 Manifest 中添加 `WRITE_SETTINGS` 权限**
在 `common/src/main/AndroidManifest.xml` 和 `shared/src/main/AndroidManifest.xml` 的 `<manifest>` 下添加：
```xml
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
```

- [ ] **Step 2: 编写 `RootShellBridgeTest.kt`**
测试 `RootShellBridge` 的命令包装逻辑、退出码判定、特殊字符校验与空结果处理。

- [ ] **Step 3: 创建 `RootShellBridge.kt`**
在 `shared/src/main/java/com/shilapi/xcertplay/system/RootShellBridge.kt` 中实现：
```kotlin
package com.shilapi.xcertplay.system

import android.os.IBinder
import android.os.Parcel
import com.shilapi.xcertplay.DiagLog

object RootShellBridge {
    private const val TAG = "RootShellBridge"
    private const val SERVICE_NAME = "ExtraUtilsService"
    private const val DESCRIPTOR = "com.neusoft.alfus.os.IExtraUtilsService"
    private const val TRANSACTION_GET_MESSAGE = 6
    private const val EVENT_SHELL = 8
    private const val ACTION_EXEC = 0
    private const val RC_MARKER = "__RC:"
    private const val MAX_COMMAND_BYTES = 220

    fun isAvailable(): Boolean = findExtraUtilsService() != null

    internal fun wrap(command: String): String =
        "{ $command; } 2>&1; rc=\$?; echo $RC_MARKER\$rc"

    fun execute(command: String): Boolean {
        if (command.contains('\n') || command.contains('\r')) return false
        val wrapped = wrap(command)
        if (wrapped.toByteArray(Charsets.UTF_8).size > MAX_COMMAND_BYTES) {
            DiagLog.w(TAG, "Root command too long: ${wrapped.length} bytes")
            return false
        }
        val binder = findExtraUtilsService() ?: return false
        return try {
            val output = transact(binder, wrapped)
            val marker = output.lastIndexOf(RC_MARKER)
            if (marker < 0) return false
            val rc = output.substring(marker + RC_MARKER.length).trim().toIntOrNull()
            rc == 0
        } catch (error: Throwable) {
            DiagLog.w(TAG, "Root transaction failed", error)
            false
        }
    }

    private fun findExtraUtilsService(): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        getService.invoke(null, SERVICE_NAME) as? IBinder
    } catch (_: Throwable) {
        null
    }

    private fun transact(binder: IBinder, command: String): String {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeInt(EVENT_SHELL)
            request.writeInt(ACTION_EXEC)
            request.writeByteArray(command.toByteArray(Charsets.UTF_8))
            if (!binder.transact(TRANSACTION_GET_MESSAGE, request, reply, 0)) {
                return ""
            }
            reply.readException()
            val bytes = reply.createByteArray() ?: ByteArray(0)
            String(bytes, Charsets.UTF_8)
        } finally {
            reply.recycle()
            request.recycle()
        }
    }
}
```

- [ ] **Step 4: 创建 `PrivilegedHotspotTool.kt`**
在 `shared/src/main/java/com/shilapi/xcertplay/system/PrivilegedHotspotTool.kt`：
```kotlin
package com.shilapi.xcertplay.system

import android.os.IBinder

object PrivilegedHotspotTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val action = args.firstOrNull()
        val enable = action == "enable"
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val wifiBinder = getService.invoke(null, "wifi") as? IBinder
                ?: run { System.err.println("NO_WIFI_SERVICE"); System.exit(1); return }
            val stubClass = Class.forName("android.net.wifi.IWifiManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val wifiService = asInterface.invoke(null, wifiBinder)
            val setWifiApEnabled = wifiService.javaClass.methods.firstOrNull {
                it.name == "setWifiApEnabled" && it.parameterTypes.size == 2
            } ?: run { System.err.println("NO_SET_WIFI_AP"); System.exit(2); return }
            val result = setWifiApEnabled.invoke(wifiService, null, enable)
            println("RESULT:$result")
            System.exit(0)
        } catch (error: Throwable) {
            error.printStackTrace()
            System.exit(3)
        }
    }
}
```

- [ ] **Step 5: 运行单元测试**
```sh
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :shared:testDebugUnitTest
```

---

### Task 2: 增强 `CarHotspotController` 实现四大射频动作 100% 双层闭环

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/network/CarHotspotController.kt`
- Modify: `shared/src/test/java/com/shilapi/xcertplay/network/CarHotspotControllerTest.kt`

**Interfaces:**
- Produces:
  - `CarHotspotController.ensureEnabled(timeoutMillis: Long): HotspotStartResult`
  - `CarHotspotController.releaseForStationMode(timeoutMillis: Long = 2000L): String?`
  - `CarHotspotController.ensureStationEnabled(timeoutMillis: Long = 3000L): Boolean`
  - `CarHotspotController.ensureStationDisabled(timeoutMillis: Long = 3000L): Boolean`

- [ ] **Step 1: 在 `CarHotspotControllerTest.kt` 中增加闭环状态流转测试**
覆盖：
1. `ensureStationDisabled`：Native 关闭 -> 等待，超时未关时触发 Root `svc wifi disable` 测试；
2. `ensureStationEnabled`：Native 开启 -> 等待，超时未开时触发 Root `svc wifi enable` 测试；
3. `ensureEnabled`：关 Station 等待 -> 反射开 AP -> 失败触发 Root `PrivilegedHotspotTool enable` 流程；
4. `releaseForStationMode`：反射关 AP -> 失败触发 Root `PrivilegedHotspotTool disable` 流程。

- [ ] **Step 2: 修改 `CarHotspotController.kt`**
完善四大核心方法：
1. **`ensureStationDisabled(timeoutMillis: Long)`**：
   - 先调用 `wifi.isWifiEnabled = false`；
   - 轮询 `awaitStationDisabled(timeoutMillis / 2)`；
   - 若依然未关且 `RootShellBridge.isAvailable()`，执行 `RootShellBridge.execute("/system/bin/svc wifi disable")`；
   - 再次轮询等待至结束。
2. **`ensureStationEnabled(timeoutMillis: Long)`**：
   - 若已开直接返回 true；
   - 调用 `wifi.isWifiEnabled = true`；
   - 轮询 `awaitStationEnabled(timeoutMillis / 2)`；
   - 若依然未开且 `RootShellBridge.isAvailable()`，执行 `RootShellBridge.execute("/system/bin/svc wifi enable")`；
   - 再次轮询等待至结束。
3. **`ensureEnabled(timeoutMillis: Long)`**：
   - 若热点已开直接返回 `HotspotStartResult.READY`；
   - 先调用 `ensureStationDisabled(2500L)`，确保 Station 模式彻底释放；
   - 反射调用 `enableAccessPoint(setAp, wifi)`；
   - 若反射返回 false 或 null（权限被拒），且 `RootShellBridge.isAvailable()`：
     使用 `appContext.packageCodePath` 执行 root 提权：
     `RootShellBridge.execute("CLASSPATH=${appContext.packageCodePath} app_process /system/bin com.shilapi.xcertplay.system.PrivilegedHotspotTool enable")`；
   - 轮询等待 `awaitEnabled(timeoutMillis)`。
4. **`releaseForStationMode(timeoutMillis: Long)`**：
   - 若热点未开直接返回 null；
   - 先反射调用 `setAp.invoke(wifi, null, false)`；
   - 若失败且 `RootShellBridge.isAvailable()`：
     `RootShellBridge.execute("CLASSPATH=${appContext.packageCodePath} app_process /system/bin com.shilapi.xcertplay.system.PrivilegedHotspotTool disable")`；
   - 轮询等待 `awaitDisabled(timeoutMillis)`。

- [ ] **Step 3: 运行 `shared` 单元测试验证**
```sh
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :shared:testDebugUnitTest
```

---

### Task 3: 完善自启与模式判定 `CarHotspotAutoStart.kt`

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarHotspotAutoStart.kt`
- Modify: `common/src/test/java/com/shilapi/xcertplay/CarHotspotAutoStartTest.kt`

- [ ] **Step 1: 编写/更新 `CarHotspotAutoStartTest.kt`**
确保 `shouldWakeCarHotspotOnStartup` 和 `shouldReconcileHotspot(autoEnabled, mode)` 语义精确完备。

- [ ] **Step 2: 更新 `CarHotspotAutoStart.kt`**
补充判定函数，明确热点模式与同网模式各自的期望动作。

- [ ] **Step 3: 运行测试**
```sh
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest
```

---

### Task 4: 实现统一对齐器 `syncWirelessEnvironment` 并接入所有触发场景

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/WirelessEnvironmentReconcilerTest.kt`

**Interfaces:**
- Produces: `DiPlayActivity.syncWirelessEnvironment(reason: String)`

- [ ] **Step 1: 编写 `WirelessEnvironmentReconcilerTest.kt`**
验证：
1. 目标为 `MANUAL` 且开关打开时，执行 Station 关 -> Hotspot 开；
2. 目标为 `EXISTING_WIFI` 时，执行 Hotspot 关 -> Station 开；
3. 代数递增时，前一个代数的任务立即放弃退出；
4. 快速连点或多次触发时的防抖。

- [ ] **Step 2: 在 `DiPlayActivity.kt` 中实现 `syncWirelessEnvironment`**
1. 增加代数与冷却控制变量：
```kotlin
private val wirelessSyncGeneration = java.util.concurrent.atomic.AtomicInteger(0)
@Volatile private var lastSyncFailureAt = 0L
private const val SYNC_FAILURE_COOLDOWN_MILLIS = 15_000L
```
2. 实现统一方法：
```kotlin
private fun syncWirelessEnvironment(reason: String) {
    val mode = AirPlayPersistence.loadWirelessHotspotMode(this)
    val autoHotspot = AirPlayPersistence.loadAutoCarHotspot(this)
    val generation = wirelessSyncGeneration.incrementAndGet()
    val controller = CarHotspotController(applicationContext)

    Thread({
        DiagLog.i(TAG_CAR_HOTSPOT, "syncWirelessEnvironment begin reason=$reason mode=$mode autoHotspot=$autoHotspot gen=$generation")
        val now = SystemClock.elapsedRealtime()
        if (now - lastSyncFailureAt < SYNC_FAILURE_COOLDOWN_MILLIS) {
            DiagLog.w(TAG_CAR_HOTSPOT, "syncWirelessEnvironment skipped: cooling down after recent failure")
            return@Thread
        }

        if (mode == WirelessHotspotMode.MANUAL && autoHotspot) {
            // 目标：Hotspot=开, Station=关
            if (CarHotspotStatus.isEnabled(applicationContext) == true) {
                DiagLog.i(TAG_CAR_HOTSPOT, "hotspot already enabled, no-op")
                return@Thread
            }
            if (wirelessSyncGeneration.get() != generation) return@Thread
            val result = controller.ensureEnabled(CAR_HOTSPOT_AUTO_ENABLE_TIMEOUT_MILLIS)
            if (wirelessSyncGeneration.get() != generation) return@Thread
            if (result != HotspotStartResult.READY) {
                lastSyncFailureAt = SystemClock.elapsedRealtime()
                DiagLog.w(TAG_CAR_HOTSPOT, "failed to enable hotspot: ${result.reason}")
            }
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            // 目标：Hotspot=关, Station=开
            val hotspotOn = CarHotspotStatus.isEnabled(applicationContext) == true
            val stationOn = controller.isStationEnabled()
            if (!hotspotOn && stationOn) {
                DiagLog.i(TAG_CAR_HOTSPOT, "existing wifi environment already satisfied, no-op")
                return@Thread
            }
            if (wirelessSyncGeneration.get() != generation) return@Thread
            if (hotspotOn) {
                controller.releaseForStationMode()
            }
            if (wirelessSyncGeneration.get() != generation) return@Thread
            val stationOk = controller.ensureStationEnabled()
            if (wirelessSyncGeneration.get() != generation) return@Thread
            if (!stationOk) {
                lastSyncFailureAt = SystemClock.elapsedRealtime()
                DiagLog.w(TAG_CAR_HOTSPOT, "failed to ensure station enabled in existing wifi mode")
            }
        }

        runOnUiThread {
            if (isFinishing || wirelessSyncGeneration.get() != generation) return@runOnUiThread
            if (page == "home") refreshStatus() else render()
        }
    }, "wireless-sync-worker").apply { isDaemon = true }.start()
}
```
3. 接入所有触发点：
- **`onCreate`**：替换原有的 `wakeCarHotspotInBackground()`，改为 `syncWirelessEnvironment("app_start")`；
- **`onResume`**：在 `onResume` 中调用 `syncWirelessEnvironment("on_resume")`；
- **`applyWirelessLink(mode)`**：在模式保存后调用 `syncWirelessEnvironment("mode_switch")`；
- **自动热点开关切换**：在 `toggle` 回调中保存后，如果 `it == true`，立即调用 `syncWirelessEnvironment("auto_hotspot_enabled")`；
- **`connect(wireless: Boolean)`**：在发起连接前，如果状态未满足期望，触发 `syncWirelessEnvironment("pre_connect")`。

- [ ] **Step 3: 运行 `common` 单元测试**
```sh
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest
```

---

### Task 5: 全量测试验证与 Geely 真实出包

- [ ] **Step 1: 运行全量单元测试**
```sh
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew testDebugUnitTest
```
- [ ] **Step 2: 运行集成打包脚本**
```sh
cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && \
DIPLAY_VENDOR_HOP_DIR=/Users/g/Documents/project/diplay-vendor-hop \
scripts/package-geely.sh local
```
确认新生成的 APK 顺利生成在 `dist/` 目录中，无任何编译或打包报错。
