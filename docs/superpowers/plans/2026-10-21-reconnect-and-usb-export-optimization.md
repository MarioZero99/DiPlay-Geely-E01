# 断线极速重连与 U 盘日志导出修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 解决吉利 E01 车机上同网 Wi-Fi 掉线后重连耗时过长（卡 30 秒超时）以及 U 盘保存日志失败（未识别到挂载卷）的问题，实现点击重连立即 Hard-Reset 释放残留音视频流与监听、同网 First-TCP 等待窗口收紧至 5 秒、跳过无用系统 NSD 挂起等待、以及将 `/storage/usbotg` 与 `/proc/mounts` 真实挂载卷纳入探测的完整闭环。

**Architecture:**
1. **U 盘挂载探测修复 (`DiagnosticVolumeProbe`)**：
   - 现存 Bug：`vendorMountRoots`（包含 `/storage/usbotg` 等）只遍历其子目录，未将 `/storage/usbotg` 本身作为候选卷；开机即插 U 盘无法收到 `ACTION_MEDIA_MOUNTED` 动态广播。
   - 改造：通过读取 `/proc/mounts` 自动解析出所有处于挂载状态的外置文件系统（排除主存储 `/storage/emulated/0`），同时把 `/storage/usbotg`、`/storage/sdcard1` 自身作为候选卷直接进行可写性与包目录探测。
2. **掉线与手动重连 Hard-Reset (`CarPlayController` & `DiPlayActivity`)**：
   - 现存 Bug：TCP Event 断开后，音视频 UDP 媒体接收器与解码器仍在后台运行，iPhone 误以为旧会话还在推流，不响应新会话握手；用户手动点击重连时新旧状态叠加。
   - 改造：在 `CarPlayController` 暴露主动复位接口并在 `fail()` 时彻底关闭全部底层 Socket（音视频 UDP、TCP Listener、RFCOMM）；在 `DiPlayActivity` 点击手动重连时先执行强制释放再全新拉起握手，让 iPhone 毫秒级感知旧链路终结。
3. **同网 Wi-Fi 等待窗口与广播启动优化 (`WirelessStartupPolicy` & `CarPlayBonjour`)**：
   - 现存 Bug：同网模式下 `FIRST_TCP_MILLIS` 仍使用车载热点默认的 30 秒，导致重连异常时车主在屏幕前白等 30 秒；`CarPlayBonjour` 在没有 NSD 的车机上仍然先发起 `nsdManager.registerService()` 并 `await(3000ms)`。
   - 改造：在 `CarPlayController` 中针对同网模式（`EXISTING_WIFI`）将初次 TCP 等待时间单独控制为 5 秒，快速触发重置与自愈；在车机环境配置直接直通内置 mDNS，消除无用的 3 秒阻塞。

**Tech Stack:** Kotlin, Android SDK 22 (minSdk 19, Android 5.1 E01 baseline), JmDNS 3.6.3, `android.system.Os`, JUnit 4, Robolectric 4.17。

**Spec:** 本计划基于 2026-10-21 下午真实车机诊断日志（`DiPlay-20261021-180423-026.txt`）及 E01 固件（`fstab.mt6735` / `init.mt6735.rc`）逆向取证分析设计。

## Global Constraints

- **最低 API 支持**：minSdk 19，E01 为 API 22。不可使用 Android 6.0+（API 23+）新增的公共 API。
- **严格遵循 RFC 6762 规范**：mDNS 响应报文源端口必须为 5353，不得使用随机 UDP 端口规避冲突。
- **真凭实据，绝不谎报**：U 盘写入成功才提示已存入 U 盘；写盘必须执行 `FileDescriptor.sync()` 确保物理刷盘；外置路径不进入 `FileProvider`。
- **主线程零阻塞**：所有磁盘与网络探测、Socket 关闭与重启必须在工作线程执行。

---

### Task 1: 修复 U 盘探测根卷缺失与 `/proc/mounts` 解析 (`DiagnosticVolumeProbe`)

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticVolumeProbe.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticVolumeProbeTest.kt`

**Interfaces:**
- Consumes: `DiagnosticVolumeProbe.probe(context: Context, extraMountPaths: Collection<String>): ProbeSummary`
- Produces: 增强后的 `candidates` 列表，包含从 `/proc/mounts` 读取到的真实外置挂载点，以及 `vendorMountRoots` 本身与子目录。

- [ ] **Step 1: 编写新增测试用例**
  在 `DiagnosticVolumeProbeTest.kt` 中添加单测：
  - 测试 `vendorMountRoots` 自身作为卷根目录被识别并探测（不仅仅是子目录）；
  - 测试包含 `/storage/usbotg`、`/storage/sdcard1` 的候选路径可被正确纳入，且主存储 `/storage/emulated/0` 被正确排除。
- [ ] **Step 2: 运行测试并验证失败**
  运行 `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticVolumeProbeTest`，确认新增测试按预期红掉。
- [ ] **Step 3: 修改 `DiagnosticVolumeProbe.kt` 探测逻辑**
  1. 在 `vendorMountRoots` 列表中补充吉利 E01 常见挂载点：
     ```kotlin
     private val vendorMountRoots = listOf(
         "/storage/usbotg",
         "/storage/udisk",
         "/storage/sdcard1",
         "/storage/sdcard2",
         "/mnt/usb_storage",
         "/mnt/udisk",
         "/mnt/usb",
         "/mnt/media_rw",
     )
     ```
  2. 修复 Source 3 遍历逻辑：不仅扫描子目录，如果 `root` 本身是有效目录且非主存储，**直接将 `root` 本身加入候选卷**：
     ```kotlin
     for (rootPath in vendorMountRoots) {
         val root = File(rootPath)
         if (!root.isDirectory || isPrimaryStorage(root)) continue
         if (root.name !in candidates) candidates[root.name] = root
         root.listFiles()?.filter { it.isDirectory && !isPrimaryStorage(it) }?.forEach { sub ->
             if (sub.name !in candidates) candidates[sub.name] = sub
         }
     }
     ```
  3. 增加 Source 4（从 `/proc/mounts` 读取）：解析处于 mounted 状态的外置文件系统挂载点（过滤类型如 vfat, exfat, fuse, ntfs, ext4，排除 `/storage/emulated`、`/system`、`/data` 等内部路径）。
- [ ] **Step 4: 运行测试并确保全部通过**
  运行 `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticVolumeProbeTest`，确保单测全绿。
- [ ] **Step 5: 提交更改**
  `git commit -m "fix(diag): include root mount paths and proc mounts in volume probe"`

---

### Task 2: 掉线与手动重连时的底层媒体链路彻底清理 (Hard Reset)

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/orchestration/CarPlayControllerTest.kt`（或新增测试用例）

