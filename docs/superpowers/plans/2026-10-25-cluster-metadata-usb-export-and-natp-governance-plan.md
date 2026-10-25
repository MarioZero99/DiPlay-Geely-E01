# 吉利 E01 仪表多媒体广播、U 盘物理挂载写盘与 NATP 治理转正实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
在吉利 E01（Android 5.1 / API 22）车机平台上落地三项关键改进：
1. 媒体信息双写广播（`com.android.music.metachanged`）与 `MediaMetadata`/`PlaybackState` 联动，修复仪表盘多媒体卡片不显示曲目与封面的问题；
2. U 盘真实物理块设备（`/dev/block/sda*`）探测与 Root 动态挂载，杜绝在内部虚拟空目录伪保存，确保日志真实落盘至物理 U 盘；
3. 将后台语音治理（`natp` + 晓咖）移出实验区转为标准功能交付用户，并在日志中加入 `natp` 进程真实存活状态的周期/触发式埋点探测。

**Architecture:**
1. **多媒体广播与会话增强**：在 `CarPlayMediaKeys`（`Api21Backend`）中，当收到 iPhone 的 `0x5001` 元数据或封面时，不仅向 `MediaSession` 下发完备的卡片元数据（补全 `DISPLAY_TITLE`、`DISPLAY_SUBTITLE` 等），同时触发同步 `PlaybackState(STATE_PLAYING)`，并向全系统广播标准车载多媒体意图 `com.android.music.metachanged` 和 `playstatechanged`，穿透 GKUI 仪表盘与原生车机桌面小部件。
2. **物理 U 盘探测与挂载**：在 `DiagnosticVolumeProbe` 中引入块设备探测与 Root 挂载机制；在 `/proc/mounts` 无外置挂载点时，检查 `/dev/block/sd*`，通过 Root 安全挂载至真实外部存储目录；在 `DiagnosticExportStore` 中严格校验挂载事实，杜绝在空目录刷虚假成功。
3. **NATP 探测埋点与转正**：在 `:governance` 模块中实现 `natp` 进程存活性探测（通过 `E01PrivilegedBridge` 执行轻量探测 `pidof natp`），把执行结果与存活事实记录进 `DiagLog` 与诊断报告头；在 UI 上将控制开关移出 `experimentalCards`，面向吉利 E01 平台转正交付。

**Tech Stack:** Kotlin 1.9, Android API 22 (minSdk 19), MediaSession / MediaMetadata, System Broadcasts, Linux Block Devices / VFS Mount, JUnit4, Robolectric.

---

## Global Constraints

- **最低 API 支持**：minSdk 19，E01 目标运行环境为 API 22（Android 5.1 Lollipop），严禁引入 API 23+ 类库或方法。
- **系统安全与稳定性**：广播发送严禁引发阻塞；Root 命令执行限制在 220 字节限额内；封面 Bitmap 必须保证采样与及时 `recycle()`。
- **诊断日志完整性**：所有关键动作（广播发送、U 盘挂载、natp 进程存活性）必须记录到 `DiagLog`。

---

## File Structure

```
common/src/main/java/com/shilapi/xcertplay/
├── CarPlayMediaKeys.kt                         (Modify: 增加广播发送、Display元数据与PlaybackState联动)
├── DiagnosticVolumeProbe.kt                   (Modify: 增加物理块设备探测与Root挂载感知)
├── DiagnosticExportStore.kt                   (Modify: 校验真实挂载点、执行块设备挂载写盘)
├── DiagnosticReportProvider.kt                (Modify: 诊断报告头加入 natp 存活状态)
└── DiPlayActivity.kt                          (Modify: 语音治理卡片移出实验区转正)

governance/src/main/java/com/shilapi/governance/
├── VehicleGovernance.kt                       (Modify: 去除实验性门禁，暴露进程状态查询)
└── core/
    ├── IVehicleGovernor.kt                    (Modify: 增加 queryProcessAlive 接口)
    └── E01Mtk6735Governor.kt                  (Modify: 实现 natp 进程探测与 DiagLog 埋点)

mobile/src/main/java/com/shilapi/xcertplay/
└── DiPlayApplication.kt                       (Modify: VehicleGovernance.install 默认开放支持)

common/src/test/java/com/shilapi/xcertplay/
├── CarPlayMediaKeysBroadcastTest.kt           (Create: 媒体广播与卡片元数据测试)
└── DiagnosticVolumeMountTest.kt               (Create: 真实块设备挂载写盘测试)

governance/src/test/java/com/shilapi/governance/
└── core/E01Mtk6735GovernorProbeTest.kt        (Create: natp 进程探测命令测试)
```

---

## Implementation Tasks

