# 车机端手动检查更新与安装设计规范 (In-App On-Demand Update Design)

## 1. 目标与背景

DiPlay 当前通过 `scripts/publish-apk.sh` 向 GitHub Releases 发布带有 hop 专用硬件通信库的官方安装包（`DiPlay-Legacy-Geely-V<xyz>.apk` 与 `SHA256SUMS.txt`）。
目前车机车主若需升级，必须通过电脑无线 ADB 或 U 盘文件浏览器手动推送覆盖安装，门槛较高。

本功能在车机端提供**纯手动触发**的检查更新与安装链路：
1. **纯手动点击**：杜绝任何开机自查、后台常驻守护或静默轮询，零驾驶干扰、零后台资源占用；
2. **直连官方 GitHub Releases API**：直接获取最新 Release 信息、官方包下载链接与 SHA256 校验和；
3. **防重复下载与完整性校验**：原子写入临时文件，基于 `SHA256SUMS.txt` 校验；已存在且 Hash 匹配的包直接就绪，无需重复下载；
4. **生命周期自洁**：App 启动或准备新下载时自动清扫历史旧包，确保车机有限空间占用不超过 10 MB；
5. **系统原生安装器确认**：下载完成后唤起 Android 原生安装器，由车主在车机屏幕上点击「安装」确认，不走高风险 root 静默安装。

---

## 2. 系统约束与运行环境

- **硬件/系统平台**：吉利 E01 车机（东软 NEUSOFT ODM），Android 5.1（API 22，Lollipop），MT6735 芯片，运行内存仅约 26~33 MB 可用，内部存储剩余约 2.8 GB。
- **编译/Target SDK**：`compileSdk = 37`, `minSdk = 19`, `targetSdk = 37`。
- **存储与 FileProvider**：`targetSdk >= 24` 时直接传递 `file://` URI 会触发 `FileUriExposedException`，必须使用 `androidx.core.content.FileProvider` 暴露 `content://` URI 并授予读权限。
- **网络约束**：GitHub API（`api.github.com`）要求必须携带有效 `User-Agent` 请求头，否则直接返回 403 Forbidden；TLS 握手需兼容 API 22（Android 5.1 原生已支持 TLS 1.2）。
- **安装包体积**：R8 裁剪后官方 release 包约 6.01 MB。

---

## 3. UI 交互与状态机设计

在系统设置页 `DiPlayActivity.systemCards`（关于卡片旁）添加独立的「软件更新」卡片。

### 3.1 状态枚举 (`UpdateUiState`)

| 状态 | 页面展示内容 | 操作按钮 | 按钮动作 |
|---|---|---|---|
| `IDLE` | 当前版本号（如 `v0.4.3-geely`） | `[检查更新]` | 发起异步版本检测 |
| `CHECKING` | 当前版本号 + "正在检查新版本..." | `[检查中...]`（置灰不可点） | 无 |
| `UP_TO_DATE` | 当前版本号 + "当前已是最新版本" | `[检查更新]` | 再次检查 |
| `UPDATE_AVAILABLE` | 发现新版本 `v0.4.4` (6.0 MB) + 更新说明 | `[下载更新]` | 开始下载更新包 |
| `DOWNLOADING` | 下载进度条 + "已下载 X.X MB / 6.0 MB (45%)" | `[取消]` | 终止下载并删除临时文件 |
| `READY_TO_INSTALL` | "新版本已就绪，点击安装" | `[立即安装]`（高亮按钮） | 调起系统安装器 |
| `ERROR` | 失败原因（如"网络连接超时"、"存储空间不足"等） | `[重试]` | 重新发起检查或下载 |

### 3.2 防重复下载状态迁移
- 当从 `CHECKING` 解析到远端有更新版本 `V` 时：
  - 立即异步检查本地 `updates/` 目录下是否已存在 `DiPlay-Legacy-Geely-V<num>.apk`；
  - 若存在且计算 SHA-256 与 Release 的 `SHA256SUMS.txt` 一致：**状态直接跳过 `UPDATE_AVAILABLE` 和 `DOWNLOADING`，直接进入 `READY_TO_INSTALL`**。

---

## 4. 核心组件与数据流设计

