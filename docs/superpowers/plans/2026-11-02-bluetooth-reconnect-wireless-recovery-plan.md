# 蓝牙断开重连后无线CarPlay自动恢复与自愈 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底解决用户反馈的“蓝牙断开后重新连上，就再也无法无线连接”问题。消除 ECARX 双协议严格交集阻塞，引入蓝牙 ACL/Profile 重连事件主动唤醒与退避重置机制，保障无线 CarPlay 在蓝牙恢复后自动并高可靠地重连。

**Architecture:**
1. **ECARX 蓝牙已连设备判定解绑**：重构 `EcarxConnectedIphoneLookup`，摒弃原有的“必须同时连接 HFP 和 A2DP（严格交集）”逻辑，改为以 HFP/免提为主、A2DP 互补的并集策略，解决 iOS/车机重连时因 A2DP 未连导致判定设备为 0 的假离线陷阱。
2. **蓝牙重连主动感知与自愈唤醒**：在 `CarPlayHostActivity` 中注册动态 `BroadcastReceiver`（监听 `BluetoothDevice.ACTION_ACL_CONNECTED`、`BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED` 以及亿咖通蓝牙连接广播）。一旦检测到目标 iPhone 蓝牙重新接入，立即重置退避计数器并取消死等定时器，秒级主动唤醒重拉无线流程。
3. **GOC SPP 套接字与 Controller 端口快速释放与自愈**：优化蓝牙断开与重连时 `GocSppTransport` 的释放与复位，并在会话清理中彻底释放 AirPlay 7000 端口，杜绝端口冲突与残留状态干扰。

**Tech Stack:** Kotlin, Android Bluetooth SDK, ECARX Vendor Binder APIs, Robolectric & JUnit 4.

**Spec:** `docs/superpowers/plans/2026-11-02-bluetooth-reconnect-wireless-recovery-plan.md`

## Global Constraints

- **兼容性要求**：严禁破坏原生 AOSP 蓝牙链路（`AndroidIap2BluetoothLink`）与 Actions USB 适配器链路。
- **只使用单设备时信任当前连接**：保持 10-14 拍板的原则（若车机只报一台设备，即便地址与历史存档不一致，也以当前连接为准，不强退设置页）。
- **TDD 驱动开发**：每一个 Task 必须先写红测试再写实现，最后绿测试回归。

---

## 阶段与任务分解

### Task 1: 消除 ECARX 蓝牙已连设备的双 Profile 严格交集门禁

**Files:**
- Modify: `/Users/g/Documents/project/diplay-vendor-hop/src/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookup.kt`
- Test: `/Users/g/Documents/project/diplay-vendor-hop/test/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookupTest.kt`

**Interfaces:**
- Consumes: `queryConnectedDevices(api, label, onDiagnostic)`
- Produces: `connectedDevicesFromProfileApis(hfpApi, a2dpApi, resolveRemoteName, onDiagnostic): List<CarBluetoothPhone>`（返回 HFP 与 A2DP 并集设备，HFP 优先级高于 A2DP）

- [ ] **Step 1: 编写红测试（验证 HFP 单独连接时依然被判定为有效设备）**
  修改 `EcarxConnectedIphoneLookupTest.kt`：
  - 更新测试用例 `onlyDevicesConnectedOnBothProfilesCount` 为 `devicesConnectedOnHandsFreeAloneCountWhenMediaIsDisconnected`，断言当 iPhone 仅在 HFP（免提）上连接而 A2DP（媒体）未连接时，依然能正确返回该手机。
  - 增加测试用例 `deviceConnectedOnMediaAloneAlsoCounts`，验证仅 A2DP 连上时也保留为候选。

- [ ] **Step 2: 运行测试确保失败**
  运行 Gradle 单元测试，确认断言失败（旧代码因为 `intersect` 会返回空列表）。

