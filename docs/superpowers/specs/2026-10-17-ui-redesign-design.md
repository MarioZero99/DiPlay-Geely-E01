# DiPlay 车机互联 UI & 交互全局重构设计规范 (Design Spec)

- **版本**: 1.2.0 (Full Architecture & Thread-Safety Edition · 零虚构实机纯净版)
- **日期**: 2026-10-17
- **目标平台**: 吉利 E01 车机（Android 4.4 / 5.1+，1920×720 @ 160dpi 超宽屏）
- **默认皮肤**: **极氪白（ZEEKR_WHITE · 先锋纯白 + 极氪高能橙点缀）**
- **可选皮肤**: 魅族白（FLYME_WHITE · 灵动纯白 + 魅族科技蓝点缀）
- **夜间模式**: 按照系统时间 **18:00 至次日 06:00** 自动切换为防眩光暗黑模式（支持手动锁定）

---

## 一、 设计总则：绝对禁止虚构功能（Strict Reality Gate）

本重构规范严格遵循：**“界面只呈现代码与硬件真实存在的功能与数据，严禁虚构任何伪状态或不可达入口”**。

### 1.1 严禁出现的虚构元素清单（零容忍）
1. ❌ **禁止虚构待机帧率/码率**：未连接或待机状态下，根本没有视频流，不展示任何伪造的“60 FPS / 12 Mbps”；
2. ❌ **禁止虚构手机电量/信号格**：当前蓝牙 iAP2 阶段未实现电池电量状态上报，不画伪造的手机电量图标；
3. ❌ **禁止虚构车机胎压/车速/能量流**：本项目为独立 Android CarPlay 接收端，不接整车 CAN 总线仪表，不画任何虚构的车辆工程数据；
4. ❌ **禁止虚构不可达的无线模式**：不画 E01 车机不支持的 Wi-Fi P2P 直连，只保留真实跑通的 `MANUAL`（车机热点）与 `EXISTING_WIFI`（同一网络）；
5. ❌ **禁止暴露包内置的 MFi 证书选项**：`certificate.p7b` 与 `identity.pk8` 已编入 APK `assets/offline-mfi/`，启动自动载入，严禁给用户提供无意义的“证书配置”。

### 1.2 现有代码功能全量映射表（真实功能 1:1 对应）

| UI 模块 | 对应的代码函数 / 字段 / 操作 | 真实物理行为 |
|---|---|---|
| **主状态指示** | `pageState().readiness`、`sessionPhase` | 映射 `Idle`（就绪/未连）、`Connecting`（握手中）、`Live`（投屏中） |
| **已发现手机** | `DiPlayPreferences.phoneAddress(this)` / `hopPhones` | 真实读取车机蓝牙配对列表中当前选定的 iPhone 名称与 MAC 地址 |
| **主按钮动作** | `connect(true)` / `openProjection()` / `disconnect()` | 未连接时发起无线连接；已连接时进入 CarPlay 全屏；运行中断开会话 |
| **次按钮动作** | `connect(false)` | 触发 USB Host 有线检测与连接（仅在检测到 USB 时可用） |
| **无线热点模式** | `WirelessHotspotMode.MANUAL` | 切换车机热点模式，提供进入系统热点设置与保存热点口令能力 |
| **同一网络模式** | `WirelessHotspotMode.EXISTING_WIFI` | 切换车机同网模式，真实读取 `joinedWifiName()` 并保存 Wi-Fi 密码 |
| **音频输出通道** | `channelSelector()` / `AirPlayPersistence.loadNavigationStreamType` | 真实切换与测试吉利 E01 原车音频通道 0~6（默认 Channel 0 混音） |
| **外观主题换肤** | 新增 `AirPlayPersistence.loadAppSkin()` / `ThemeSchedule` | 极氪白、魅族白、夜间模式（18:00-06:00 自动切换） |
| **显示与性能** | `displayCards()` | 分辨率（100%/80%/60%）、音乐缓冲（300/500/1000ms）、帧率（30/60fps）、视频编解码器（H.264/HEVC）、全屏隐藏系统栏 |
| **系统与自启** | `systemCards()` | 开机自启、向 iPhone 上报位置、屏幕调试日志、语言切换 |
| **车机固件维护** | `hopMaintenanceCards()` / `VendorCarHop` | 原厂 GOC 蓝牙跳板固件写入与劫持状态维护 |
| **诊断日志导出** | `exportDiagnostics()` | 将车机连接诊断日志导出到下载目录或本地存储 |

