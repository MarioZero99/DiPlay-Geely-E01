# 车机端手动检查更新与安装实施计划 (In-App On-Demand Update Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为吉利 E01 车机端 DiPlay App 引入纯手动触发的检查更新与安装功能。车主在设置页手动点击检查，应用通过 GitHub Releases API 检索官方包、防重下载、SHA-256 校验、冷启动自洁，并通过系统原生安装器确认安装。

**Architecture:** 
1. **存储与清理层 (`UpdateStorageCleaner`)**：管理 `updates/` 目录；冷启动与下载前检查并清理 `versionCode <= 当前运行 App` 的历史包以及 `.download` 碎片；检测可用磁盘空间；
2. **检测层 (`UpdateChecker`)**：轻量 HTTP 客户端请求 GitHub Releases API，提取 Release 资产中的 official APK 与 `SHA256SUMS.txt`，比较版本号；
3. **下载与校验层 (`UpdateDownloader`)**：流式写入 `.download` 临时文件，支持进度回调与中断取消；计算 SHA-256 并与校验和比对，通过后原子重命名；
4. **系统安装层 (`UpdateInstaller`)**：通过 `FileProvider` 生成 `content://` URI，组装 `ACTION_VIEW` 附带 `FLAG_GRANT_READ_URI_PERMISSION` 拉起车机原生安装器；
5. **UI 控制层 (`UpdateCardController`)**：驱动设置页中的版本更新卡片，严格在主线程与后台工作线程间通信，管理 `IDLE -> CHECKING -> UPDATE_AVAILABLE -> DOWNLOADING -> READY_TO_INSTALL` 状态机。

**Tech Stack:** Android SDK (API 22/Target 37), Kotlin, JUnit 4, Robolectric, HttpURLConnection.

**Spec:** `docs/superpowers/specs/2026-10-19-in-app-update-design.md`

## Global Constraints
- 纯手动触发：严禁任何开机自动网络请求、后台定时轮询或静默下载；
- 存储严控：整个 `updates/` 目录最多只保留 1 个有效更新包，占用空间 ≤ 10 MB；
- 内存敏感：所有网络下载与哈希计算必须采用定长缓冲流（8KB buffer），严禁全量读入内存；
- 多语言对齐：新增的所有用户可见文案必须在 `values/strings.xml` 与 `values-zh-rCN/strings.xml` 严格成对提供。

---

### Task 1: 存储管理与启动自洁 (`UpdateStorageCleaner`)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/update/UpdateStorageCleaner.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/update/UpdateStorageCleanerTest.kt`

**Interfaces:**
- Produces: `class UpdateStorageCleaner(private val context: Context)`
  - `fun getUpdateDir(): File`
  - `fun getInstalledVersionCode(): Int`
  - `fun hasAdequateFreeSpace(requiredBytes: Long = 25 * 1024 * 1024): Boolean`
  - `fun cleanOldUpdates(currentVersionCode: Int = getInstalledVersionCode()): Int`
  - `fun findReadyApk(targetVersionCode: Int, expectedSha256: String?): File?`

- [ ] **Step 1: Write the failing tests**

创建 `UpdateStorageCleanerTest.kt`，编写以下测试用例：
1. `cleanOldUpdatesDeletesTmpDownloadFiles`：验证残留的 `.download` 文件被直接删除；
2. `cleanOldUpdatesDeletesOlderOrEqualVersionApks`：构造模拟的旧版本 APK 文件（或通过 mock/file 标识），验证 `versionCode <= current` 的包被清除；
3. `hasAdequateFreeSpaceReturnsTrueWhenSufficient`：验证磁盘空间检测逻辑正确；
4. `findReadyApkReturnsFileWhenShaMatches`：验证本地已存在且 SHA256 匹配的 APK 能被成功检测出。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.update.UpdateStorageCleanerTest`
Expected: FAIL (类不存在)。

- [ ] **Step 3: Implement `UpdateStorageCleaner`**

实现 `UpdateStorageCleaner.kt`：
- `getUpdateDir()`：优先使用 `context.getExternalFilesDir("updates")` 或 `File(context.externalCacheDir, "updates")`，回退至 `File(context.cacheDir, "updates")`；
- `cleanOldUpdates()`：扫描目录，删除以 `.download` 结尾的文件；对每个 `.apk` 文件通过 `context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)` 读取 `versionCode`，若 `<= currentVersionCode` 或解析失败，调用 `file.delete()`；
- `hasAdequateFreeSpace()`：通过 `StatFs(dir.path).availableBytes` 校验是否大于阈值；
- `findReadyApk()`：检查目标文件存在且非空，若提供了 `expectedSha256` 则计算本地文件 SHA256 比对。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.update.UpdateStorageCleanerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add common/src/main/java/com/shilapi/xcertplay/update/UpdateStorageCleaner.kt common/src/test/java/com/shilapi/xcertplay/update/UpdateStorageCleanerTest.kt
git commit -m "feat(update): implement update storage directory management and auto-cleanup"
```