- [ ] **Step 3: 修改 `EcarxConnectedIphoneLookup.kt` 实现并集策略**
  - 将 `handsFree.keys.intersect(media.keys)` 调整为并集合并：
    ```kotlin
    val allAddresses = handsFree.keys + media.keys
    return allAddresses.map { address ->
        val name = handsFree[address]?.name?.ifBlank { null }
            ?: media[address]?.name?.ifBlank { null }
            ?: resolveRemoteName(address).orEmpty().trim()
        CarBluetoothPhone(address, name)
    }.sortedWith(compareBy({ it.name.lowercase(Locale.US) }, { it.address }))
    ```
  - 调整诊断日志：仅当 HFP 和 A2DP 均无任何设备连接时，才输出 `the car reports no phone connected on any profile`。

- [ ] **Step 4: 运行测试确保全部通过**
  执行 `gradle testDebugUnitTest`，确认 `EcarxConnectedIphoneLookupTest` 6 个用例全部绿色。

- [ ] **Step 5: 提交代码**
  ```bash
  git commit -m "fix(vendor-hop): resolve phone when connected on HFP alone without requiring A2DP"
  ```

---

### Task 2: 增加蓝牙重连事件监听与主动自愈唤醒器（BluetoothReconnectWatcher）

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/BluetoothReconnectWatcher.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/BluetoothReconnectWatcherTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class BluetoothReconnectWatcher(
      private val context: Context,
      private val isWirelessMode: () -> Boolean,
      private val onBluetoothReconnected: (deviceAddress: String?, deviceName: String?) -> Unit,
  ) {
      fun start()
      fun stop()
  }
  ```

- [ ] **Step 1: 编写单元测试验证广播捕获与事件回调触发**
  编写 `BluetoothReconnectWatcherTest.kt`：
  - 模拟收到 `BluetoothDevice.ACTION_ACL_CONNECTED` 广播，断言 `onBluetoothReconnected` 能够被调用，且携带设备 MAC 与 Name。
  - 模拟收到 `BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED` 状态为 `STATE_CONNECTED`，断言触发回调。
  - 模拟收到吉利亿咖通蓝牙连接广播 `ecarx.intent.action.BLUETOOTH_DEVICE_CONNECTED`，断言触发回调。
  - 验证当处于有线 USB 模式（`isWirelessMode() == false`）时，过滤忽略该事件。
  - 验证调用 `stop()` 后广播解注册，不再响应事件。

- [ ] **Step 2: 运行测试确保红灯**
  运行 Gradle 单元测试，确认因类不存在或未实现而失败。

- [ ] **Step 3: 实现 `BluetoothReconnectWatcher`**
  - 动态注册 `BroadcastReceiver`，监听：
    - `BluetoothDevice.ACTION_ACL_CONNECTED`
    - `BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED`
    - `ecarx.intent.action.BLUETOOTH_DEVICE_CONNECTED`
  - 内部加入轻量防抖（例如 1.5 秒内同一设备的重复连接事件去重），避免协议栈多 profile 连接瞬间触发连环风暴。

- [ ] **Step 4: 运行测试确保绿灯**
  执行 `BluetoothReconnectWatcherTest`，验证全部通过。

- [ ] **Step 5: 提交代码**
  ```bash
  git commit -m "feat(bluetooth): introduce BluetoothReconnectWatcher for active reconnection sensing"
  ```

---

### Task 3: 在 `CarPlayHostActivity` 接入主动唤醒与退避重置逻辑

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/CarPlayHostActivityBluetoothReconnectTest.kt`

**Interfaces:**
- Consumes: `BluetoothReconnectWatcher`
- Modifies: `CarPlayHostActivity.reconnectAfterLoss`, `CarPlayHostActivity.onBluetoothReconnected`

