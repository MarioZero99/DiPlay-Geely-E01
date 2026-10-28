# 星瑞仪表媒体信息解锁与 BTAudio 状态守护实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 解锁吉利星瑞 2021 款仪表屏 CarPlay 媒体信息显示，破除东软 `IPKServer` 的 `BTAudio` 软件拦截，并在切歌时进行状态守护自愈。

**Architecture:** 
1. 确立默认音源为 `SOURCE_BLUETOOTH = 2`（对应仪表 CAN 协议 Byte 5 外部流媒体通道）；
2. 抽象独立的 `BTAudioGuard` 模块，在推歌前执行毫秒级读取，若被车机后台重置则立即纠正为 `"Connected"`；
3. CarPlay 退出时不重置状态，等待车机下次连接物理蓝牙时由原厂回调自然覆写；
4. 集成至 `XingruiInstrumentBridge`，保持非阻塞与节流架构。

**Tech Stack:** Kotlin, Android ContentResolver (`Settings.System`), ProcessBuilder/Shell 兜底, JUnit 4, Robolectric.

---

### Task 1: 提交已验证的 U 盘导出与日志脱敏修复

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticVolumeProbe.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticRedactor.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/XingruiInstrumentBridge.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticVolumeProbeTest.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt`

- [ ] **Step 1: 检查 git status 确认改动范围**
- [ ] **Step 2: 执行已有的单元测试确认无误**
  `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiagnosticVolumeProbeTest" --tests "com.shilapi.xcertplay.DiagnosticExportStoreTest" --tests "com.shilapi.xcertplay.DiagnosticRedactorTest"`
- [ ] **Step 3: 提交代码**
  `git commit -m "fix(export,cluster): 恢复/proc/mounts的U盘挂载探测与日志脱敏seq命名"`

---

### Task 2: 实现 `BTAudioGuard` 守护模块与单元测试

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/instrument/BTAudioGuard.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/instrument/BTAudioGuardTest.kt`

**Interfaces:**
- Produces: `BTAudioGuard.ensureConnected(context: Context): Boolean`

- [ ] **Step 1: 编写 BTAudioGuard 失败单元测试**
  在 `BTAudioGuardTest.kt` 中模拟 `Settings.System.getString` 返回 `"Disconnected"` 或 `null`，验证 `ensureConnected` 能将其更新为 `"Connected"`。
- [ ] **Step 2: 运行测试并确认失败**
  `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.BTAudioGuardTest"`
- [ ] **Step 3: 编写 BTAudioGuard 最小实现代码**
  - 使用 `Settings.System.getString` 快速判断；
  - 若不等于 `"Connected"`，调用 `Settings.System.putString(..., "BTAudio", "Connected")`；
  - 捕获 `SecurityException` 或系统受限异常，兜底执行 `su -c settings put system BTAudio Connected`。
- [ ] **Step 4: 运行测试并确认通过**
  `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.instrument.BTAudioGuardTest"`
- [ ] **Step 5: 提交 Task 2 代码**
  `git commit -m "feat(cluster): 增加BTAudio系统状态检测与自愈守护模块"`

---

### Task 3: 将 `BTAudioGuard` 接入推歌链路并对齐音源通道

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/XingruiInstrumentBridge.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt`

- [ ] **Step 1: 修改 AirPlayPersistence 恢复 SOURCE_BLUETOOTH 默认**
  移除之前对 `SOURCE_BLUETOOTH` 强转 `SOURCE_USB` 的临时逻辑，默认返回 `SOURCE_BLUETOOTH (2)`。
- [ ] **Step 2: 在 XingruiInstrumentBridge 推歌前执行守护检查**
  在 `doSendMediaUpdate()` 与 `doSendActionOnly()` 发送 AIDL 前调用 `BTAudioGuard.ensureConnected(appContext)`，确保切歌瞬间门禁为开启状态。
  遵循设计：退出时不重置 `BTAudio`，等待下次车载蓝牙自动覆写。
- [ ] **Step 3: 运行相关单元测试**
  `./gradlew :common:testDebugUnitTest`
- [ ] **Step 4: 提交 Task 3 代码**
  `git commit -m "feat(cluster): 推歌前自愈BTAudio状态并对齐蓝牙流媒体通道"`

---

### Task 4: 编译出包准备实车测试

**Files:**
- N/A

- [ ] **Step 1: 运行全量单元测试**
  `./gradlew testDebugUnitTest`
- [ ] **Step 2: 编译 Debug/Release APK**
  `./gradlew :mobile:assembleDebug`
- [ ] **Step 3: 提供用户验证说明（U 盘导出验证 + 仪表点亮验证）**