---

### Task 2: GitHub Releases 检测器 (`UpdateChecker`)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/update/UpdateChecker.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/update/UpdateCheckerTest.kt`

**Interfaces:**
- Produces: `data class ReleaseAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)`
- Produces: `data class ReleaseInfo(val tagName: String, val versionName: String, val versionCode: Int, val notes: String, val apkAsset: ReleaseAsset, val sha256Asset: ReleaseAsset?)`
- Produces: `class UpdateChecker(private val repoOwner: String = "MarioZero99", private val repoName: String = "DiPlay-Geely-E01")`
  - `fun parseReleaseJson(jsonString: String): ReleaseInfo?`
  - `fun checkLatestRelease(currentVersionCode: Int, timeoutMs: Int = 10000): CheckResult`
  - `sealed class CheckResult { data class UpdateFound(val info: ReleaseInfo) : CheckResult(); object UpToDate : CheckResult(); data class Error(val message: String) : CheckResult() }`

- [ ] **Step 1: Write the failing tests**

创建 `UpdateCheckerTest.kt`：
1. `parseReleaseJsonExtractsOfficialApkAndSha256`：传入真实 GitHub Releases JSON 样例，验证成功解析 tag、notes、官方包 `DiPlay-Legacy-Geely-V043.apk` 及 `SHA256SUMS.txt`，并滤掉 `-local.apk`；
2. `parseReleaseJsonRejectsReleaseWithoutOfficialApk`：无官方包时返回 null；
3. `checkResultIdentifiesUpToDateWhenVersionMatchesOrLower`：当远端 `versionCode <= currentVersionCode` 时返回 `UpToDate`。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.update.UpdateCheckerTest`
Expected: FAIL。

- [ ] **Step 3: Implement `UpdateChecker`**

实现 `UpdateChecker.kt`：
- 使用 `org.json.JSONObject` 解析 GitHub API 返回的 JSON 结构；
- 遍历 `assets` 数组：
  - 寻找 `name` 符合 `DiPlay-Legacy-Geely-V.*\.apk` 且不包含 `local` 的资产作为 `apkAsset`；
  - 寻找 `name == "SHA256SUMS.txt"` 的资产作为 `sha256Asset`；
- 版本号解析：从 `tagName`（如 `v0.4.3-geely`）或 `apkAsset.name` 中提取数值生成版本号（如 `0.4.3` -> 43）；
- `checkLatestRelease()`：使用 `HttpURLConnection` 连接 `https://api.github.com/repos/$repoOwner/$repoName/releases/latest`；
  - 设置 `User-Agent: DiPlay-Updater`；
  - 设置 `ConnectTimeout` 与 `ReadTimeout` 为 10 秒；
  - 捕获网络异常转译为友好错误信息。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.update.UpdateCheckerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add common/src/main/java/com/shilapi/xcertplay/update/UpdateChecker.kt common/src/test/java/com/shilapi/xcertplay/update/UpdateCheckerTest.kt
git commit -m "feat(update): implement GitHub release checker and asset parser"
```

---

### Task 3: 下载与 SHA-256 校验器 (`UpdateDownloader`)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/update/UpdateDownloader.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/update/UpdateDownloaderTest.kt`

