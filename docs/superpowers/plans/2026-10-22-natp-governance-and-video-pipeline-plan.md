# 吉利 E01 (MT6735) 后台语音独立治理模块 (`:governance`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 
创建完全解耦的独立 Gradle 子模块 `:governance`，通过东软原生 `ExtraUtilsService` Binder 接口（纯内存无脚本 uid-0 提权），实现吉利 E01（MT6735，Android 5.1）车机后台语音内核 `natp`（PSS 263MB、占一核 40% CPU）与前台应用 `com.ecarx.xiaoka`（PSS 62MB）的端到端联合治理，释放 320MB+ 物理内存与半核 CPU；并在 DiPlay 实验性设置中以完全解耦的独立门面完成生命周期挂载与安全自愈。

**Architecture:** 
1. 模块边界：新建 `:governance` 模块，对外暴露单例 `VehicleGovernance` 门面与 `IVehicleGovernor` SPI，主工程单向依赖，模块内部零依赖任何 CarPlay 业务逻辑。
2. 提权下发：利用 Android 5.1 init 原生机制（`ctl.stop` 会在内核底层直接发送 `SIGKILL` 杀死 natp 进程），使用纯 AOSP 原生命令（`setprop` + `pm`，无 `pidof` / `killall` 依赖），命令长度压至 66 字节（远低于 220 字节限额）。
3. 退出生命周期：在 `DiPlayActivity.closeTheApp()` 主动退出前及 `onDestroy` 中触发恢复，杜绝因 `killProcess()` 导致恢复指令未下发。

**Tech Stack:** Kotlin 1.9, Coroutines 1.7.3, Android API 22 (minSdk 19), Gradle Kotlin DSL, JUnit4, Robolectric.

---

## Global Constraints

- **最低 API 支持**：minSdk 19，E01 目标运行环境为 API 22（Android 5.1）。严禁引入 Android 6.0+（API 23+）公共类库。
- **Shell 依赖绝对纯净**：Android 5.1 toolbox 只有基础工具，**严禁依赖 `pidof`、`killall`、`pkill`**。抑制与恢复命令只能使用 `setprop` 与 `pm`。
- **命令行长度硬限制**：所有由 `E01RootBridge` 执行的单行 Shell 命令经过包装后长度必须严格 $\le 220$ 字节。
- **主线程零阻塞**：所有 Binder 提权执行与属性查询必须通过后台协程或工作线程执行。
- **安全第一**：严禁修改 `/system` 或刷写闪存；严禁禁用 `ecarx.controlboard` 与 `ecarx.hvac.app`。

---

## File Structure

```
settings.gradle.kts                                                  (Modify: 注册 :governance)
governance/
├── build.gradle.kts                                                 (Create: 独立子模块构建)
└── src/
    ├── main/
    │   ├── AndroidManifest.xml                                      (Create: 库清单)
    │   └── java/com/shilapi/governance/
    │       ├── VehicleGovernance.kt                                 (Create: 对外门面单例)
    │       ├── core/
    │       │   ├── IVehicleGovernor.kt                              (Create: 治理驱动接口)
    │       │   ├── GovernorFactory.kt                               (Create: 平台探测分发)
    │       │   ├── E01Mtk6735Governor.kt                            (Create: E01 专属实现)
    │       │   ├── NoOpGovernor.kt                                  (Create: 降级空实现)
    │       │   └── CommandQueue.kt                                  (Create: 协程串行防抖队列)
    │       ├── platform/
    │       │   └── E01RootBridge.kt                                 (Create: 纯内存 uid-0 桥接)
    │       └── store/
    │           └── GovernancePersistence.kt                         (Create: 独立持久化存储)
    └── test/
        └── java/com/shilapi/governance/
            ├── E01Mtk6735GovernorTest.kt                            (Create: 指令与状态测试)
            └── CommandQueueTest.kt                                  (Create: 串行队列测试)
common/
├── build.gradle.kts                                                 (Modify: 添加 governance 依赖)
└── src/main/java/com/shilapi/xcertplay/
    ├── DiPlayBootstrap.kt                                           (Modify: 全局注入 install)
    └── DiPlayActivity.kt                                            (Modify: closeTheApp 恢复 & experimentalCards 挂载)
```

---

## Implementation Tasks

### Task 1: 搭建 `:governance` 独立子模块骨架与构建脚本

