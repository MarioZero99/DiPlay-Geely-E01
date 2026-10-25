# 彻底消除项目高级特性与极端适配吉利 E01（Android 5.1）实施计划 · 阶段 1（修订版）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 针对吉利 E01 车机硬件平台（MTK MT6735, 1GB~2GB RAM, Android 5.1 / API 22, 单中控 720p 屏幕, 单射频 MT6625, MTK ALSA HAL, 准备接入 E01 仪表台与车机媒体中心），彻底消除上游遗留的无关高版本特性与 BYD 专属包袱（AAOS 汽车总线音频、BYD DiLink 5.1 监控与布局、LocalOnlyHotspot、HEVC 软解），同时**完整保留并严谨加固 E01 仪表屏扩展管道与 E01 媒体封面图分发管线（`NowPlayingArtworkQueue`）**，实现对 E01 的极端适配。

**Architecture:** 
1. 模块解耦：从 Gradle 构建解耦并注销 `:automotive` 模块；
2. 音频通道固化：彻底废除 `advanced_audio_channel_mapping`（AAOS 总线），锁定简洁稳定的原生 Android StreamType 路由（媒体走 STREAM_MUSIC，导航走 STREAM_NOTIFICATION/14），解决导航声音被混入媒体流导致无法独立调音与压音的隐患；
3. 仪表台架构净化：彻底删除 BYD 专有代码（`DiLink51ClusterMonitor`、`DiLink51ClusterLayout` 及硬编码指纹），解耦并净化通用的 `ClusterMapPresentation` 架构，为后续无缝接入 E01 仪表屏投屏保留标准干净的显示通道；
4. 无线热点直通：消除不兼容的 `LocalOnlyHotspot`（API 26+），无线模式坚决锁定在 E01 原生驱动级热点（`MANUAL`，基于 ioctl/binder）和同网 Wi-Fi（`EXISTING_WIFI`）；
5. 视频解码硬化：剔除不可用的 `isSoftwareOnly` HEVC 软解查找死代码，默认硬解优先；
6. 媒体封面加固：保留最新实现并修复了 OOM 漏洞的 `NowPlayingArtworkQueue` 与 `CarPlayMediaKeys`，加固 Android 5.1 下 `MediaSession` / `MediaMetadata` 向 E01 车机与仪表台展示专辑封面的内存与 Binder 传输安全性。

**Tech Stack:** Kotlin, Android SDK 22 (Lollipop MR1), MTK MT6735 HAL, Gradle Kotlin DSL, JUnit4 / Robolectric.

