# 蓝牙断开重连后无线连接排查与精准修复计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于实车真实证据定位并解决“蓝牙断开后重新连上，就再也无法无线连接”的真正根因。先通过诊断日志明确断连重连后车机 ECARX HFP / A2DP 双 Profile 的真实状态，再依据实车数据实施针对性修复。

**Architecture:**
1. **可观测性先行（Observability First）**：改造 `EcarxConnectedIphoneLookup`，当判定设备为空时，分别打印 HFP 和 A2DP 各自解析出的设备数量与详情（MAC 与名称），消除当前日志无法区分 `hfp>0, a2dp=0` 与 `hfp=0, a2dp=0` 的盲区。
2. **实车数据定性分流**：根据蓝牙断开重连后的现场日志判定具体分支：
   - **分支 A（HFP 有手机，A2DP 为空）**：验证 `gocsdk` 在只有 HFP 链路时完成 `goc_spp` 握手的可行性；若可行，将门禁调整为以 HFP 为基准（严格排除纯 A2DP 设备，避免多设备崩溃）。
   - **分支 B（HFP 和 A2DP 皆为空）**：排查车机 ECARX 蓝牙底层服务在重连后的状态同步机制（Binder 连接刷新、重新 bindProfile 等）。
3. **真实广播与安全取消（若需主动唤醒）**：从已解包的 `XCBTService.apk` / `com.nforetek.bt` 中提取真实广播 Action，并在 `CarPlayHostActivity` 中显式持有 `reconnectRunnable` 实例进行精准取消。

**Tech Stack:** Kotlin, Android (API 22 / Lollipop MR1), ECARX Vendor Binder, JUnit 4 (Robolectric / Host via `-PvendorHopDir`).

**Spec:** `docs/superpowers/plans/2026-11-02-bluetooth-reconnect-wireless-recovery-plan.md`

## Global Constraints

- **严禁无实据推测**：不提前将“交集为空”定性为“HFP 独活”，必须依靠日志字段证实。
- **不得破坏单手机路径与门禁防御**：不无脑改并集，绝不接纳纯 A2DP 设备成为候选，防止触发 `Multiple connected iPhones` 破坏既有逻辑。
- **不依赖不存在的 AOSP 广播**：吉利 E01 蓝牙射频属 ECARX 独占，若需事件唤醒必须使用真实逆向出的车机 Action。
- **精确 Runnable 管理**：取消延迟任务必须针对特定 Runnable，严禁使用会误杀其他 UI 任务的清空调用。
- **单测环境约束**：`diplay-vendor-hop` 无独立 Gradle 工程，测试须在 DiPlay 主工程带 `-PvendorHopDir` 参数下执行。

---

## 阶段与任务分解

### Task 1: 增强 `EcarxConnectedIphoneLookup` 诊断输出（区分 HFP 与 A2DP 真实状态）

**Files:**
- Modify: `/Users/g/Documents/project/diplay-vendor-hop/src/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookup.kt`
- Test: `/Users/g/Documents/project/diplay-vendor-hop/test/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookupTest.kt`

**Interfaces:**
- Consumes: `queryConnectedDevices(hfpApi, "hfp", onDiagnostic)`, `queryConnectedDevices(a2dpApi, "a2dp", onDiagnostic)`
- Produces: `connectedDevicesFromProfileApis` 保持返回类型 `List<CarBluetoothPhone>` 不变，但在 `intersect` 为空时，通过 `onDiagnostic` 详细输出两个 profile 各自的设备数与列表。

- [ ] **Step 1: 编写单元测试验证诊断明细输出**
  在 `EcarxConnectedIphoneLookupTest.kt` 中添加测试用例：
  - 当 HFP 有设备而 A2DP 无设备时，断言返回值仍为 emptyList（保持原有防御不变），但捕获的诊断日志必须明确包含 `hfp=[CC:60:23:D7:2F:5B]` 和 `a2dp=[]`。
  - 当两者皆无设备时，诊断日志必须明确包含 `hfp=[]` 和 `a2dp=[]`。

- [ ] **Step 2: 在 DiPlay 主工程下带 `-PvendorHopDir` 运行测试确保失败**
  运行命令：
  ```bash
  ./gradlew testDebugUnitTest -PvendorHopDir=/Users/g/Documents/project/diplay-vendor-hop --tests "com.shilapi.xcertplay.network.EcarxConnectedIphoneLookupTest"
  ```
  确认断言失败（因旧代码只输出通用的一句话 `the car reports no phone connected on both profiles`）。