---

## 二、 平台与架构约束

| 维度 | 规格与硬性约束 |
|---|---|
| **目标硬件** | 吉利博越/缤越 E01 原厂车机（MTK 平台，亿咖通定制 Android 4.4 / 5.1 固件） |
| **屏幕规格** | 物理分辨率 1920 × 720 px @ 160dpi（Density = 1.0，即 1px = 1dp，有效可用区 1920×720 dp，超宽 8:3） |
| **系统兼容** | Android 4.4 (API 19) / Android 5.1 (API 22) 至 Android 8.1+ |
| **UI 实现机制** | **纯 Kotlin 代码构建原生 View（View DSL）**，继续沿用 `LinearLayout`, `TextView`, `Button`, `GradientDrawable`，不引入 Compose，不增加包体积 |
| **渲染性能** | **零高斯模糊开销**，纯用纯色、边框、与系统原生 `RippleDrawable`，确保在 MTK6735 车机上冷启动秒开、绘制 60 FPS |

---

## 三、 色彩与主题皮肤系统（Theme & Skin Engine）

系统提供 2 套白底日间高奢皮肤 + 1 套防眩光夜间暗黑皮肤，默认采用**极氪白**，并在设置中提供自动时间感应开关。

### 3.1 色板定义（Color Palettes）

```
                     ┌───────────────────┐
                     │   ThemePalette    │
                     └─────────┬─────────┘
          ┌────────────────────┼────────────────────┐
          ▼                    ▼                    ▼
   [ ZEEKR_WHITE (默认) ] [ FLYME_WHITE ]       [ DARK_MODE ]
 (极氪白 · 先锋高能橙)   (魅族白 · 灵动科技蓝)  (夜间曜黑 · 防眩光)
```

| 色值属性 | ZEEKR_WHITE (极氪白 · 默认) | FLYME_WHITE (魅族白) | DARK_MODE (夜间暗黑) |
|---|---|---|---|
| **`screenBg` (屏幕底色)** | `#F4F6FA` (雪山暖白) | `#F5F8FC` (晨曦微蓝白) | `#0B0E14` (曜石黑) |
| **`dockBg` (左侧栏底色)** | `#FFFFFF` (纯白) | `#FFFFFF` (纯白) | `#11141C` (深空灰) |
| **`cardBg` (卡片表面色)** | `#FFFFFF` (纯白浮雕) | `#FFFFFF` (纯白浮雕) | `#141923` (钛灰深色) |
| **`cardInner` (次级槽底色)** | `#F8FAFC` (极浅微灰) | `#F0F4FA` (极浅微蓝) | `#1A202C` (中灰凹槽) |
| **`cardBorder` (边缘细线)** | `rgba(0,0,0,0.06)` | `rgba(45,104,255,0.08)` | `rgba(255,255,255,0.07)` |
| **`textMain` (主标题字色)** | `#0F172A` (黑曜石深色) | `#1E293B` (深炭灰) | `#F8FAFC` (冷白) |
| **`textMuted` (次级字色)** | `#64748B` (中性石板灰) | `#64748B` (中性石板灰) | `#94A3B8` (中性冷灰) |
| **`accent` (核心强调色)** | **`#FF5E00` (极氪先锋橙)** | **`#2563EB` (魅族灵动蓝)** | `#FF5E00` 或 `#2563EB` |
| **`accentSoft` (强调柔色)** | `rgba(255,94,0,0.12)` | `rgba(37,99,235,0.12)` | `rgba(255,94,0,0.18)` |
| **`btnMainBg` (主键底色)** | `#0F172A` (极简深色大键) | `#2563EB` (高质感魅蓝) | `#FFFFFF` (高对比纯白) |
| **`btnMainFg` (主键字色)** | `#FFFFFF` | `#FFFFFF` | `#000000` |
| **`statusReady` (就绪绿)** | `#10B981` (翠绿呼吸) | `#059669` (青翠绿) | `#26D07C` (夜光绿) |

