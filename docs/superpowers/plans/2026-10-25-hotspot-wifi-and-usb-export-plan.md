# 吉利星瑞 E01 无线双模静默切换、单射频互斥与 U 盘日志 Root 穿透落盘实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 
彻底根治吉利星瑞 E01（Android 5.1 / MT6735 单射频芯片）车机上：
1. 0.4.7 中热点与 Wi-Fi 无法自动静默启闭的问题；
2. 车载热点模式与同网 Wi-Fi 模式互相切换后“找不到 iPhone / 广播连接断开”的问题；
3. 诊断报告无法保存到外置 U 盘的问题。

**Architecture:** 
1. **轻量级原生 Binder IPC 替代脆弱的 `app_process`**：通过在主进程中安全反射 `IWifiManager.Stub.TRANSACTION_setWifiApEnabled` 动态获取 Binder 事务码，利用 `RootShellBridge` 执行毫秒级 `/system/bin/service call wifi <CODE> s16 "android.net.wifi.IWifiManager" i32 0 i32 <1|0>` 控制热点，搭配 `/system/bin/svc wifi enable|disable` 控制 Wi-Fi，彻底消除虚拟机启动延迟、内存暴涨与超时死锁；
2. **ProGuard/R8 混淆白名单加固**：保留 `PrivilegedHotspotTool` 等一切通过反射或命令调用的入口，作为次级安全兜底；
3. **U 盘 Root 穿透写入与物理刷盘**：在 `DiagnosticExportStore` 中引入 Root 提权管道，突破 Android 5.1 对二级 USB 外置卷的 `EACCES` 媒体权限隔离，通过 Root 将临时文件直接 copy 并 `chmod 666` 到 U 盘根目录下的 `DiPlay/` 文件夹，并调用 `sync` 确保即拔即存；
4. **单射频双模排他对齐与连接链路自愈**：严格规范“模式切换 ➔ 清理残余 AirPlay 会话与端口 ➔ 彻底关停前一模式 ➔ 轮询确认射频释放 ➔ 启闭目标网络 ➔ 校验 IP 就绪”的时序闭环，并将默认 IP 策略调整为通用稳定的 IPv4 优先，消除跨网段握手超时。

**Tech Stack:** Kotlin 1.9, Java 11 / Android 5.1 (API 22, minSdk 19), Binder IPC, Neusoft ExtraUtilsService (uid 0), JUnit4, Robolectric.

---

## Global Constraints

- **最低 API 兼容**：严格遵循 minSdk 19 / targetSdk 37 / 编译 API 22（Android 5.1 Lollipop），严禁引入 API 23+ 类或高版本系统调用。
- **单射频互斥时序**：联发科 MT6735 不能同时保持 Station 模式与 SoftAP 模式。从 Station 切 AP 必须等待底层确认彻底关闭（`StationPhase.OFF`）；从 AP 切 Station 必须等待热点彻底关闭（`ApPhase.OFF`）。
- **执行时间与内存约束**：E01 可用内存低至 20~30MB。单次命令必须控制在 50ms 内完成，严禁在后台频繁启动重型 `app_process` 进程。
- **降级保护**：非 E01 平台或未 root 环境自动降级回原有纯 AOSP / 提示手动操作流程，绝不 crash。

---

## File Structure

