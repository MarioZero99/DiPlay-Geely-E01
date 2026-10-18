# 去掉 Wi-Fi Direct（本仓库只服务星瑞 E01）

- 日期：2026-10-17
- 状态：已执行（2026-10-18）
- 范围：这个包只跑吉利星瑞 E01（Android 5.1，API 22）。不为以后的 Android 10 车机留 Wi-Fi Direct。

## 为什么删

E01 建不了 Wi-Fi Direct 组。真正的建组路径要 Android 10（API 29）。现在选择器只在 API 29 才把 `WIFI_P2P` 放进列表，API 22 上存过的值会落成车机热点，控制器里再走到也会拒绝。车上跑不到 `WifiP2pGroupManager`。

`WifiP2pGroupManagerTest` 用影子电台把整段建组跑一遍，接口名是 `p2p-test-missing`，没有 IPv4，于是每次把 2 秒地址窗口睡满。23 条里 19 条超过 1 秒，整类约 56 秒。电台答不答应只有带 Wi-Fi Direct 的台架能答。本仓库没有台架，E01 也做不了这个台架。实现删掉之后，没有东西要补到单测里。

同网（`EXISTING_WIFI`）和车机热点（`MANUAL`）不动。`LOCAL_ONLY_HOTSPOT` 这次不碰。

## 删掉的文件

- `shared/src/main/java/com/shilapi/xcertplay/network/WifiP2pGroupManager.kt`
- `shared/src/main/java/com/shilapi/xcertplay/network/P2pStartupRecovery.kt`
- `shared/src/main/java/com/shilapi/xcertplay/network/P2pOwnership.kt`
- `shared/src/main/java/com/shilapi/xcertplay/network/P2pConfigurationMemory.kt`
- `common/src/test/java/com/shilapi/xcertplay/WifiP2pGroupManagerTest.kt`
- `shared/src/test/java/com/shilapi/xcertplay/network/P2pStartupRecoveryTest.kt`
- `shared/src/test/java/com/shilapi/xcertplay/network/P2pOwnershipTest.kt`

频道换算 `wifiFrequencyMhzToChannel` 仍给同网和热点用，`WirelessHotspotManagerTest.convertsWifiP2pFrequencyToIap2Channel` 留着。

## 改调用处

- `WirelessHotspotMode` 去掉 `WIFI_P2P`。`WirelessHotspotBackend` 去掉 `WIFI_P2P`。`UsableConnection` 去掉 `WIFI_DIRECT`。
- `CarPlayRuntimeConfig` 和 `CarPlayHostActivity` 的默认模式从 `WIFI_P2P` 改成 `MANUAL`。
- `CarPlayController` 去掉建组分支、20 秒超时和 `P2pResetRequiredException`。
- `DeviceConnectionSupport`、`WirelessModeSelection`、`WirelessReadiness`、`AirPlayPersistence` 去掉这条模式。已存的字符串 `"WIFI_P2P"` 读不到枚举时落成 `MANUAL` 并写回，旧安装不会崩。
- `DiPlayActivity` 去掉重置 Wi-Fi Direct 的入口和文案分支。英中字符串里只服务这条路的项一并删。
- 测试改断言：`ConnectionLinkCheckTest`、`WirelessModeSelectionTest`、`HotspotModeMigrationTest`、`SettingsPagesUiTest`、`WirelessModeDisplayTest`、`CarHotspotAutoStartTest`、`WirelessReadinessCheckTest`、`MfiTargetConfigTest`。其中要留一条：偏好里写着 `WIFI_P2P` 时读出来是车机热点。

## 步骤

- [x] 1. 删上面七个文件，编译错误指到剩余调用处。
- [x] 2. 按调用处改完，默认模式为车机热点。
- [x] 3. 跑 `WirelessModeSelectionTest`、`ConnectionLinkCheckTest`、`HotspotModeMigrationTest`，确认没有单测再睡那 2 秒。

## 执行记录（2026-10-18）

- 七个文件按名单删除，四个模块（common / shared / mobile / automotive）编译通过。
- 连带清掉的还有：`CarPlayStatus.Failed.wifiResetRequired` 字段、`wifiRecoveryButton` 与
  `showDiPlayHome("wireless-recovery")`、帮助弹窗里的「重置 CarPlay Wi-Fi」中按钮、
  `resetWirelessGroup()` / `closeP2pChannel()` / `handleWirelessRecovery()` / `confirmWirelessReset()`、
  `WIFI_P2P_START_TIMEOUT_MILLIS`。`AttemptRecord.reset` 保留——它是已写进 `events.jsonl` 的字段，
  `theVocabulariesArePinned` 钉住了它的位置，现在只是恒为 `false`。
- 英中各删 14 条只服务这条路的字符串；`settings_android9_compat` 改动前就已无代码引用，且同时讲
  HEVC，不在「只服务这条路」之列，未动。
- 单测：`ConnectionLinkCheckTest` 里 `android10AddsWifiDirect` 改为 `android10OffersNoMoreThanAndroid9`
  （钉住平台不再加东西）；`HotspotModeMigrationTest` 原来那条 `existingWifiDirectSelectionIsPreserved`
  反过来了，改成 `aStoredWifiDirectSelectionReadsBackAsTheCarHotspot`——计划要求留的那一条。
- 全量 `:shared:testDebugUnitTest` + `:common:testDebugUnitTest` 绿，138 个套件共 16.0 s；
  原来约 56 s 的 `WifiP2pGroupManagerTest` 已不在其中，最慢的套件是 2.23 s。