**Interfaces:**
- Produces: `class UpdateDownloader(private val timeoutMs: Int = 15000)`
  - `fun downloadSha256(sha256Url: String, apkFileName: String): String?`
  - `fun downloadAndVerify(apkUrl: String, targetFile: File, expectedSha256: String?, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit, isCancelled: () -> Boolean): DownloadResult`
  - `sealed class DownloadResult { data class Success(val file: File) : DownloadResult(); object Cancelled : DownloadResult(); data class Failure(val error: String) : DownloadResult() }`

- [ ] **Step 1: Write the failing tests**

创建 `UpdateDownloaderTest.kt`：
1. `parseExpectedSha256FromSumsFile`：验证从 `SHA256SUMS.txt` 格式内容（`<hash>  <filename>`）中精准截取目标 APK 的 64 位 SHA256 字符串；
2. `downloadComputesCorrectSha256`：使用本地 mock 输入流写入临时文件并计算 SHA256，匹配成功重命名为目标文件；
3. `downloadDeletesTempFileWhenChecksumMismatch`：当计算 hash 与预期不符时，断言抛错/返回 Failure 并确保临时 `.download` 文件被彻底删除；
4. `downloadRespectsCancellation`：测试中途取消时立即停止并清理未完成碎片。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.update.UpdateDownloaderTest`
Expected: FAIL。

- [ ] **Step 3: Implement `UpdateDownloader`**

实现 `UpdateDownloader.kt`：
- 计算 SHA-256：使用 `MessageDigest.getInstance("SHA-256")`；
- 下载循环：8KB 缓冲区分块读取；每次读取更新 `MessageDigest` 并写入 `FileOutputStream`；
- 检查 `isCancelled()`：若为 true 则在 finally 块中关闭流并删除临时文件；
- 校验阶段：若 `expectedSha256 != null`，对比十六进制摘要；一致则 `tmpFile.renameTo(targetFile)`。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.update.UpdateDownloaderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add common/src/main/java/com/shilapi/xcertplay/update/UpdateDownloader.kt common/src/test/java/com/shilapi/xcertplay/update/UpdateDownloaderTest.kt
git commit -m "feat(update): implement streaming downloader with atomic sha256 verification"
```

---

### Task 4: 原生安装拉起与 Provider 适配 (`UpdateInstaller`)

**Files:**
- Modify: `common/src/main/res/xml/diagnostic_report_paths.xml`
- Modify: `common/src/main/AndroidManifest.xml`
- Create: `common/src/main/java/com/shilapi/xcertplay/update/UpdateInstaller.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/update/UpdateInstallerTest.kt`

**Interfaces:**
- Produces: `object UpdateInstaller`
  - `fun createInstallIntent(context: Context, apkFile: File): Intent`
  - `fun install(context: Context, apkFile: File): Result<Unit>`

- [ ] **Step 1: Write the failing tests**

创建 `UpdateInstallerTest.kt`：
1. `createInstallIntentSetsCorrectActionAndFlags`：断言返回的 Intent 包含 `Intent.ACTION_VIEW`、`Intent.FLAG_ACTIVITY_NEW_TASK`、`Intent.FLAG_GRANT_READ_URI_PERMISSION`，且 MIME 类型为 `application/vnd.android.package-archive`；
2. `createInstallIntentUsesContentUri`：断言生成的 URI 属于 FileProvider 规范的 `content://` 协议。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.update.UpdateInstallerTest`
Expected: FAIL。

- [ ] **Step 3: Modify XMLs and implement `UpdateInstaller`**

1. 修改 `diagnostic_report_paths.xml`，增加对 `updates/` 目录的授权映射：
   ```xml
   <external-cache-path name="updates" path="updates/" />
   <cache-path name="internal_updates" path="updates/" />
   ```
2. 修改 `common/src/main/AndroidManifest.xml`，增加 `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`；
3. 实现 `UpdateInstaller.kt`：
   - 使用 `FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", apkFile)`；
   - 组装并返回带权限 Flag 的 Intent；
   - `install()` 方法执行 `context.startActivity(intent)`。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.update.UpdateInstallerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```sh
git add common/src/main/res/xml/diagnostic_report_paths.xml common/src/main/AndroidManifest.xml common/src/main/java/com/shilapi/xcertplay/update/UpdateInstaller.kt common/src/test/java/com/shilapi/xcertplay/update/UpdateInstallerTest.kt
git commit -m "feat(update): configure FileProvider paths and package install intent launcher"
```

