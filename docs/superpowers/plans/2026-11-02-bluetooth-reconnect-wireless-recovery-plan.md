# 蓝牙断开重连后无线连接排查与精准修复计划（修订版）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于严谨的实车证据，彻底排查并解决“蓝牙断开后重新连上，就再也无法无线连接”的问题。首先通过精确诊断日志看清断连重连后车机 ECARX HFP / A2DP 双 Profile 的真实状态，再根据现场日志精准定性，实施对应的分支修复。

**Architecture:**
1. **可观测性先行（统一精确的诊断字符串）**：
   在 `EcarxConnectedIphoneLookup` 中，当交集为空时，统一格式输出：
   `ecarx no phone on both profiles: hfp=[$hfpDesc] a2dp=[$a2dpDesc]`
   其中无设备时严格使用 `none`，有设备时严格使用 `${it.address}(${it.name})`（多个用逗号空格隔开）。单测断言、代码实现、实车判定三处字符串完全一致。
2. **结合现有前置日志构建完整判据矩阵**：
   区分“Binder 未应答”（`did not answer`）、“调用抛异常”（`threw or answered nothing`）、“占位符过滤”（`reported 00:00:00:00:00:00`）、“地址无法解析”（`had no address this side can read`）、“服务正常但列表皆空”（`hfp=[none] a2dp=[none]`）、“仅 HFP 存活”（`hfp=[...] a2dp=[none]`）以及“选机通过但在后续阶段失败”。
3. **实车数据驱动的分流修复**：
   - **分支 1（整段 15 秒均为 HFP 存活，A2DP 始终为 none）**：在确认 A2DP 并非只是延迟到达且 `gocsdk` 在纯 HFP 下可建立 `wireless RFCOMM connected` 的前提下，方可将门禁改为以 HFP 为基准（严格排除纯 A2DP 设备进入候选）。
   - **分支 2（双协议均回答正常但列表皆为 none）**：因现有代码已在每秒新建实例并 unbind/bind，此现象表明 ECARX 底层服务未同步连接状态，需排查底层状态上报与刷新逻辑。
   - **分支 3（前置服务异常）**：若出现 `did not answer` 或 `threw`，排查 Binder 通信与进程存活。
   - **分支 4（双协议均有设备且选机成功，失败发生在 `bt/select` 之后）**：排查 `goc_spp` 套接字连接（`Could not connect to reserved LocalSocket goc_spp`）、RFCOMM 超时、iAP2 握手或 Wi-Fi TCP 阶段。

**Tech Stack:** Kotlin, Android (API 22), ECARX Vendor Binder, JUnit 4 (Robolectric / Host via `:shared:testDebugUnitTest -PvendorHopDir=...`).

**Spec:** `docs/superpowers/plans/2026-11-02-bluetooth-reconnect-wireless-recovery-plan.md`

## Global Constraints

- **格式严格对齐**：诊断日志格式在测试用例、代码实现、判据说明中严格一致（`hfp=[none]` 或 `hfp=[ADDRESS(NAME)]`）。
- **门禁防御不可破坏**：在未取得实车“整段 15 秒 HFP 有手机且 A2DP 为 none”并验证 SPP 可行的证据前，交集逻辑保持不动；即便修改，也绝不把纯 A2DP 设备纳为候选，杜绝多设备 `Multiple connected iPhones` 崩溃。
- **单手机已容错**：车机只报一台手机时，现有逻辑已自动容错（`the car reports one phone and it is not the one DiPlay saved; using the car's`），不再做重复防御。
- **精确测试命令**：必须使用 `:shared:testDebugUnitTest` 配合 `-PvendorHopDir`，禁止在根工程直接运行。
- **暂不预设广播唤醒**：因车机 APK 代码处于 odex 中，广播唤醒需在确认有此需求后再行逆向提取真实 Action。

---

## 阶段与任务分解

### Task 1: 细化 `EcarxConnectedIphoneLookup` 诊断输出

**Files:**
- Modify: `/Users/g/Documents/project/diplay-vendor-hop/src/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookup.kt`
- Test: `/Users/g/Documents/project/diplay-vendor-hop/test/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookupTest.kt`