**Files:**
- Modify: `settings.gradle.kts:27`
- Create: `governance/build.gradle.kts`
- Create: `governance/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: None
- Produces: Gradle 模块 `:governance`

- [ ] **Step 1: 在 `settings.gradle.kts` 中添加 `include(":governance")`**
- [ ] **Step 2: 创建 `governance/src/main/AndroidManifest.xml`**
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.shilapi.governance" />
```
- [ ] **Step 3: 创建 `governance/build.gradle.kts`**
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shilapi.governance"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 19 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
```
- [ ] **Step 4: 运行 `./gradlew :governance:assemble` 验证模块构建成功**
- [ ] **Step 5: Commit: "governance: scaffold independent gradle subproject"**

---

### Task 2: 实现底层提权桥接 `E01RootBridge.kt` 与指令测试

**Files:**
- Create: `governance/src/main/java/com/shilapi/governance/platform/E01RootBridge.kt`
- Create: `governance/src/test/java/com/shilapi/governance/platform/E01RootBridgeTest.kt`

**Interfaces:**
- Consumes: `android.os.IBinder`, `android.os.Parcel`
- Produces: `E01RootBridge.execute(command): Result`

- [ ] **Step 1: 编写失败测试 `E01RootBridgeTest.kt`**
  - 验证命令包装外壳 `{ <cmd>; } 2>&1; rc=$?; echo __GEEKPLAY_ROOT_RC:$rc` 格式；
  - 验证超出 220 字节时抛出 `IllegalArgumentException`；
  - 验证多行命令防御拦截。
- [ ] **Step 2: 运行测试并验证失败**
- [ ] **Step 3: 实现 `E01RootBridge.kt`**
  - 封装东软 `ExtraUtilsService` Binder 通信逻辑；
  - 实现命令安全检查与退出码解析。
- [ ] **Step 4: 运行测试并验证通过**
- [ ] **Step 5: Commit: "governance: implement E01RootBridge for uid-0 execution"**

---

### Task 3: 实现协程串行防抖队列 `CommandQueue.kt` 与独立存储

**Files:**
- Create: `governance/src/main/java/com/shilapi/governance/core/CommandQueue.kt`
- Create: `governance/src/main/java/com/shilapi/governance/store/GovernancePersistence.kt`
- Create: `governance/src/test/java/com/shilapi/governance/core/CommandQueueTest.kt`

**Interfaces:**
- Consumes: Kotlin Coroutines, `Mutex`, `SharedPreferences`
- Produces: `CommandQueue.execute(block): T`, `GovernancePersistence.isSuppressionConfigured(): Boolean`

- [ ] **Step 1: 编写失败测试 `CommandQueueTest.kt` 验证串行互斥**
- [ ] **Step 2: 运行测试验证失败**
- [ ] **Step 3: 实现 `CommandQueue.kt`（内置 Mutex 互斥与 300ms 保护）**
- [ ] **Step 4: 实现 `GovernancePersistence.kt`，使用 `e01_governance_prefs.xml` 独立命名空间**
- [ ] **Step 5: 运行测试验证通过**
- [ ] **Step 6: Commit: "governance: implement serialized CommandQueue and isolated persistence"**

---

### Task 4: 实现治理驱动核心 `IVehicleGovernor`、`E01Mtk6735Governor` 与降级兜底

**Files:**
- Create: `governance/src/main/java/com/shilapi/governance/core/IVehicleGovernor.kt`
- Create: `governance/src/main/java/com/shilapi/governance/core/NoOpGovernor.kt`
- Create: `governance/src/main/java/com/shilapi/governance/core/E01Mtk6735Governor.kt`
- Create: `governance/src/main/java/com/shilapi/governance/core/GovernorFactory.kt`
- Create: `governance/src/test/java/com/shilapi/governance/core/E01Mtk6735GovernorTest.kt`

**Interfaces:**
- Consumes: `E01RootBridge`, `CommandQueue`, `GovernancePersistence`
- Produces: `IVehicleGovernor` 统一接口实现

- [ ] **Step 1: 编写失败测试 `E01Mtk6735GovernorTest.kt`**
  - 严格校验抑制命令（66 字节）：
    `setprop ctl.stop run_natp; pm disable-user --user 0 com.ecarx.xiaoka`（wrapped 105 字节 $\le 220$ 字节）
  - 严格校验恢复命令（53 字节）：
    `pm enable com.ecarx.xiaoka; setprop ctl.start run_natp`（wrapped 92 字节 $\le 220$ 字节）
  - 严格校验自愈命令（27 字节）：
    `pm enable com.ecarx.xiaoka`（wrapped 66 字节 $\le 220$ 字节）
- [ ] **Step 2: 运行测试验证失败**
- [ ] **Step 3: 实现 `IVehicleGovernor.kt`、`NoOpGovernor.kt` 与 `E01Mtk6735Governor.kt`**
- [ ] **Step 4: 实现 `GovernorFactory.kt`，通过 `Build.HARDWARE` / `E01RootBridge.isAvailable()` 自动探测与降级**
- [ ] **Step 5: 运行测试验证通过**
- [ ] **Step 6: Commit: "governance: implement E01Mtk6735Governor with 66B bulletproof commands"**

---

### Task 5: 封装门面单例 `VehicleGovernance.kt` 与编译期物理熔断

**Files:**
- Create: `governance/src/main/java/com/shilapi/governance/VehicleGovernance.kt`
- Create: `governance/src/test/java/com/shilapi/governance/VehicleGovernanceTest.kt`

**Interfaces:**
- Consumes: `GovernorFactory`, `GovernancePersistence`
- Produces: `VehicleGovernance.install()`, `setSuppressionEnabled()`, `onAppExit()`

- [ ] **Step 1: 编写失败测试 `VehicleGovernanceTest.kt`**
  - 验证当 `isExperimentalAllowed == false` 时，门面强制绑定 `NoOpGovernor` 并执行一次自愈巡检；
  - 验证状态变更回调与 StateFlow。
- [ ] **Step 2: 运行测试验证失败**
- [ ] **Step 3: 实现 `VehicleGovernance.kt` 门面**
- [ ] **Step 4: 运行测试验证通过**
- [ ] **Step 5: Commit: "governance: expose VehicleGovernance facade with build-time gating"**

---

### Task 6: 主工程集成、启动感知与 `closeTheApp` 干净恢复

**Files:**
- Modify: `common/build.gradle.kts`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayBootstrap.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`