---

### Task 5: UI 状态机与设置页卡片集成

**Files:**
- Modify: `common/src/main/res/values/strings.xml`
- Modify: `common/src/main/res/values-zh-rCN/strings.xml`
- Create: `common/src/main/java/com/shilapi/xcertplay/update/UpdateCardController.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/update/UpdateCardControllerTest.kt`

**Interfaces:**
- Produces: `class UpdateCardController(private val context: Context, private val cleaner: UpdateStorageCleaner, private val checker: UpdateChecker, private val downloader: UpdateDownloader, private val onStateChanged: (State) -> Unit)`
  - `fun check()`
  - `fun download()`
  - `fun cancel()`
  - `fun install()`

- [ ] **Step 1: Define strings in both languages**

在 `values/strings.xml` 与 `values-zh-rCN/strings.xml` 中增加更新相关词条：
- `check_for_updates`: "Check for updates" / "检查更新"
- `checking_updates`: "Checking for updates..." / "正在检查新版本..."
- `already_latest_version`: "Already on the latest version" / "当前已是最新版本"
- `new_version_found`: "Found new version %1$s" / "发现新版本 %1$s"
- `download_update`: "Download Update (%1$s)" / "下载更新 (%1$s)"
- `downloading_update`: "Downloading update... %1$d%%" / "正在下载更新... %1$d%%"
- `install_now`: "Install Now" / "立即安装"
- `cancel_download`: "Cancel" / "取消"
- `update_error`: "Update check failed: %1$s" / "更新失败：%1$s"
- `insufficient_storage`: "Insufficient storage for update" / "车机存储空间不足"

- [ ] **Step 2: Write failing tests for `UpdateCardController`**

创建 `UpdateCardControllerTest.kt`，验证状态转移：
1. `initialStateIsIdle`：初始态为 IDLE；
2. `checkTransitionsToCheckingThenUpToDate`：无新版时切入 UP_TO_DATE；
3. `checkTransitionsToReadyToInstallIfLocalApkMatches`：本地已有安装包时直接进入 READY_TO_INSTALL；
4. `downloadUpdatesProgressAndReachesReadyToInstall`：下载成功后状态切为 READY_TO_INSTALL。

- [ ] **Step 3: Implement `UpdateCardController` and integrate into `DiPlayActivity`**

1. 编写 `UpdateCardController.kt`，处理后台线程任务调度与回调主线程；
2. 在 `DiPlayActivity.systemCards` 的「关于」卡片上方或内部挂载更新卡片视图：
   - 包含版本文本显示、动态主按钮（检查/下载/安装）、以及进度条布局；
3. 在 `DiPlayActivity.onCreate` 中启动后台一次性调用 `UpdateStorageCleaner(this).cleanOldUpdates()`。

- [ ] **Step 4: Run tests to verify**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.update.*`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```sh
git add common/src/main/res/values/strings.xml common/src/main/res/values-zh-rCN/strings.xml common/src/main/java/com/shilapi/xcertplay/update/UpdateCardController.kt common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt common/src/test/java/com/shilapi/xcertplay/update/UpdateCardControllerTest.kt
git commit -m "feat(update): integrate on-demand update card into system settings with full state machine"
```

---

### Task 6: 完整构建验证与端到端无损检查

**Files:**
- Test: All tests across `:common`, `:mobile`, `:shared`

- [ ] **Step 1: Run complete test suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL with 0 failures.

- [ ] **Step 2: Run release packaging verification (hop verification)**

Run: `scripts/publish-apk.sh --dry-run` 或 `./gradlew :mobile:assembleRelease`
验证 APK 包含正确的 FileProvider 与更新资源，未破坏原有 hop 接线与 R8 裁剪规则。

- [ ] **Step 3: Final Commit & Document wrap-up**

```sh
git status
```
确认工作区干净，所有改动均经过单元测试覆盖。