- [ ] **Step 1: 编写集成测试模拟退避中断与重连提速**
  编写 `CarPlayHostActivityBluetoothReconnectTest.kt`：
  - 模拟前次连接失败，进入 `reconnectScheduled = true` 且等待 30 秒的场景。
  - 发送蓝牙已连接广播，断言：
    1. 正在排队的 30 秒延迟任务被立即取消（`mainHandler.removeCallbacks`）；
    2. `reconnectAttempts` 重置为 0；
    3. `restartCarPlay` 立即被触发（reason="Bluetooth reconnected"）。

- [ ] **Step 2: 运行测试确保失败**
  运行单元测试确认当前 Activity 无法对蓝牙重连做出反应。

- [ ] **Step 3: 在 `CarPlayHostActivity` 中集成 `BluetoothReconnectWatcher`**
  - 在 `onCreate` / `onStart` 中启动 `BluetoothReconnectWatcher`，在 `onDestroy` / `onStop` 中停止。
  - 在回调函数中增加处理：
    ```kotlin
    private fun onBluetoothDeviceReconnected(deviceAddress: String?, deviceName: String?) {
        if (!wirelessEnabled || shuttingDown.get()) return
        Log.i(TAG, "Bluetooth reconnected address=$deviceAddress name=$deviceName; breaking reconnect backoff")
        appendLog("Bluetooth device reconnected: ${deviceName ?: deviceAddress ?: "unknown"}; triggering fast reconnect")
        // 取消正在退避排队的重试
        reconnectAttempts = 0
        if (reconnectScheduled) {
            reconnectScheduled = false
            mainHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        }
        if (!handshakeResetInProgress && controller == null) {
            restartCarPlay("Bluetooth reconnected")
        }
    }
    ```

- [ ] **Step 4: 运行测试验证功能与时序**
  跑通 `CarPlayHostActivityBluetoothReconnectTest` 及所有回归用例。

- [ ] **Step 5: 提交代码**
  ```bash
  git commit -m "feat(host): fast-recover wireless CarPlay when Bluetooth reconnects"
  ```

---

### Task 4: 完善 GOC SPP 套接字与断连自愈清理

**Files:**
- Modify: `/Users/g/Documents/project/diplay-vendor-hop/src/com/shilapi/xcertplay/transport/GocSppTransport.kt`
- Modify: `/Users/g/Documents/project/diplay-vendor-hop/src/com/shilapi/xcertplay/transport/GocIap2BluetoothLink.kt`
- Test: `/Users/g/Documents/project/diplay-vendor-hop/test/com/shilapi/xcertplay/transport/GocSppTransportTest.kt`

**Interfaces:**
- Consumes: `LocalSocket`
- Produces: `GocSppTransport.connect(bluetoothAddress): GocSppTransport`

- [ ] **Step 1: 编写测试覆盖旧连接断开后再次调用 `connect` 的场景**
  验证当发生 EOF / 链路断开时，套接字被完全销毁，下次调用 `connect` 能够干净重新发起握手。

- [ ] **Step 2: 审查并修复 `GocSppTransport` 异常关闭细节**
  确保在 `failAndClose` 中关闭 `socket` 时，若底层因系统资源暂时忙碌未完全释放，捕获并在重连时重试最多 3 次，间隔 200ms。

- [ ] **Step 3: 运行回归测试**
  运行 `GocSppTransportTest` 与 `GocIap2BluetoothLinkTest`，保证 100% 通过。

- [ ] **Step 4: 提交代码**
  ```bash
  git commit -m "fix(vendor-hop): ensure clean reset of GocSppTransport on disconnect"
  ```

---

### Task 5: 全链路端到端回归验证

- [ ] **Step 1: 运行全量单元测试**
  - 在 `DiPlay-Legacy-Android-Geely` 执行 `./gradlew testDebugUnitTest`
  - 在 `diplay-vendor-hop` 执行 `./gradlew test`
  - 确认所有测试全部 Green。

- [ ] **Step 2: 验证 APK 编译**
  执行 `./gradlew assembleDebug`，确认无编译错误且打包正常。