- [ ] **Step 3: 修改 `EcarxConnectedIphoneLookup.kt` 细化诊断日志**
  在 `connectedDevicesFromProfileApis` 中：
  ```kotlin
  val handsFree = queryConnectedDevices(hfpApi, "hfp", onDiagnostic).associateBy { it.address }
  val media = queryConnectedDevices(a2dpApi, "a2dp", onDiagnostic).associateBy { it.address }
  val common = handsFree.keys.intersect(media.keys)
  if (common.isEmpty()) {
      val hfpDesc = if (handsFree.isEmpty()) "none" else handsFree.values.joinToString { "${it.address}(${it.name})" }
      val a2dpDesc = if (media.isEmpty()) "none" else media.values.joinToString { "${it.address}(${it.name})" }
      onDiagnostic("ecarx no phone on both profiles: hfp=[$hfpDesc] a2dp=[$a2dpDesc]")
  }
  return common.map { address -> ... }
  ```
  同时去掉 `connectedDevices()` 里重复且信息量较弱的旧日志行。

- [ ] **Step 4: 运行测试确保全部通过**
  再次执行 `./gradlew testDebugUnitTest -PvendorHopDir=...`，确保现有用例和新加用例全部通过。

- [ ] **Step 5: 提交改动**
  ```bash
  git commit -m "feat(vendor-hop): log detailed HFP and A2DP device states when intersection is empty"
  ```

---

### Task 2: 实车复现与现场日志抓取

- [ ] **Step 1: 打包并安装最新 APK 到实车**
  使用带详细诊断日志的构建安装到吉利 E01 车机。
- [ ] **Step 2: 复现“蓝牙断开后重新连上”流程**
  1. 手机连上蓝牙，启动无线 CarPlay。
  2. 手机关闭蓝牙或断开连接，等待无线 CarPlay 断开。
  3. 手机重新打开蓝牙并连上车机。
  4. 观察 DiPlay 界面并导出现场日志。
- [ ] **Step 3: 分析现场日志判明根因类型**
  - **现象 1（HFP 存活，A2DP 丢失）**：日志显示 `hfp=[CC:60:...], a2dp=[none]`。说明确实是 A2DP 未随蓝牙重连建立，触发了严格交集拦截。进入 **Task 3A**。
  - **现象 2（双协议皆空）**：日志显示 `hfp=[none], a2dp=[none]`。说明重连后车机 ECARX 服务未向 Binder 同步已连接列表，或 Binder 连接需刷新。进入 **Task 3B**。
  - **现象 3（地址或名称变化）**：日志显示 MAC 地址变化导致与配置比对失败。针对性处理地址匹配。

---

### Task 3A: （若证实为 HFP 存活 A2DP 丢失）以 HFP 为基准的门禁改造与 GOC SPP 验证

**Files:**
- Modify: `/Users/g/Documents/project/diplay-vendor-hop/src/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookup.kt`
- Test: `/Users/g/Documents/project/diplay-vendor-hop/test/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookupTest.kt`

- [ ] **Step 1: 验证 GOC SPP 握手在仅有 HFP 时是否可行**
  确认底层 `gocsdk` 是否仅依赖底层 ACL 连接即可通过 `LocalSocket("goc_spp")` 与 iPhone 建立 iAP2 通道。
- [ ] **Step 2: 改造门禁为“以 HFP 为基准，严格排除纯 A2DP 设备”**
  - 设备候选集以 `handsFree.values` 为主，A2DP 仅用于补充/校正名称，不把只有 A2DP 的设备纳入候选集，杜绝多设备混淆崩溃。
- [ ] **Step 3: 更新单测并验证**
  确保纯 HFP 设备能够成功返回，纯 A2DP 设备被安全忽略。

---

### Task 3B: （若证实为双协议皆空）排查 ECARX 服务重连刷新机制

**Files:**
- Modify: `/Users/g/Documents/project/diplay-vendor-hop/src/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookup.kt`

- [ ] **Step 1: 检查 Binder 存活状态与重新绑定**
  若 Binder 服务在蓝牙重连后返回旧缓存，在每次查询超时或重试时执行 unbindService + 重新 bindService，强制触发底层 AIDL 重新建立。
- [ ] **Step 2: 验证实车是否能刷新出已连接列表**。

---

### Task 4: 逆向车机真实广播与安全取消 Runnable 改造（若需主动唤醒）

**Files:**
- Inspect: `extracted_apks/jadx_xcbtservice_full/`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`

- [ ] **Step 1: 从 `XCBTService.apk` / `com.nforetek.bt` 逆向代码中提取真实连接 Action**
  检索车机广播发送点（如 `nforetek` 状态变化广播），记录准确的 Action 字符串。
- [ ] **Step 2: 改造 `CarPlayHostActivity` 延迟退避管理**
  - 显式声明 `private var pendingReconnectRunnable: Runnable? = null`。
  - 在 `reconnectAfterLoss` 中：
    ```kotlin
    val runnable = Runnable { ... }
    pendingReconnectRunnable = runnable
    mainHandler.postDelayed(runnable, delayMillis)
    ```
  - 取消时仅调用 `mainHandler.removeCallbacks(it)`，安全释放单点任务，杜绝批量清空副作用。