**Interfaces:**
- Consumes: `com.shilapi.governance.VehicleGovernance`
- Produces: 主工程无感接入生命周期

- [ ] **Step 1: 在 `common/build.gradle.kts` 中添加 `implementation(project(":governance"))`**
- [ ] **Step 2: 在 `DiPlayBootstrap.kt` 中注入 `VehicleGovernance.install(this, ExperimentalFeatures.enabled)`**
- [ ] **Step 3: 在 `DiPlayActivity.closeTheApp()` 中（即退出杀进程前）同步触发恢复：**
  ```kotlin
  private fun closeTheApp() {
      VehicleGovernance.onAppExit()
      releaseProcessWirelessResources(applicationContext)
      CarPlayBackgroundSession.exit { android.os.Process.killProcess(android.os.Process.myPid()) }
  }
  ```
- [ ] **Step 4: 在 `DiPlayActivity.onDestroy()` 中追加 `VehicleGovernance.onAppExit()` 兜底**
- [ ] **Step 5: 运行 `./gradlew test` 确保全量测试通过**
- [ ] **Step 6: Commit: "common: integrate VehicleGovernance and bind to closeTheApp lifecycle"**

---

### Task 7: 设置页（ExperimentalFeatures）UI 挂载与确认弹窗

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:1010` (`experimentalCards`)

**Interfaces:**
- Consumes: `VehicleGovernance.isSupported`, `VehicleGovernance.isSuppressedFlow`
- Produces: 实验性功能设置 Switch 卡片

- [ ] **Step 1: 在 `experimentalCards(columns: CardColumns)` 中挂载语音治理卡片**
  - 只有当 `VehicleGovernance.isSupported && ExperimentalFeatures.enabled` 为 true 时才绘制；
  - 开启 Switch 时弹出警告确认弹窗：“将临时停用原车语音交互并释放 320MB 内存，退出应用自动恢复”；
  - 用户确认后调用 `VehicleGovernance.setSuppressionEnabled(true)`；关闭时直接调用 `false`。
- [ ] **Step 2: 编译并在真机/AVD 上验证设置页 UI 布局**
- [ ] **Step 3: Commit: "ui: mount voice governance switch in experimental settings card"**

---

## Verification & Acceptance Checklist

1. **构建与字节预算验证**：
   - 运行 `./gradlew :governance:test` 全部通过；
   - 抑制指令 66 字节（包装后 105 字节 $\le 220$ 字节）；
   - 恢复指令 53 字节（包装后 92 字节 $\le 220$ 字节）；
   - 绝无 `pidof` 或 `killall` 外部命令依赖。
2. **车端实车验证**：
   - 开启 Switch：执行 `ps | grep -E "natp|xiaoka"`，确认 `natp` 进程彻底消亡，`xiaoka` 为 disabled；
   - 内存核验：`dumpsys meminfo` 验证物理可用内存暴增 320MB+（超过 340MB 可用）；
   - 退出应用：点击顶部退出按钮，车机执行 `closeTheApp()`，确认原厂晓咖与 natp 在 1 秒内完全复活。