```
[ 用户点击 "检查更新" ]
        │
        ▼
[ UpdateChecker ] ─── GET api.github.com/repos/.../releases/latest ───► [ GitHub API ]
        │                                                                     │
        ▼                                                                     ▼
 解析 Release JSON (tag_name, body, assets: .apk & SHA256SUMS.txt) <──────────┘
        │
        ├─► 版本比对 (remoteVersionCode > currentVersionCode)
        │
        ▼
[ UpdateStorageCleaner ] ─── 检查本地 updates/ 目录 ───► 是否有完整且 Hash 一致的包？
        │                                                     │
   [无 / 不匹配]                                            [有]
        │                                                     │
        ▼                                                     ▼
 状态: UPDATE_AVAILABLE                               状态: READY_TO_INSTALL
        │                                                     │
[ 用户点击 "下载更新" ]                                [ 用户点击 "立即安装" ]
        │                                                     │
        ▼                                                     ▼
[ UpdateDownloader ]                                  [ UpdateInstaller ]
  - 写入 .apk.download                                  - FileProvider 生成 content:// URI
  - 下载 SHA256SUMS.txt                                  - Intent.ACTION_VIEW / FLAG_GRANT_READ
  - 计算 SHA256 并比对                                   - startActivity 调起车机 PackageInstaller
  - 原子重命名为 .apk                                          │
        │                                                     ▼
        ▼                                             [ 车机系统安装界面确认 ]
 状态: READY_TO_INSTALL
```

### 4.1 `UpdateChecker`
- 请求 URL: `https://api.github.com/repos/MarioZero99/DiPlay-Geely-E01/releases/latest`。
- 请求头:
  - `User-Agent: DiPlay-Geely-Updater/<currentVersionName>`
  - `Accept: application/vnd.github.v3+json`
- 资产匹配规则：
  - APK: 匹配名称以 `DiPlay-Legacy-Geely-V` 开头、且以 `.apk` 结尾，排除 `-local.apk`。
  - SHA256 校验文件: 匹配名称为 `SHA256SUMS.txt`。

### 4.2 `UpdateDownloader`
- 目标路径: `context.getExternalCacheDir()/updates/<apk_name>`（若外部存储不可用则降级至 `context.cacheDir/updates/`）。
- 写入规则:
  - 先流式写入 `<apk_name>.download` 临时文件；
  - 读取 Release 对应 `SHA256SUMS.txt` 中的 64 位十六进制 Hash；
  - 下载完成后对 `.download` 计算 SHA-256；
  - 匹配：原子重命名为 `<apk_name>`；
  - 不匹配：立即删除临时文件，抛出 `ChecksumMismatchException`。

### 4.3 `UpdateStorageCleaner`
- 触发时机：
  1. `DiPlayActivity.onCreate()` 启动后台自洁；
  2. 用户点击「下载更新」前预清理。
- 清理规则：
  - 扫描 `updates/` 目录下所有文件；
  - 凡是 `.download` 临时文件：直接删除；
  - 凡是 `.apk` 文件：
    - 调用 `context.packageManager.getPackageArchiveInfo(path, 0)` 获取包内 `versionCode`；
    - 若 `archiveInfo == null`（损坏文件）或 `archiveInfo.versionCode <= BuildConfig.VERSION_CODE`：直接删除；
    - 仅保留比当前运行 App 更高版本的合法完整包。

### 4.4 `UpdateInstaller`
- 构建安装 Intent：
  ```kotlin
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", apkFile)
  val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
  }
  context.startActivity(intent)
  ```
- 权限与 Provider 声明：
  - 在 `diagnostic_report_paths.xml` 中追加 `<external-cache-path name="updates" path="updates/" />` 与 `<cache-path name="internal_updates" path="updates/" />`；
  - 在 Manifest 中补全 `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`（保证高版本 API 模拟器与车机兼容）。

---

## 5. 异常处理与边界场景

1. **网络离线或 GitHub 超时**：
   - 捕获 `SocketTimeoutException` / `UnknownHostException` / `IOException`；
   - 友好提示「无法连接到更新服务器，请确认车机网络连接后重试」。
2. **车机存储空间不足**：
   - 下载前通过 `StatFs` 检查可用磁盘空间；若可用空间 `< 25 MB`，阻断下载并提示「车机存储空间不足（需至少 25 MB）」。
3. **下载中途熄火断电 / 用户取消**：
   - 取消标志位打断 IO 流，自动清理 `.download` 临时文件，避免留下未完成脏文件。
4. **GitHub 资产缺失**：
   - 若 Release 中未发现官方 APK 或缺少 SHA256 文件，提示「未找到适配当前硬件的更新包」，不盲目下载未知包。