### 3.2 夜间模式自动切换调度机制
1. **时间判据**：
   ```kotlin
   fun isNightTime(calendar: Calendar = Calendar.getInstance()): Boolean {
       val hour = calendar.get(Calendar.HOUR_OF_DAY)
       return hour >= 18 || hour < 6
   }
   ```
2. **主题解析器 (`resolveEffectiveTheme`)**：
   - 若用户配置为 `AppThemeMode.AUTO`（系统默认）：
     - 夜间（18:00-06:00）自动返回 `DARK_MODE`；
     - 白昼（06:00-18:00）返回用户偏好皮肤（默认 `ZEEKR_WHITE`，若选过魅族白则返回 `FLYME_WHITE`）。
   - 若用户手动强制指定某皮肤：直接采用指定皮肤，不随时间切换。
3. **时钟感应监听**：
   - 注册系统 `Intent.ACTION_TIME_TICK`（系统每分钟回调），跨越 06:00 或 18:00 阈值时触发 Activity 平滑重绘（`render()`）。
   - Activity `onResume()` 时主动检查一次时间，从后台返回即刻校准。

---

## 四、 页面布局与真实功能映射（1920×720）

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 1920 × 720 全景宽屏布局（所有数据 100% 对应代码真实状态）                             │
├────────────┬────────────────────────────────────┬──────────────────────────────────────┤
│ Slim Dock  │ Hero 核心互联主舞台 (58% 宽度)      │ 功能快捷卡片网格 (42% 宽度)           │
│ (宽 82dp)  │                                    │                                      │
│            │  ┌──────────────────────────────┐  │  ┌────────────────────────────────┐  │
│ [主页]     │  │ 状态: 就绪/连接中/会话中     │  │  │ 模块 1: 真实无线模式二选一拨盘 │  │
│            │  │ 设备: 真实读取配对手机名     │  │  │ [ 车机热点(推荐) | 同一网络 ] │  │
│ [音频]     │  │ 图腾: 原生矢量微动效         │  │  │ (带真实热点设置/密码配置按钮)  │  │
│            │  ├──────────────────────────────┤  │  └────────────────────────────────┘  │
│ [设置]     │  │ [ 进入/断开 CarPlay 按钮 ]   │  │  ┌────────────────────────────────┐  │
│            │  │ [ USB 有线连接 (检测存在时) ] │  │  │ 模块 2: 原车音频通道直通 (0~6) │  │
│ [退出]     │  └──────────────────────────────┘  │  │ [ Channel 0 (真实测试点播) ]   │  │
│            │                                    │  └────────────────────────────────┘  │
│            │                                    │  ┌────────────────────────────────┐  │
│            │                                    │  │ 模块 3: 高级设置与极客维护抽屉 │  │
│            │                                    │  │ [ 展开真实显示/音频/系统维护 ] │  │
│            │                                    │  └────────────────────────────────┘  │
└────────────┴────────────────────────────────────┴──────────────────────────────────────┘
```

### 4.1 左侧 Slim Dock（常驻近主驾驶位）
* **宽度**：固定 82dp，铺满纵向 720px。
* **按键与行为**：
  * **主页键**：处于主页时高亮，点击刷新或返回主舞台。
  * **音频键**：点击触发右侧滑出音频通道选择卡（直接对应 `channelSelector()`）。
  * **设置键**：点击触发右侧滑出全功能设置抽屉（对应原设置页各桶）。
  * **退出键**：点击调用现有 `closeTheApp()`，释放 mDNS 5353 端口、释放车机蓝牙并优雅结束进程。

### 4.2 Hero 核心互联主舞台（占宽 58%）
* **组成要素与真实代码绑定**：
  1. **状态标识胶囊**：
     - 当 `sessionPhase == Idle`：绿光呼吸，显示 `CARPLAY 无线就绪`；
     - 当 `sessionPhase == Connecting`：橙光闪烁，显示 `正在建立会话...`；
     - 当 `sessionPhase == Live`：常亮绿光，显示 `CARPLAY 投屏运行中`；
     - 当存在前置条件缺失（`gaps.isNotEmpty()`）：显示黄光警示及具体缺项说明（对应 `gapsCard`）。
  2. **设备元数据**：
     - 调用现有 `DiPlayPreferences.phoneAddress(this)` 与 `hopPhones`，真实展示选定的 iPhone（如 `已配对：iPhone 15 Pro`）；若未选定，显示 `请在下方选择已配对手机`。
  3. **数字化能量环**：纯原生细线矢量圆环微旋转动效，无任何假数据。
  4. **主控双按键组**：
     - **主按键（高 52dp）**：
       - 未连接时：文本为 `进入 CARPLAY 投屏`，点击执行 `connect(true)`；
       - 已连接时：文本为 `打开投屏画面`，点击执行 `openProjection()`；
       - 会话运行中同时提供 `断开连接` 按钮（调用 `CarPlayBackgroundSession.stop()`）。
     - **次按键（高 52dp）**：
       - `USB 有线连接`：只有在系统探针发现 USB 支持时（`UsableConnection.USB in connectionReport().usable`）才高亮可点，点击调用 `connect(false)`。

### 4.3 右侧快捷功能卡片（占宽 42%）
严格对应代码里真实存在的功能：
* **模块 1：无线互联拓扑模式（真实二选一拨盘）**：
  * 对应 `AirPlayPersistence.loadWirelessHotspotMode(this)` 与 `applyWirelessLink(mode)`：
    1. **车机热点（MANUAL）**：
       - 显示热点配置状态与真实 SSID（`hotspotSsid()`）；
       - 提供按钮 `打开车机热点系统设置`（调用 `openCarWifiSettings()`）；
       - 提供按钮 `修改热点名称/密码`（调用 `editHotspotCredentials()`）；
       - 提供开关 `自动开启车机热点`（对应 `AirPlayPersistence.loadAutoCarHotspot`）。
    2. **同一网络（EXISTING_WIFI）**：
       - 显示车机当前真实加入的 Wi-Fi 名称（`joinedWifiName()`）；
       - 提供按钮 `打开车机 Wi-Fi 设置`（调用 `openCarClientWifiSettings()`）；
       - 提供按钮 `输入/更改此 Wi-Fi 密码`（调用 `askExistingWifiCredentials`）。
* **模块 2：原车音效输出路由（真实代码通道）**：
  * 对应 `AirPlayPersistence.loadNavigationStreamType(this)`；
  * 展示当前选定的通道（吉利 E01 默认为 Channel 0 原生直通）；
  * 提供快捷进入通道试听与选择弹层（对应 `channelSelector()` 中 0~6 数字点播测试与保存）。
* **模块 3：高级设置与极客诊断抽屉入口**：
  * 点击平滑从右侧滑入设置面板，涵盖全部次高频选项。

### 4.4 高级设置与极客维护抽屉（Half-screen Slide Drawer）
全部收拢原 `settings(content)` 中真实存在的配置项：
1. **座舱外观与皮肤**：
   - 极氪白（默认）、魅族白、夜间模式、自动（18:00-06:00 时间调度）。
2. **显示与性能配置（对应原 `displayCards`）**：
   - `分辨率比例`：原生 100% / 80% / 60%（对应 `loadDisplayScaleTenths`）；
   - `音频缓冲`：300ms / 500ms(默认) / 1000ms（对应 `loadMediaBufferMillis`）；
   - `投屏帧率`：30 FPS / 60 FPS（对应 `loadFps`）；
   - `视频编解码器`：H.264 / H.265 HEVC（对应 `loadHevcEnabled`）；
   - `全屏隐藏系统栏`：开/关（对应 `loadHideTopBar`）；
   - `右舵模式`：开/关（对应 `loadRightHandDrive`）；
   - `直接视频渲染表面`：开/关（对应 `loadDirectSurfaceView`）；
   - `屏幕物理尺寸基准与宽度 mm`（对应 `loadPhysicalSizeBasis` / `loadWidthPhysicalMm`）。
3. **系统与自动化配置（对应原 `systemCards`）**：
   - `开机自启`：开/关（对应 `loadAutoStartOnBoot`）；
   - `向 iPhone 上报位置`：开/关（对应 `loadLocationReportingEnabled`）；
   - `屏幕调试日志`：开/关（对应 `loadDebugLogsEnabled`）；
   - `应用语言`：中文 / 英文（对应 `AppLocale.showPicker`）；
   - `权限与连接帮助`：打开系统应用详情权限页。
4. **车机固件与极客诊断维护（对应原 `hopMaintenanceCards` 与 `diagnostics`）**：
   - 真实读取当前原厂 GOC 蓝牙跳板状态（`hopMaintenance`）；
   - 提供固件操作按钮（劫持/还原/安装），带真实确认弹窗（`confirmHopMaintenance`）；
   - 提供诊断日志导出按键（`exportDiagnostics()`，导出至本地文件）；
   - 若包带实验性上传则保留上传按钮（`uploadGeneratedReport()`）；
   - 展示版本号与开源声明。

---

## 五、 数据结构与持久化改动

### 5.1 主题枚举与持久化 (`AirPlayPersistence.kt`)
```kotlin
enum class AppSkin(val key: String) {
    ZEEKR_WHITE("zeekr_white"),
    FLYME_WHITE("flyme_white"),
    NIGHT_DARK("night_dark")
}