```
DiPlay-Legacy-Android-Geely/
├── mobile/proguard-rules.pro                                       (Modify: 添加 PrivilegedHotspotTool 与相关类 -keep)
├── shared/src/main/java/com/shilapi/xcertplay/
│   ├── network/
│   │   ├── CarHotspotController.kt                                  (Modify: 接入 service call wifi 极速 Binder 路径)
│   │   └── HotspotAddressPolicy.kt                                  (Modify: 优化同网模式 IP 优先级与回退)
│   └── system/
│       ├── RootShellBridge.kt                                       (Modify: 增加 service call wifi 命令构建与并发锁保护)
│       └── PrivilegedHotspotTool.kt                                 (Modify: 保留作为次级兜底，消除强类型转型隐患)
├── common/src/main/java/com/shilapi/xcertplay/
│   ├── DiagnosticExportStore.kt                                     (Modify: 增加 Root 穿透 U 盘落盘与 sync 刷盘)
│   ├── DiagnosticVolumeProbe.kt                                     (Modify: 支持 Root 提权环境下的可写卷认定)
│   └── DiPlayActivity.kt                                            (Modify: 模式切换时重置冷却、清理残余会话与状态刷新)
└── [对应单元测试文件]
    ├── shared/src/test/java/com/shilapi/xcertplay/network/CarHotspotControllerTest.kt
    ├── shared/src/test/java/com/shilapi/xcertplay/system/RootShellBridgeTest.kt
    └── common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt
```

---

## Tasks

### Task 1: 升级 RootShellBridge 支持原生 Binder IPC 控制热点，并加固并发模型

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/system/RootShellBridge.kt`
- Modify: `mobile/proguard-rules.pro`
- Test: `shared/src/test/java/com/shilapi/xcertplay/system/RootShellBridgeTest.kt`

**Interfaces:**
- Produces:
  - `RootShellBridge.getWifiApTransactionCode(): Int?`
  - `RootShellBridge.hotspotServiceCallCommand(code: Int, enable: Boolean): String`
  - 并发保护：将简单的 `AtomicBoolean` 升级为带排队的互斥锁或序列化执行，杜绝短时间内合法连续指令被误丢弃。

- [ ] **Step 1: 编写测试用例 `RootShellBridgeTest.kt`**
  - 测试 `hotspotServiceCallCommand(code = 28, enable = true)` 生成标准命令：
    `"/system/bin/service call wifi 28 s16 android.net.wifi.IWifiManager i32 0 i32 1"`
  - 测试命令长度严格低于 220 字节限制；
  - 测试 `getWifiApTransactionCode()` 的安全反射与异常降级。

- [ ] **Step 2: 修改 `mobile/proguard-rules.pro`**
  添加防剪裁规则：
  ```proguard
  # --- Privileged Root Tools ------------------------------------------------------------------------
  -keep class com.shilapi.xcertplay.system.PrivilegedHotspotTool {
      public static void main(java.lang.String[]);
  }
  ```

- [ ] **Step 3: 在 `RootShellBridge.kt` 中实现动态 Binder 事务码与指令生成**
  - 动态缓存 `TRANSACTION_setWifiApEnabled`（反射 `IWifiManager.Stub`）；
  - 实现极速指令生成：`service call wifi <CODE> s16 "android.net.wifi.IWifiManager" i32 0 i32 <1|0>`；
  - 优化并发机制，使用同步互斥锁确保连续命令不丢包。

- [ ] **Step 4: 运行测试并验证**
  - `./gradlew :shared:testDebugUnitTest --tests "*RootShellBridgeTest*"`

---

### Task 2: 改造 CarHotspotController 实现三级兜底开关热点与可靠互斥

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/network/CarHotspotController.kt`
- Test: `shared/src/test/java/com/shilapi/xcertplay/network/CarHotspotControllerTest.kt`

**Interfaces:**
- Produces:
  - `CarHotspotController.enableAccessPointUntil`: 
    优先顺序：Native 反射 ➔ Root 原生 `service call wifi`（耗时 5ms） ➔ Root `app_process` 兜底；
  - `CarHotspotController.releaseForStationMode`:
    关闭热点同样采用 `service call wifi` 极速指令。

- [ ] **Step 1: 编写测试用例 `CarHotspotControllerTest.kt`**
  - 模拟 Native 反射失败时，优先触发 `service call wifi` 极速命令；
  - 验证关热点时等待 `ApPhase.OFF` 的超时判定与状态对齐。