**Interfaces:**
- Consumes: `CarPlayController.closeWirelessStack()`, `CarPlayController.abort()`, `DiPlayActivity.retry()`
- Produces: `CarPlayController.forceResetSession()` 或在 `fail()` / 手动重连时主动同步掐断音视频 UDP 与 ServerSocket。

- [ ] **Step 1: 编写单测验证会话复位断流行为**
  在测试中验证当调用复位时，底层 `AirPlaySession`、音视频 UDP 数据链路被立即关闭，不残留接收循环。
- [ ] **Step 2: 运行测试确认测试工作正常**
- [ ] **Step 3: 实现 `CarPlayController` 强制复位与清理逻辑**
  1. 在 `fail()` 触发时，确保不仅记录错误状态，同时立刻关闭当前活跃的所有媒体数据传输通道（调用 `AirPlaySession.close()`，关闭视频 UDP/TCP、音频 UDP/TCP 套接字），防止继续从 iPhone 接收孤立数据包。
  2. 在 `DiPlayActivity` 中，当用户点击“重试”或触发手动重连时：
     - 先向当前 controller 发送强制中断与完全释放调用（`controller?.abort(immediate = true)`）；
     - 等待旧套接字释放完毕后再拉起下一代握手，杜绝新旧会话并发重叠。
- [ ] **Step 4: 运行 `:shared:testDebugUnitTest` 验证通过**
- [ ] **Step 5: 提交更改**
  `git commit -m "fix(orchestration): perform hard reset of media sockets on disconnect and manual retry"`

---

### Task 3: 优化同网模式下 First-TCP 等待窗口与广播流程

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/network/WirelessStartupPolicy.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/network/CarPlayBonjour.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/orchestration/FirstTcpWatchdogTest.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/network/CarPlayBonjourTest.kt`

**Interfaces:**
- Consumes: `WirelessStartupPolicy.FIRST_TCP_MILLIS`, `CarPlayBonjour.publishAirPlay()`
- Produces: `FIRST_TCP_EXISTING_WIFI_MILLIS = 5_000L`；当 `useInterfaceMdns = true` 时跳过 `registerAirPlay()` 阻塞等待。

- [ ] **Step 1: 编写单测**
  - 测试在同网后端下，`FirstTcpWatchdog` 超时使用 5 秒短等待；
  - 测试在 `useInterfaceMdns` 为 true 时，Bonjour 启动直接进入接口 mDNS 流程，不阻塞等待系统 NSD 的 3000ms 锁存。
- [ ] **Step 2: 运行测试并验证失败**
- [ ] **Step 3: 实现差异化超时与广播优化**
  1. 在 `WirelessStartupPolicy` 中定义同网模式超时：
     ```kotlin
     const val FIRST_TCP_EXISTING_WIFI_MILLIS = 5_000L
     ```
  2. 在 `CarPlayController` 构造 `FirstTcpWatchdog` 时，根据当前的 `wirelessHotspotBackend` 是 `EXISTING_WIFI` 还是 `MANUAL` 动态传入超时毫秒数。
  3. 在 `CarPlayBonjour.kt` 中：当 `useInterfaceMdns == true` 时，直接执行 `interfaceMdnsActive = startInterfaceMdns()`，彻底绕过 `nsdManager.registerService()` 的 3 秒阻塞。
- [ ] **Step 4: 运行全部相关单测**
  运行 `./gradlew :shared:testDebugUnitTest`，确保所有测试用例全绿通过。
- [ ] **Step 5: 提交更改**
  `git commit -m "perf(wireless): reduce existing wifi first tcp timeout to 5s and bypass nsd wait"`

---

### Task 4: 全量回归测试与发布产物核验

**Files:**
- Verify: 全工程构建产物与 APK

- [ ] **Step 1: 运行全量单元测试**
  运行 `./gradlew :common:testDebugUnitTest :shared:testDebugUnitTest`，确认全部单测通过。
- [ ] **Step 2: 编译 local release 安装包**
  运行 `./gradlew :mobile:assembleStandaloneRelease -Pexperimental=on`。
- [ ] **Step 3: 校验 APK**
  检查产物 DEX 与资源文件，将产物复制到 `/Users/g/Downloads/xr/DiPlay-Legacy-Geely-V044-local.apk`。
- [ ] **Step 4: 提交实施总结**