enum class ThemeSchedule(val key: String) {
    AUTO_TIME("auto_time"), // 18:00 - 06:00 为夜间
    MANUAL("manual")
}
```
* 持久化字段：
  * `app_skin`: 存储选定的白昼皮肤（默认 `ZEEKR_WHITE`）；
  * `theme_schedule`: 存储主题调度策略（默认 `AUTO_TIME`）。

---

## 六、 动效与微交互规范

1. **触控微缩放（Press Micro-scale）**：
   * 所有卡片和按钮在 `ACTION_DOWN` 时平滑缩小至 `0.98f`，在 `ACTION_UP` 或 `ACTION_CANCEL` 时以 150ms 弹性回弹恢复 `1.0f`。
2. **呼吸指示灯（Alive Breathing Glow）**：
   * 就绪状态光点采用 2.5 秒周期的 Alpha 渐变动效（`0.6f` 至 `1.0f` 往复），避免刺眼闪烁。
3. **分段拨盘滑动（Pill Switch Transition）**：
   * 在「车机热点」与「同一网络」切换时，选中的白色滑块使用属性动画横向平移，提升机械精致感。
4. **抽屉滑入滑出（Drawer Translation）**：
   * 设置面板从屏幕右侧 `translationX = width` 平滑滑入至 `translationX = 0`，耗时 220ms，插值器采用 DecelerateInterpolator。

---

## 七、 实施自检清单（Spec Self-Review）

1. **无虚构功能检查**：全篇逐一核对 `DiPlayActivity.kt`，所有暴露出的按钮和状态均有现存的真实底层实现，无任何占位伪功能；
2. **无占位符（Placeholder Scan）**：文档中无任何 `TBD`、`TODO` 或未决项目；
3. **范围可控（Scope Check）**：重构严格聚焦于表现层 UI 与交互组件重写，不破坏底层的 CarPlay 会话状态机（`CarPlayController`）与 GOC 蓝牙通讯协议。

---

## 八、 线程模型、互锁防护与后台进程管控机制 (Threading & Process Management)

吉利 E01 车机硬件基于老旧 MTK 四核芯片，I/O 与进程切换开销相对敏感。为确保 UI 改造后绝对丝滑、零 ANR、零死锁，必须严格建立清晰的线程边界与互锁防范机制。

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 主线程 (UI / Main Looper)                                                              │
│  - 纯净职责: 视图测量、渲染重绘、触控响应、按键微缩放动效                                │
│  - 严禁行为: 严禁直接执行文件 I/O、禁止同步等待 Root 进程、禁止同步探测套接字              │
└───────────────────────────▲────────────────────────────────┬───────────────────────────┘
                            │ runOnUiThread / Handler.post   │ 异步提交 (非阻塞)
                            │ 串行投递变更                    ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 后台协同层 (Managed Workers & Background Controllers)                                  │
│  - 互斥防重入锁: AtomicBoolean(isConnecting) 彻底阻断快速连击引发的 SPP 端口竞争        │
│  - 模式切换排他: 切换无线模式时，前一模式未完成释放前，后一模式串行排队                  │
│  - 生命周期守卫: 任务完成回调前严格校验 if (isFinishing || isDestroyed) 杜绝泄漏与 NPE   │
│  - 退出时资源释放: closeTheApp() 依次解除 mDNS 监听、归还车机蓝牙、再优雅退出           │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 8.1 主线程职责单一与绝对非阻塞
1. **主线程纯净原则**：主线程仅负责组件状态刷新、动画调度、用户交互响应。
2. **零同步 I/O**：
   - 任何涉及 `AirPlayPersistence` 磁盘存储、`/system` 固件状态读取、网络接口探测、日志文件生成的逻辑，必须异步分发至后台工作线程；
   - 主线程更新操作先在内存状态中完成即时 UI 反馈（如开关切换立即打钩），后台静默落盘。

### 8.2 并发互锁防护与防重入机制 (Concurrency & Anti-Interlocking)
1. **连接动作原子防重入 (`AtomicBoolean isConnecting`)**：
   - 驾驶员点击「进入 CARPLAY 投屏」时，主线程**微秒级**将主按钮置为 Loading 禁用态，并在内存层锁定 `isConnecting` 标志；
   - 彻底阻断快速连续猛击导致底层 GOC SPP 套接字或 AirPlay 端口（5353/7000/7100）出现多会话冲突死锁；
   - 待底层 `CarPlayController` 抛错、取消或正式建立会话后，方可在主线程解除锁定。
2. **无线拓扑切换排他锁 (`WirelessSwitchMutex`)**：
   - 驾驶员在「车机热点」与「同一网络」之间来回拨动时，网络底层需要经历“注销原有广播 → 释放 AP/Wi-Fi 接口 → 绑定新接口 → 重新配置路由”；
   - 严禁前一个模式正在释放时后一个模式强行并发启动。引入串行化队列：模式切换请求排队执行，切换期间界面拨块展示微转圈状态，直至底层彻底移交控制权。
3. **会话运行中变更的互锁保护 (`applyConnectionSetting`)**：
   - 严格继承既有的会话状态三态判据（`SessionPhase.Live`）：在投屏进行中修改涉及连接模式的底层设置时，强制先安全终止 CarPlay 会话，待套接字完全回收后安全保存，避免数据传输途中遭遇底层连接强制重建引发死锁。

### 8.3 后台进程与异步任务生命周期管控
1. **消除孤立野线程**：
   - 原代码中存在的零散 `Thread { ... }.start()` 统一收敛为命名守护任务（如 `car-hop-worker`、`diag-export-worker`）；
   - 在任务向主线程回传数据前，必须统一执行生命周期安全检查：
     ```kotlin
     runOnUiThread {
         if (isFinishing || isDestroyed) return@runOnUiThread
         // 安全刷新 UI
     }
     ```
2. **退出时的确定性资源析构**：
   - 当点击 Dock 的退出键执行 `closeTheApp()` 时：
     1. 首先向正在运行的后台轮询（如 `maintenanceTick`、`tick`）发出中断信号；
     2. 调用 `releaseProcessWirelessResources()` 归还车机系统蓝牙；
     3. 释放 mDNS 占用的 5353 端口；
     4. 安全终止 `CarPlayBackgroundSession`，最后才结束自身进程。绝对不留下占死蓝牙端口或无响应的僵尸进程。