- [ ] **Step 2: 在 `CarHotspotController.kt` 中改造热点启闭逻辑**
  - 将原来的直接 `app_process` 升级为先尝试轻量 Binder 指令；
  - 若动态获取到事务码，直接执行 `RootShellBridge.execute(serviceCallCommand)`；
  - 确保开关热点与开关联动 Station 均在 100ms 级别内完成。

- [ ] **Step 3: 运行测试并验证**
  - `./gradlew :shared:testDebugUnitTest --tests "*CarHotspotControllerTest*"`

---

### Task 3: 改造 DiagnosticExportStore 与 DiagnosticVolumeProbe 实现 U 盘 Root 穿透落盘

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticVolumeProbe.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt`

**Interfaces:**
- Produces:
  - `DiagnosticVolumeProbe.VolumeProbeResult.rootWritable`: 在 Root 可用且分区不是只读（ro）挂载时标记为 true；
  - `DiagnosticExportStore.exportViaRoot(targetFile: File, report: String): Boolean`: 通过写入 app 私有 cache 临时文件，Root `cp` 至 U 盘并 `chmod 666` 及 `sync`。

- [ ] **Step 1: 编写测试用例 `DiagnosticExportStoreTest.kt`**
  - 模拟普通 Java 写入 U 盘抛出 `IOException("EACCES")` 时，自动进入 Root 穿透拷贝；
  - 验证生成命令包含 `mkdir -p`、`cp` 与 `sync`。

- [ ] **Step 2: 在 `DiagnosticVolumeProbe.kt` 中完善卷可写认定**
  - 若系统包含 Root 能力（`RootShellBridge.isAvailable()`），且卷为 `rw` 挂载，将 `rootWritable` 认定为 true。

- [ ] **Step 3: 在 `DiagnosticExportStore.kt` 中实现 Root 穿透写入**
  - 当常规 `syncWriteText` 失败且 Root 可用时，调用 `exportViaRoot`；
  - 将生成的诊断报告成功落盘至 `<U盘根目录>/DiPlay/<文件名>`，并在 UI 上反馈成功保存至 U 盘。

- [ ] **Step 4: 运行测试并验证**
  - `./gradlew :common:testDebugUnitTest --tests "*DiagnosticExportStoreTest*"`

---

### Task 4: 模式切换闭环治理与同网模式 IP 优先级纠偏（解决双模切换找不到 iPhone）

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/network/HotspotAddressPolicy.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`

**Interfaces:**
- Produces:
  - 模式切换即时重置失败冷却（清除 15 秒阻塞锁）；
  - 切换模式时主动清理未释放的 AirPlay 监听端口（防止 7000 端口假死被占）；
  - `EXISTING_WIFI` 模式下默认首选通用 IPv4 地址（配合即时 IPv6 探测），杜绝个人热点链路本地 IPv6 路由不可达。

- [ ] **Step 1: 编写测试用例**
  - 测试模式切换时 `lastSyncFailureAt` 被立即置零；
  - 测试 `HotspotAddressPolicy` 在无显式指定时，同网模式首选有效 IPv4（或双栈广播）。

- [ ] **Step 2: 改造 `DiPlayActivity.kt` 模式切换联动**
  - 在 `applyWirelessLink` 中主动调用 `CarPlayBackgroundSession.stop()` 与端口解绑；
  - 触发 `requestWirelessSync(WirelessSyncReason.MODE_SWITCH)` 时强制旁路冷却；
  - 只有在确认网络状态与 IP 真正就绪后，才允许发起连接。

- [ ] **Step 3: 改造 `CarPlayController.kt` 与 `HotspotAddressPolicy.kt`**
  - 纠正对 `EXISTING_WIFI` 盲目硬选 IPv6 导致的连接超时问题，确保 IPv4 和 IPv6 均能在 iAP2 `0x4301` 中完整发布。

- [ ] **Step 4: 全工程测试与回归打包**
  - `./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest`