**Spec / Baseline Context:**
* 平台定位：吉利博越/缤瑞 E01 车机（Android 5.1.1 / API 22, GKUI 19）；
* 音频深度优化（如中间热点断流保护、弹性抖动缓冲、ALSA 回环防爆音）由专属计划 [2026-10-25-mtk-loopback-and-elastic-jitter-buffer-plan.md](file:///Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely/docs/superpowers/plans/2026-10-25-mtk-loopback-and-elastic-jitter-buffer-plan.md) 单独承接；
* 关键 API 修复（`UpdateDownloader` 替换 `contentLengthLong`、`TcpLiveness` 修正 `Build.VERSION_CODES.O` 门禁、`ManualHotspotManager` 驱动 ioctl 与安全反射）已在提交 `de0c1039` 固化作为基线。

## Global Constraints

* **Floor Target:** 必须确保代码在 Android 5.1 (API 22) 下完全免抛 `NoSuchMethodError`、`NoClassDefFoundError` 与 `SecurityException`；
* **Preserve E01 Roadmap:** 严禁破坏通用的仪表台投屏扩展点（`ClusterMapPresentation`）与媒体封面图分发队列（`NowPlayingArtworkQueue`）；
* **BYD Purge:** 彻底清除所有比亚迪特有指纹（`BYD-AUTO/IVI...`）、分屏图层（`fission_bg...`）与 `UsageStatsManager` 轮询；
* **Zero UI Regression:** 移除无用卡片后，设置页与主页排版自适应无断层，所有单测保持 100% Green；
* **Commit Granularity:** 每个 Task 结束后运行 `./gradlew testDebugUnitTest` 验证，并进行独立 Git 提交。

---

## 阶段 1 任务拆解与执行路线

| Task | 模块 | 核心工作内容 | 影响范围 |
|---|---|---|---|
| **Task 1** | 构建工程 | 解耦并注销 `:automotive` 模块 | `settings.gradle.kts` |
| **Task 2** | 音频管线 | 彻底消除“高级音频通道映射”与 AAOS 总线，固化导航流路由 | `AudioChannelMapping.kt`, `AndroidMediaSink.kt`, `CarPlayHostActivity.kt`, `config.xml` |
| **Task 3** | 仪表台架构 | 彻底剥离 BYD DiLink 监控与专有布局，净化通用仪表屏投屏通道供 E01 接入 | `DiLink51ClusterMonitor.kt` (删除), `DiLink51ClusterLayout.kt` (删除), `ClusterMapPresentation.kt`, `CarPlayHostActivity.kt`, `DiPlayActivity.kt` |
| **Task 4** | 网络与热点 | 彻底消除 `LocalOnlyHotspot` (API 26+) 冗余代码 | `LocalOnlyHotspotManager.kt` (删除), `CarPlayController.kt`, `WirelessModeSelection.kt` |
| **Task 5** | 视频与解码 | 剔除不可用的 HEVC 软解选择逻辑，锁定硬件硬解 | `AirPlayPersistence.kt`, `AndroidMediaSink.kt`, `DiPlayActivity.kt` |
| **Task 6** | E01 媒体封面 | 加固并对齐 E01 媒体中心/仪表封面展示（保留 `NowPlayingArtworkQueue`） | `CarPlayMediaKeys.kt`, `NowPlayingArtworkQueue.kt`, `AirPlayPersistence.kt` |
| **Task 7** | 整合验证 | 运行全量单元测试与 Lint 检查，构建 Release APK 验收 | 全模块单测与打包 |

---

### Task 1: 解耦并注销 `:automotive` 模块

**Files:**
- Modify: `settings.gradle.kts:28`

**Interfaces:**
- 移除不再适用的 Android Automotive OS (minSdk 28) 独立工程，缩短 Gradle 配置和索引耗时。

- [ ] **Step 1: 修改 `settings.gradle.kts` 移除 `:automotive`**
  注释或删除 `include(":automotive")`。

- [ ] **Step 2: 验证编译与单测**
  ```bash
  ./gradlew testDebugUnitTest
  ```
  确认工程配置通过，不再编译无用的 AAOS 依赖。

- [ ] **Step 3: 提交代码**
  ```bash
  git commit -am "chore(build): unregister unused automotive module from settings"
  ```

---

### Task 2: 彻底消除“高级音频通道映射”与 AAOS 汽车总线模式

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AudioChannelMapping.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt`
- Modify: `common/src/main/res/values/config.xml`
- Modify: `shared/src/test/java/com/shilapi/xcertplay/media/AudioChannelMappingTest.kt`

**Interfaces:**
- `AudioChannelMapper.map(audioType, payloadType, navigationStreamType)` 不再需要 `mode: AudioChannelMappingMode`；
- `AndroidMediaSink` 构造函数彻底移除 `advancedAudioChannelMapping: Boolean`；
- `usesNavigationStream(audioType, payloadType)` 恒定返回流是否属于导航（`channel == AudioChannel.NAVIGATION`），永不因“高级映射开关”被篡改返回 false，**彻底解决导航声音落入媒体通道导致无法独立调音与压音的问题**。

- [ ] **Step 1: 更新单测**
  在 `AudioChannelMappingTest.kt` 中移除 `AUTOMOTIVE_BUS` 测试用例，断言各种音频类型（telephony, speechrecognition, media, alert, default）在标准流路由下精准映射到对应通道与 StreamType。

- [ ] **Step 2: 重构 `AudioChannelMapping.kt`**
  - 移除 `AudioChannelMappingMode` 枚举；
  - 简化 `AudioChannelMapper.map`，仅保留适合 E01 移动/平板 ROM 的干净映射；
  - `usesNavigationStream` 消除 `advanced: Boolean` 参数，直接依据目标 channel 进行判断。

- [ ] **Step 3: 清理上层调用链**
  - `AndroidMediaSink.kt`：移除 `advancedAudioChannelMapping` 参数，所有 `audioAttributes()` 和流类型构建逻辑统一收拢；
  - `CarPlayHostActivity.kt`：移除 `advancedAudioChannelMapping` 变量与从 Resources/Persistence 加载逻辑；
  - `DiPlayActivity.kt`：从 `audioRoutingCard` 中移除“高级音频通道映射”切换开关及说明；
  - `AirPlayPersistence.kt`：删除 `KEY_ADVANCED_AUDIO_CHANNEL_MAPPING`、`loadAdvancedAudioChannelMapping` 和 `saveAdvancedAudioChannelMapping`。

- [ ] **Step 4: 运行全量测试**
  ```bash
  ./gradlew testDebugUnitTest
  ```

- [ ] **Step 5: 提交代码**
  ```bash
  git commit -am "refactor(audio): purge advancedAudioChannelMapping and harden navigation stream routing for E01"
  ```

---

### Task 3: 纯净化仪表台架构：彻底清除 BYD DiLink 残留，保留并对齐 E01 仪表屏扩展

**Files:**
- Delete: `common/src/main/java/com/shilapi/xcertplay/DiLink51ClusterMonitor.kt`
- Delete: `common/src/main/java/com/shilapi/xcertplay/DiLink51ClusterLayout.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Modify: `common/src/test/java/com/shilapi/xcertplay/ClusterMapPresentationTest.kt`

**Interfaces:**
- 彻底剔除 BYD 专有的 `UsageStatsManager` 轮询与硬编码指纹；
- `ClusterMapPresentation` 与 `CarPlayHostActivity` 保留通用的 `Display` 探测、`Presentation.show()` 与 Alt-screen 视频渲染通道，以便未来接入 E01 仪表屏投屏；
- `DiPlayActivity` 移除 DiLink 专属的 ADB 弹窗和主题切换，保留通用的仪表屏地图开闭及 CarPlayClusterDisplay 显示偏好（地图、转向卡片、缩放）。

- [ ] **Step 1: 删除 BYD 专有类**
  删除 `DiLink51ClusterMonitor.kt` 与 `DiLink51ClusterLayout.kt`。

- [ ] **Step 2: 净化 `ClusterMapPresentation.kt`**
  - 移除对 `DiLink51ClusterLayout` 的引用与自适应分屏切图分支；
  - 保留干净的通用 Presentation 视口渲染与 Surface 传递；
  - 更新 `ClusterMapPresentationTest.kt`。

- [ ] **Step 3: 净化 `CarPlayHostActivity.kt`**
  - 移除 `ensureDiLink51ClusterPresentation` 及 `clusterLayers` 多图层 Map；
  - 仪表台投屏统一收拢在标准的 `clusterPresentation` 生命周期中，待 E01 仪表屏接入时即插即用。

- [ ] **Step 4: 净化 `DiPlayActivity.kt` 设置项**
  - 移除 `DiLink51ClusterLayout.supported()` 分支下的“ADB 自动地图设置”、“仪表盘主题”与“仪表盘对比度”；
  - 保留通用的“仪表盘上的 CarPlay 地图”开关与内容选择（地图 / 转向卡片），确保 E01 后续接入仪表时无缝使用。

- [ ] **Step 5: 运行全量测试**
  ```bash
  ./gradlew testDebugUnitTest
  ```

- [ ] **Step 6: 提交代码**
  ```bash
  git commit -am "refactor(cluster): purge BYD DiLink specifics and preserve generic presentation pipeline for E01 cluster"
  ```

---

### Task 4: 彻底消除 `LocalOnlyHotspot` (API 26+) 冗余代码

**Files:**
- Delete: `shared/src/main/java/com/shilapi/xcertplay/network/LocalOnlyHotspotManager.kt`
- Delete: `shared/src/test/java/com/shilapi/xcertplay/network/LocalOnlyHotspotManagerTest.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`
- Modify: `shared/src/main/java/com/shilapi/xcertplay/orchestration/WirelessModeSelection.kt`
- Modify: `shared/src/test/java/com/shilapi/xcertplay/orchestration/WirelessModeSelectionTest.kt`

**Interfaces:**
- 移除所有对 `WifiManager.startLocalOnlyHotspot` 的引用与 `@RequiresApi(Build.VERSION_CODES.O)`；
- 在模式判定中将所有未知或高版本模式确定性回退到 `MANUAL`（E01 原生驱动级热点）。

- [ ] **Step 1: 更新单测**
  在 `WirelessModeSelectionTest.kt` 中调整测试，验证无论传入何种非 EXISTING_WIFI 模式，在 Android 5.1 (API 22) 下均确定性路由至 `MANUAL` 热点。

- [ ] **Step 2: 删除 `LocalOnlyHotspotManager.kt` 及其测试**
  物理移除无用的 Android 8+ 热点管理器。

- [ ] **Step 3: 清理 `CarPlayController.kt`**
  移除 `createLocalOnlyHotspotManager()` 及其 `@RequiresApi(Build.VERSION_CODES.O)` 注解。

- [ ] **Step 4: 运行测试验证**
  ```bash
  ./gradlew testDebugUnitTest
  ```

- [ ] **Step 5: 提交代码**
  ```bash
  git commit -am "refactor(network): remove LocalOnlyHotspot and lock wireless hotspot down to native E01 driver"
  ```

---

### Task 5: 消除 HEVC 软件解码分支，锁定硬件 H.264 (AVC) 硬解

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt:370-387`
- Modify: `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`

**Interfaces:**
- `AndroidMediaSink` 移除 `preferSoftwareHevcDecoder` 参数；
- `createDecoder(mime)` 彻底移除针对 `Build.VERSION.SDK_INT >= Q` 的 `MediaCodecList.REGULAR_CODECS` 软解查找死代码；
- 统一使用 `MediaCodec.createDecoderByType(mime)`，硬解优先。

- [ ] **Step 1: 清理 `AndroidMediaSink.kt` 解码器创建逻辑**
  删除 `preferSoftwareHevcDecoder` 属性与 `SDK_INT >= Q` 的软解码器遍历，直接调用 `MediaCodec.createDecoderByType(mime)`。

- [ ] **Step 2: 清理上层持久化与调用参数**
  - `CarPlayHostActivity.kt` 移除 `hevcSoftwareDecoderEnabled` 参数传递；
  - `AirPlayPersistence.kt` 移除 `KEY_HEVC_SOFTWARE_DECODER` 存取函数。

- [ ] **Step 3: 运行测试**
  ```bash
  ./gradlew testDebugUnitTest
  ```

- [ ] **Step 4: 提交代码**
  ```bash
  git commit -am "refactor(video): eliminate dead HEVC software decoder branch and lock to hardware codecs"
  ```

---

### Task 6: 严谨加固 E01 媒体封面与元数据管线（保留并加固 `NowPlayingArtworkQueue`）

**Files:**
- Review & Keep: `common/src/main/java/com/shilapi/xcertplay/NowPlayingArtworkQueue.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt`
- Modify: `common/src/main/res/values/strings.xml`
- Modify: `common/src/main/res/values-zh-rCN/strings.xml`
- Test: `common/src/test/java/com/shilapi/xcertplay/NowPlayingArtworkQueueTest.kt`

**Interfaces:**
- **完整保留**媒体封面图解码队列（`NowPlayingArtworkQueue`）与 `CarPlayMediaKeys` 的封面推送能力，使 E01 中控主屏与未来仪表屏能够实时渲染 CarPlay 专辑封面；
- 严谨审计与加固：
  1. **下采样与显存防护**：确保 `decodeArtwork` 严格保持 `maxDim = 384`、`Bitmap.Config.RGB_565`（单张图片内存控制在 ~280KB），在 1GB 内存下绝不触发大块连续内存分配失败；
  2. **Binder 传输防爆**：`MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, ...)` 传给系统 MediaSession 时，严格处于 Android 1MB Binder 缓冲安全限制内，永不抛出 `TransactionTooLargeException`；
  3. **生命周期安全**：会话销毁时立即触发 `artworkQueue.clear()` 并回收 Bitmap，防止 E01 内存泄漏；
  4. **文案规范化**：将设置项中原本带有“仪表盘”偏颇的 `cluster_media_metadata_title`（如“仪表盘媒体元数据”）统一明确为“车机媒体封面与元数据同步”，让用户与系统服务认知一致。

- [ ] **Step 1: 运行现存的 `NowPlayingArtworkQueueTest` 确保功能基线完好**
  ```bash
  ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.NowPlayingArtworkQueueTest
  ```

- [ ] **Step 2: 审查与加固 `CarPlayMediaKeys.kt` 封面分发安全**
  - 检查并在 `putBitmap` 前做非空及有效性校验；
  - 确保会话断开或播放停止时清空元数据，避免车机残留旧专辑封面；
  - 规范化中英文配置文案（如“车机媒体信息与封面同步”）。

- [ ] **Step 3: 运行全量测试**
  ```bash
  ./gradlew testDebugUnitTest
  ```

- [ ] **Step 4: 提交代码**
  ```bash
  git commit -am "harden(media): consolidate E01 media artwork and metadata pipeline for system and cluster display"
  ```

---

### Task 7: 全量验证与打包验收

- [ ] **Step 1: 运行全量单元测试**
  ```bash
  ./gradlew testDebugUnitTest
  ```
  确认全模块测试通过率 100%。

- [ ] **Step 2: 执行 Lint 与静态检查**
  ```bash
  ./gradlew lintDebug
  ```
  确认无任何关于 API 22 的致命报错（如 NewApi、InlinedApi 等）。

- [ ] **Step 3: 执行 Release 打包验证**
  ```bash
  ./gradlew :mobile:assembleRelease
  ```
  确认生成的 APK 编译无误，运行在 E01 车机上轻量、快速、无冗余。

---

## 阶段 1 完成后状态

* **代码纯净度**：彻底清除 AAOS 汽车总线音频、BYD DiLink 监控与分屏、LocalOnlyHotspot、HEVC 软解等非 E01 平台的历史包袱；
* **E01 特性完备性**：完整保留通用的仪表屏投屏扩展架构，完整保留 E01 车机与仪表媒体封面图展示（`NowPlayingArtworkQueue`）；
* **API 与内存安全**：彻底根除高版本 API 在 Android 5.1 (API 22) 下的崩溃隐患，媒体与音频管线内存开销降至最低。