### Task 1: 媒体元数据三通道广播发送与 `PlaybackState` 联动同步

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt`

**Interfaces:**
- Produces: 
  - `sendMediaBroadcast(context: Context, nowPlaying: CarPlayNowPlaying, playing: Boolean)`
  - `buildMetadata()` 补充 `METADATA_KEY_DISPLAY_TITLE`, `METADATA_KEY_DISPLAY_SUBTITLE`, `METADATA_KEY_DISPLAY_DESCRIPTION`
  - `publishMetadata()` 时联动推进 `currentSession.setPlaybackState()`

- [ ] **Step 1: 编写失败测试 `CarPlayMediaKeysBroadcastTest.kt`**
  - 验证当 `publishMetadata` 触发时，系统发出 `com.android.music.metachanged` 广播，且携带 `track`（歌名）、`artist`、`album`、`playing=true` 等 Extra；
  - 验证 `MediaMetadata` 包含 `METADATA_KEY_DISPLAY_TITLE`；
  - 验证 `currentSession.playbackState` 与当前播放状态同步。

- [ ] **Step 2: 运行测试验证失败**

- [ ] **Step 3: 修改 `CarPlayMediaKeys.kt`**
  - 扩展 `buildMetadata`，添加 Display 专用键值；
  - 在 `publishMetadata` 中添加广播发送函数 `broadcastMediaChange`，依次发送 `com.android.music.metachanged` 与 `com.android.music.playstatechanged`；
  - 在曲目更新且 `nowPlaying.playing` 时同步更新 `session.setPlaybackState(STATE_PLAYING)`。

- [ ] **Step 4: 运行测试验证通过**

- [ ] **Step 5: Commit: "media: broadcast com.android.music.metachanged and link playback state for cluster HUD"**

---

### Task 2: U 盘真实物理块设备探测、Root 挂载与日志保存验证

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticVolumeProbe.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/DiagnosticVolumeMountTest.kt`

**Interfaces:**
- Produces:
  - `DiagnosticVolumeProbe.findBlockDevices(): List<String>`
  - `DiagnosticVolumeProbe.mountExternalBlockDevice(blockDev: String): File?`
  - `DiagnosticExportStore.verifyRealMount(targetFile: File): Boolean`

- [ ] **Step 1: 编写失败测试 `DiagnosticVolumeMountTest.kt`**
  - 验证当 `/proc/mounts` 无挂载点但存在物理块设备 `/dev/block/sda1` 时，自动尝试挂载至真实目录；
  - 验证禁止将日志写入未经 `/proc/mounts` 校验的内部假路径 `/storage/usbotg`。

- [ ] **Step 2: 运行测试验证失败**

- [ ] **Step 3: 修改 `DiagnosticVolumeProbe.kt` 与 `DiagnosticExportStore.kt`**
  - 在 `DiagnosticVolumeProbe` 中增加物理块设备探测（扫描 `/dev/block/sd*` 与 `/dev/block/vold/*`）；
  - 若未挂载，调用 `RootShellBridge.execute("mkdir -p /mnt/udisk && mount -t vfat $dev /mnt/udisk")`；
  - 在 `DiagnosticExportStore` 中校验写入路径是否位于真正的挂载文件系统内，写入完成后执行 `sync` 并在 `DiagLog` 记录真实写入物理路径。

- [ ] **Step 4: 运行测试验证通过**

- [ ] **Step 5: Commit: "export: mount physical usb block devices via root and prevent fake path saves"**

---

### Task 3: NATP 进程存活状态探测埋点与语音治理转正交付

**Files:**
- Modify: `governance/src/main/java/com/shilapi/governance/core/IVehicleGovernor.kt`
- Modify: `governance/src/main/java/com/shilapi/governance/core/E01Mtk6735Governor.kt`
- Modify: `governance/src/main/java/com/shilapi/governance/VehicleGovernance.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticReportProvider.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Modify: `mobile/src/main/java/com/shilapi/xcertplay/DiPlayApplication.kt`
- Create: `governance/src/test/java/com/shilapi/governance/core/E01Mtk6735GovernorProbeTest.kt`

**Interfaces:**
- Produces:
  - `IVehicleGovernor.checkNatpAlive(): Boolean?`
  - `VehicleGovernance.isNatpProcessAlive(): Boolean?`
  - 诊断报告输出 `Background voice governance: suppressed (natp process: terminated)`
  - UI 移出 `experimentalCards`，提升为标准车规调优项

- [ ] **Step 1: 编写失败测试 `E01Mtk6735GovernorProbeTest.kt`**
  - 验证 `checkNatpAlive()` 通过 `pidof natp` 构造轻量命令并正确解析输出（进程存在返回 true，空/错误返回 false）；
  - 验证命令长度不超过 220 字节限额。

- [ ] **Step 2: 运行测试验证失败**

- [ ] **Step 3: 实现 `checkNatpAlive` 与 DiagLog 埋点**
  - 在 `E01Mtk6735Governor` 中实现 `checkNatpAlive()`，每次压制或恢复后立即执行探测，并在 `DiagLog.i("VehicleGovernance", "natp alive=$alive after $action")` 记录埋点；
  - 在 `DiagnosticReportProvider.kt` 中抓取该状态并打印至诊断报告头。

- [ ] **Step 4: 将功能移出实验区**
  - 在 `DiPlayApplication.kt` 中将 `VehicleGovernance.install(this, isExperimentalAllowed = true)` 默认常开；
  - 在 `DiPlayActivity.kt` 中将 `voiceGovernanceCard` 移至通用/车规设置区域，不再受 `ExperimentalFeatures.enabled` 约束。

- [ ] **Step 5: 运行全量测试验证通过**

- [ ] **Step 6: Commit: "governance: graduate natp suppression to standard settings and add process alive probe"**

---

### Task 4: 全量构建与回归验证

- [ ] **Step 1: 运行全工程单元测试** (`./gradlew testDebugUnitTest`)
- [ ] **Step 2: 运行 Android Lint 静态检查** (`./gradlew lintDebug`)
- [ ] **Step 3: 构建 release 与 local APK** (`./gradlew assembleRelease assembleDebug`)
- [ ] **Step 4: 检查构建产物与版本日志**