**Interfaces:**
- Consumes: `queryConnectedDevices(hfpApi, "hfp", onDiagnostic)`, `queryConnectedDevices(a2dpApi, "a2dp", onDiagnostic)`
- Produces: `connectedDevicesFromProfileApis` 保持返回类型 `List<CarBluetoothPhone>` 不变。交集为空时，触发诊断输出：`ecarx no phone on both profiles: hfp=[$hfpDesc] a2dp=[$a2dpDesc]`。

- [ ] **Step 1: 编写单元测试验证诊断明细输出**
  在 `EcarxConnectedIphoneLookupTest.kt` 中：
  - 测试用例 `ecarxNoPhoneOnBothProfilesLogsIndividualProfilesDetailed`：
    - 场景 1：HFP 有 `CC:60:23:D7:2F:5B`（名称 `iPhone`），A2DP 为空。断言返回值为空列表，且 `said` 包含精确行：
      `ecarx no phone on both profiles: hfp=[CC:60:23:D7:2F:5B(iPhone)] a2dp=[none]`
    - 场景 2：HFP 为空，A2DP 为空。断言返回值为空列表，且 `said` 包含精确行：
      `ecarx no phone on both profiles: hfp=[none] a2dp=[none]`
    - 场景 3：HFP 为空，A2DP 有 `CC:60:23:D7:2F:5B`（名称 `iPhone`）。断言返回值为空列表，且 `said` 包含精确行：
      `ecarx no phone on both profiles: hfp=[none] a2dp=[CC:60:23:D7:2F:5B(iPhone)]`

- [ ] **Step 2: 运行测试确保失败**
  运行测试命令：
  ```bash
  /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/gradlew -p /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely :shared:testDebugUnitTest -PvendorHopDir=/Users/g/Documents/project/diplay-vendor-hop --tests "com.shilapi.xcertplay.network.EcarxConnectedIphoneLookupTest"
  ```
  确认由于旧代码未输出该精细格式而红灯。

- [ ] **Step 3: 修改 `EcarxConnectedIphoneLookup.kt`**
  在 `connectedDevicesFromProfileApis` 中统一实现：
  ```kotlin
  val handsFree = queryConnectedDevices(hfpApi, "hfp", onDiagnostic).associateBy { it.address }
  val media = queryConnectedDevices(a2dpApi, "a2dp", onDiagnostic).associateBy { it.address }
  val common = handsFree.keys.intersect(media.keys)
  if (common.isEmpty()) {
      val hfpDesc = if (handsFree.isEmpty()) "none" else handsFree.values.joinToString { "${it.address}(${it.name})" }
      val a2dpDesc = if (media.isEmpty()) "none" else media.values.joinToString { "${it.address}(${it.name})" }
      onDiagnostic("ecarx no phone on both profiles: hfp=[$hfpDesc] a2dp=[$a2dpDesc]")
  }
  return common.map { address ->
      val name = handsFree.getValue(address).name.ifBlank { media.getValue(address).name }
      CarBluetoothPhone(address, name.ifBlank { resolveRemoteName(address).orEmpty().trim() })
  }.sortedWith(compareBy({ it.name.lowercase(Locale.US) }, { it.address }))
  ```
  同时清理 `connectedDevices()` 里原本重复且模糊的那句 `if (devices.isEmpty()) onDiagnostic("ecarx the car reports no phone connected on both profiles")`。

- [ ] **Step 4: 运行测试确保全部通过**
  再次运行 `:shared:testDebugUnitTest`，确认所有测试用例（包括新加的细化诊断断言和原有 6 个用例）全部绿灯。

- [ ] **Step 5: 提交改动**
  ```bash
  git -C /Users/g/Documents/project/diplay-vendor-hop commit -am "feat(vendor-hop): log exact HFP and A2DP device details when profile intersection is empty"
  ```

---

### Task 2: 实车复现与完整判据矩阵分析

- [ ] **Step 1: 安装带细化诊断的构建至实车**
  使用带 Task 1 改动的 APK 安装到吉利 E01 车机。
- [ ] **Step 2: 现场复现并提取完整日志**
  1. 无线 CarPlay 正常连接。
  2. 手机关闭蓝牙或断开连接，直至 CarPlay 退出。
  3. 手机重新打开蓝牙并连接车机，观察车机状态栏与手机蓝牙已连。
  4. 保持连接 15~30 秒，导出完整 DiPlay 诊断日志。
- [ ] **Step 3: 对照判据矩阵判定真实根因**
  - **分支 A（纯 HFP 存活）**：
    整段 15 秒轮询中，持续输出 `ecarx no phone on both profiles: hfp=[CC:60:...(...)] a2dp=[none]`（且 A2DP 始终未到达）。进入 **Task 3A**。
  - **分支 B（双服务均空）**：
    持续输出 `ecarx no phone on both profiles: hfp=[none] a2dp=[none]`。服务应答正常，但两者列表皆空。进入 **Task 3B**。
  - **分支 C（前置服务异常）**：
    出现 `ecarx hfp did not answer` / `ecarx a2dp did not answer`，或 `getConnectedDevices threw or answered nothing`。进入 **Task 3C**。
  - **分支 D（选机通过，失败在后续链路）**：
    日志中 `bt/select` 成功选出 target，但随后在 `goc_spp`（`Could not connect to reserved LocalSocket goc_spp`）、RFCOMM（`wireless RFCOMM connect timed out`）或握手阶段失败。进入 **Task 3D**。

---

### Task 3A: （若证实为分支 A：纯 HFP 存活）验证 SPP 握手与门禁调整

**Files:**
- Modify: `/Users/g/Documents/project/diplay-vendor-hop/src/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookup.kt`
- Test: `/Users/g/Documents/project/diplay-vendor-hop/test/com/shilapi/xcertplay/network/EcarxConnectedIphoneLookupTest.kt`

- [ ] **Step 1: 验证纯 HFP ACL 链路下的 `goc_spp` 可用性**
  在实车上验证：当 A2DP 为 none 时，将 HFP 的蓝牙 MAC 写入 `goc_spp`，确认是否能建立 `wireless RFCOMM connected` 并完成 iAP2 握手。
- [ ] **Step 2: 改造门禁为以 HFP 为基准（严格排除纯 A2DP）**
  - 设备候选集以 `handsFree` 为基准：
    - 若 `handsFree` 有设备，使用 `handsFree.values`，A2DP 仅用来补充/覆盖名称；
    - 绝不接纳 `media` 独有的设备（纯 A2DP 设备直接排除），避免多设备时报 `Multiple connected iPhones`。
- [ ] **Step 3: 运行回归测试并提交**
  跑通 `:shared:testDebugUnitTest -PvendorHopDir=...` 并提交代码。

---

### Task 3B: （若证实为分支 B：双协议均空）排查 ECARX 状态未同步

- [ ] **Step 1: 检查 ECARX 蓝牙底层服务状态上报机制**
  排查为什么手机已连接但车机 HFP / A2DP 两个 Binder 接口的 `getConnectedDevices()` 均返回空列表。
- [ ] **Step 2: 对比正常连接与断开重连时车机蓝牙进程的状态差异**。

---

### Task 3C: （若证实为分支 C：前置服务未应答/异常）排查 Binder 服务连接

- [ ] **Step 1: 定位未应答的 Profile 服务**
  针对 `ecarx hfp did not answer` 或 `ecarx a2dp did not answer`，分析 Binder 服务在蓝牙断开重连时是否发生了进程重启、死锁或未完成绑定。

---

### Task 3D: （若证实为分支 D：选机通过，失败在后半程）排查 SPP / RFCOMM / Wi-Fi 握手

- [ ] **Step 1: 定位具体失败步骤**
  排查是 `gocsdk` 进程假死、`goc_spp` 套接字未就绪、RFCOMM 超时还是 Wi-Fi TCP 端口冲突。
- [ ] **Step 2: 实施对应的传输层复位或自愈机制**。
