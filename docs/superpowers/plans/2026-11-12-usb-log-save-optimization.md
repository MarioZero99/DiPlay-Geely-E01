# U 盘保存日志性能优化与代码精简 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 U 盘保存诊断日志的耗时可以被测量，并把最大的一块可裁剪成本（当天多份日志全量读取）裁掉；同时删掉地点卡里已决定不再提供的目录树浏览。

**Architecture:** 先给导出路径埋 `PerfTrace` 阶段计时，用车上一条报告回答「时间花在组装还是写入」；再按测量结果动刀。日志源从「当天所有归档 + 当前文件」改为只读当前运行的那一份（退回最新一份非空归档）；地点卡点击设备即存到 `<卷根>/DiPlay/`，不再进入子目录漫游。正则脱敏只加可证明保守的字符预检，不改变脱敏结果。

**Tech Stack:** Kotlin, Android 5.1 / API 22, libaums 0.10.0, JUnit 4, Robolectric, Gradle `:common:testDebugUnitTest`.

**Spec:** `docs/superpowers/plans/2026-11-11-usb-log-save.md`（libaums/FAT32 直写与双轨卷识别）继续有效。本文的 2026-11-12 复审结论是：`sync`、私有备份、组装时的二次脱敏三项**保留**，原因见下。

## 复审裁定（写实现前必须知道）

- **二次脱敏保留**：写入路径 `SessionLogFile.append` 已经脱敏（`SessionLogFile.kt:30`），组装时再脱一遍（`DiPlayActivity.kt:3907`）确实重复。但 `logs/` 里的文件不保证都由当前构建写入（同一辆车当天升级、旧版本留下的归档），`DiagnosticExportUiTest:270-287` 正是用一个直接写盘的 `password=hunter2` 钉住这件事。删掉它是拿凭据外泄换几百毫秒，不做。
- **`DiagnosticExportStore.exportViaRoot` 的 `sync` 保留**：它在 `save()` 里，两个调用点都在 worker 线程（`DiPlayActivity.kt:3534`、`3670`），没有主线程阻塞；且 `sync` 失败会让整次导出判失败（`DiagnosticExportStore.kt:389-393`），删掉等于把「cp 已落盘」的校验换成「读 page cache」。libaums 直写路径根本不经过它。
- **私有备份保留**：它同时是分享 URI 和「U 盘没写进去」时的保命副本，`DiagnosticExportFallbackTest:41-53` 与 `DiagnosticExportUiTest:143` 都依赖它。「只在必要时才存」在现有契约里等于「永远要存」，不要动。
- **正则预检的量级**：本机 JVM 微基准（1.4 MB、15,000 行、真实 `Tag: message` 形状）：现有 6 条正则合计约 256 ms/1.4 MB，其中 `ipv6` 约 88 ms、`mac` 约 31 ms。原计划的 `hasColon` 守卫**无效**——每行都有 `Tag:` 冒号，守卫恒真；只用「点数」守卫收益接近 0。按「匹配的必要条件」重写后同一语料约 129 ms（−50%）；并在 32 万条随机/真实/边界字符串上与现实现做过差分，0 处不一致。见 Task 4。

## Global Constraints

- 车机环境为 Android 5.1 / API 22 (Lollipop)，MTK MT6735 芯片；不引入高版本 API，不新增外部依赖。
- `DiagnosticVolumeProbe` 的双轨卷探测（系统挂载 + libaums 兜底）不变。
- 脱敏效果与边界不退化：`DiagnosticRedactorTest` 全部断言保持绿灯；二次脱敏调用必须保留。
- `sync`、私有备份、`ExportOutcome.shareableUri` 语义不得改变。
- 行为变更必须在**同一任务**里改掉点名的测试；不允许把红测试留到下一个任务。
- 测试保持毫秒级：不再写 40,000 行夹具；每个行为只加会真红的那几条，不堆测试。
- `versionCode` / `versionName` 不自行递增；出包由用户执行 `scripts/package-geely.sh official|local`。
- 提交时只 `git add` 任务点名修改的源文件与测试。

## Review Focus

- 保存瞬间 `diplay.log` 刚被轮转成 0 字节 → 报告必须退回当天最新一份非空归档，不能是空报告。(Task 3 测)
- `logs/` 里存在非当前构建写入的行 → 组装时的二次脱敏必须仍把凭据换成 `WITHHELD`。(Task 3 测)
- 地点卡直接保存遇到不可写的挂载点 → 仍要落到私有副本 + 失败卡片，不能声称已保存。(既有 `aFailedStickKeepsTheReportAndOffersPhoneStorage`，Task 2 后必须仍在整套里通过)
- 预检边界正好落在 5 个冒号 / 7 个冒号 / 8 个连续十六进制字符上 → 地址与标识符仍必须被替换。(Task 4 测)
- 上传报告与保存报告共用 `buildCompleteReport`：日志源裁剪同时改变上传内容，不能只测保存路径。(Task 3 用 `buildCompleteReport` 直接测)

---

### Task 1: 先给导出路径埋阶段计时

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:3534-3538`（`exportToUsb` worker）
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:3670-3679`（`exportHere` worker）
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:3903-3910`（`buildDiagnosticSections`）
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt:307-308`（`saveInternalBackup`）
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt`

**Interfaces:**
- Consumes: `PerfTrace.span(name, warnAboveMs, block)`, `PerfTrace.summary()`
- Produces: 五个固定 series 名 `export/report`、`export/redact`、`export/folder-write`、`export/usb-write`、`export/backup`，由报告 header 的 `--- Performance ---` 块带出（`DiPlayActivity.kt:3858`）

- [ ] **Step 1: 写会失败的测试**

在 `DiagnosticExportUiTest` 增加一条（复用同文件的 `registerReportProvider` / `awaitText` 帮助函数）：

```kotlin
/** 保存后车轮上要能回答"慢在哪":每个导出阶段都必须有自己的 PerfTrace series。 */
@Test fun eachExportStageIsTimed() {
    val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
    val activity = controller.get()
    registerReportProvider(activity.applicationContext)
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    try {
        // exportHere 没有卡片就直接返回，所以先打开地点卡。
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
        awaitText(activity, activity.getString(R.string.export_internal_storage))
        ReflectionHelpers.callInstanceMethod<Unit>(
            activity, "exportHere",
            ReflectionHelpers.ClassParameter.from(File::class.java, downloads),
        )
        awaitText(activity, "/DiPlay/DiPlay-")
        val summary = PerfTrace.summary()
        for (name in listOf("export/report", "export/redact", "export/folder-write", "export/backup")) {
            assertTrue(summary, summary.contains(name))
        }
    } finally {
        controller.pause().stop().destroy()
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiagnosticExportUiTest.eachExportStageIsTimed"`
Expected: FAIL —— `summary` 里没有任何 `export/` 行

- [ ] **Step 3: 埋 span**

两处 worker 都从「先组装、再写入」改成显式两段：

```kotlin
val result = runCatching {
    val report = PerfTrace.span("export/report") { buildCompleteReport(appContext) }
    val outcome = PerfTrace.span("export/usb-write") {
        DiagnosticExportStore.saveToUsb(appContext, device, fileName, report)
    }
    outcome to report
}
```

`exportHere` 同形，第二段换 `"export/folder-write"` 与 `DiagnosticExportStore.save(...)`。

`buildDiagnosticSections` 里把「列文件 + 逐行脱敏 + 组装 sections」整段包进 `PerfTrace.span("export/redact") { ... }`（没有文件时也要记录，所以包在空分支之外）。

`DiagnosticExportStore.saveInternalBackup` 改为：

```kotlin
private fun saveInternalBackup(context: Context, fileName: String, report: String): Backup =
    PerfTrace.span("export/backup") {
        saveBackupIn(context, File(context.filesDir, "diagnostic-reports"), fileName, report)
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiagnosticExportUiTest.eachExportStageIsTimed"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt \
        common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt \
        common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt
git commit -m "perf(export): 为报告组装、脱敏、写入与备份加阶段计时"
```

---

### Task 2: 地点卡点击即保存，删掉目录树浏览

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:3454-3474`（`showMountList`）
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:3586-3656`（删除 `openDirectory`、`showDirectory`）
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:199`（删除 `directoryListTimeoutMillis`）
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:88`（删除仅 `openDirectory` 使用的 `AtomicBoolean` import；`2143` 行是全限定名，不受影响）
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt:154-197`（删除 `VirtualFile`、`isReadable`、`children`）
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt`

**Interfaces:**
- Consumes: `ExportFolder.directory`、`exportHere(File)`、`ExportFolderSheet.showChoices(...)`
- Produces: `showMountList` 的点击语义：`usbDevice != null -> saveUsb(folder)`，否则 `directory != null -> exportHere(folder.directory)`

- [ ] **Step 1: 先改测试（会红）**

`DiagnosticExportUiTest.savingPhoneStorageWritesUnderDownloadDiPlay`（57-78 行）删掉第二次点击，点内部存储后直接等保存结果：

```kotlin
clickRow(activity, activity.getString(R.string.export_internal_storage))
awaitText(activity, "/DiPlay/DiPlay-")
```

把 `browsingAStickShowsItsFilesAndSavesAtTheMountRoot`（80-120 行）替换为：

```kotlin
/** 地点卡点一下就是保存：写到卷根 /DiPlay，不再进入子目录。 */
@Test fun tappingAStickSavesAtTheMountRoot() {
    // 夹具与旧测试相同：cacheDir 下 usb-otg-sda1、readme.txt、Music/
    // volumeListProvider 返回该目录
    ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
    awaitText(activity, stick.name)
    clickRow(activity, stick.name)
    val body = awaitText(activity, "/DiPlay/DiPlay-")
    val saved = File(stick, "DiPlay").listFiles()!!.single { it.isFile }
    assertEquals(saved.absolutePath, body.text.lines().first { it.contains("usb-otg-sda1") })
    assertTrue(texts(activity).none { it.text == "readme.txt" })
    assertFalse(File(stick, "Music/DiPlay").exists())
    assertNotNull(texts(activity).firstOrNull { it.text == activity.getString(R.string.share) })
}
```

`DiagnosticExportStoreTest` 删除三条只为被删代码存在的用例：`childrenPutsDirectoriesFirst`（70-76）、`isReadableReturnsTrueForNormalDirectory`（228-231）、`virtualFileCorrectlyReportsDirectoryProperty`（233-241）。

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiagnosticExportUiTest.tappingAStickSavesAtTheMountRoot"`
Expected: FAIL —— 点击后仍在目录列表，`/DiPlay/DiPlay-` 等不到

- [ ] **Step 3: 改实现**

`showMountList` 的行点击改成：

```kotlin
if (folder.usbDevice != null) {
    saveUsb(folder)
} else if (folder.directory != null) {
    exportHere(folder.directory)
}
```

删除 `openDirectory`、`showDirectory`、`directoryListTimeoutMillis`、`DiagnosticExportStore.VirtualFile/isReadable/children`，以及因此不再使用的 import（只删编译器/IDE 报未使用的，`sameFile` 仍有 `3796` 行在用，保留）。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiagnosticExport*"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt \
        common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt \
        common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt \
        common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt
git commit -m "refactor(export): 地点卡选中即写入卷根 DiPlay，移除目录树浏览"
```

---

### Task 3: 报告只读当前一份日志（产品口径变更）

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt:3903-3920`（`buildDiagnosticSections`）
- Modify: `common/src/main/java/com/shilapi/xcertplay/SessionLogFile.kt:150-166`（`reportFiles` 的注释，函数行为不变）
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt`

**Interfaces:**
- Consumes: `SessionLogFile.reportFiles(File): List<File>`、`DiagnosticRedactor.redact(String): String?`、`AttemptJournal.lines(Context)`
- Produces: `DiPlayActivity.buildDiagnosticSections(Context): List<DiagnosticSection>`，日志部分**最多一份**，且仍经过 `DiagnosticRedactor.redact`

- [ ] **Step 1: 改测试（会红）**

把 `theSavedFileKeepsEveryRetainedLogLine`（257-293）替换为两条，都不再写 40,000 行：

```kotlin
/** 报告只带当前这一场；当天更早的归档留在盘上，但不进报告。 */
@Test fun theSavedFileHoldsOnlyTheCurrentRun() {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    // logs/diplay-<today>.log 写入 "HEAD-KEPT-LINE\n"
    // logs/diplay.log 直接写 "password=hunter2\nNEWER-LINE marker\n"（绕开 SessionLogFile）
    // 点内部存储 → 等 "/DiPlay/DiPlay-"
    val saved = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "DiPlay",
    ).listFiles()!!.single { it.name.startsWith("DiPlay-") && it.readText().contains("NEWER-LINE marker") }
    val text = saved.readText()
    assertTrue(text.contains("NEWER-LINE marker"))
    assertFalse(text.contains("HEAD-KEPT-LINE"))
    assertFalse(text.contains("--- diplay-$today.log ---"))
    assertTrue(text.contains(DiagnosticRedactor.WITHHELD))
    assertFalse(text.contains("hunter2"))
}

/** diplay.log 刚轮转成 0 字节时退回当天最新一份非空归档。 */
@Test fun aReportWithAnEmptyCurrentLogFallsBackToTheLatestArchiveToday() {
    // logs/diplay-<today>.log 写入 "ARCHIVE-LINE\n"；logs/diplay.log 写成空文件
    val report = ReflectionHelpers.callInstanceMethod<String>(
        activity, "buildCompleteReport",
        ReflectionHelpers.ClassParameter.from(Context::class.java, activity.applicationContext),
    )
    assertTrue(report.contains("ARCHIVE-LINE"))
    assertTrue(report.contains("--- diplay-$today.log ---"))
}
```

第二条直接调私有的 `buildCompleteReport`，同时覆盖保存与上传共用的那条路径。

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiagnosticExportUiTest.theSavedFileHoldsOnlyTheCurrentRun"`
Expected: FAIL —— `HEAD-KEPT-LINE` 仍在报告里

- [ ] **Step 3: 改实现**

```kotlin
private fun buildDiagnosticSections(appContext: android.content.Context): List<DiagnosticSection> {
    val logDir = File(appContext.filesDir, "logs")
    val current = File(logDir, "diplay.log")
    val target = if (current.isFile && current.length() > 0L) {
        current
    } else {
        SessionLogFile.reportFiles(logDir).lastOrNull { it.length() > 0L }
    }
    val logs = PerfTrace.span("export/redact") {
        target?.let { file ->
            listOf(
                DiagnosticSection(
                    name = file.name,
                    // 落盘时已脱敏；这一遍兜住非当前构建写入的行（当天升级、直接写盘），不省。
                    lines = file.useLines { lines -> lines.mapNotNull { DiagnosticRedactor.redact(it) }.toList() },
                    newest = true,
                )
            )
        } ?: emptyList()
    }
    val attempts = AttemptJournal.lines(appContext)
    return if (attempts.isEmpty()) {
        logs
    } else {
        logs + DiagnosticSection(AttemptJournal.EVENTS, attempts, newest = true)
    }
}
```

`SessionLogFile.reportFiles` 的注释改为：它列出「当天归档 + 当前文件」，导出只读其中最后一份非空的（指向 `buildDiagnosticSections`）。函数本身不动。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiagnosticExportUiTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt \
        common/src/main/java/com/shilapi/xcertplay/SessionLogFile.kt \
        common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt
git commit -m "perf(export): 报告只读当前一份日志，轮转空文件时退回当天最新归档"
```

---

### Task 4: 脱敏字符预检（按「匹配的必要条件」加，单独提交可回退）

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticRedactor.kt:21-26`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticRedactorTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `DiagnosticRedactor.redact(line: String): String?`，输出与改动前逐字节相同

- [ ] **Step 1: 写边界测试（先绿也算，这条钉的是「预检不能太狠」）**

在 `DiagnosticRedactorTest` 加：

```kotlin
/** 预检的边界：5 个冒号、7 个冒号、8 个连续十六进制字符之上都必须仍被替换。 */
@Test fun addressShapesAtThePrecheckBoundariesAreStillReplaced() {
    assertEquals("[address] only", DiagnosticRedactor.redact("C0:A6:00:29:58:0A only"))
    assertEquals("[ip] only", DiagnosticRedactor.redact("2001:db8:0:0:0:0:0:1 only"))
    assertEquals("[ip] only", DiagnosticRedactor.redact("10.0.0.1 only"))
    assertEquals("[identifier] only", DiagnosticRedactor.redact("0123456789abcdef01234567 only"))
}
```

Run: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiagnosticRedactorTest.addressShapesAtThePrecheckBoundariesAreStillReplaced"`
Expected: PASS（这就是基线，用来在 Step 3 之后复跑）

- [ ] **Step 2: 实现预检**

保持替换顺序 `mac → identifier → address → ipv6` 不变（顺序变了替换结果可能不同）：

```kotlin
private fun colonCount(line: String): Int {
    var count = 0
    for (c in line) if (c == ':' && ++count >= 7) return 7
    return count
}

private fun hasHexRun(line: String, minimum: Int): Boolean {
    var run = 0
    for (c in line) {
        if (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') {
            if (++run >= minimum) return true
        } else {
            run = 0
        }
    }
    return false
}

fun redact(line: String): String? {
    if (line.contains("TRACE ") || line.contains("PHONE ") || line.contains('\n') || line.contains('\r')) return null
    if (secret.containsMatchIn(line) || namedDevice.containsMatchIn(line)) return WITHHELD
    val colons = colonCount(line)
    var result = line
    // mac 需要 5 个冒号；ipv6 需要 "::" 或 7 个冒号；两者都是匹配的必要条件，跳过不可能命中。
    if (colons >= 5) result = result.replace(mac, "[address]")
    if (hasHexRun(line, 8)) result = result.replace(identifier, "[identifier]")
    if (line.contains('.')) result = result.replace(address, "[ip]")
    if (colons >= 7 || line.contains("::")) result = result.replace(ipv6, "[ip]")
    return result.take(700)
}
```

预检都读原始 `line`：后续替换只会用 `[address]`/`[identifier]`/`[ip]` 这类不含冒号、点号、十六进制长段（`[address]` 里只有 `a`、`d` 两个十六进制字符）的文本，所以原行上的必要条件在替换后的字符串上仍然成立。

- [ ] **Step 3: 跑脱敏全套，确认结果不变**

Run: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiagnosticRedactorTest"`
Expected: 全部 PASS（含 Step 1 新增的边界用例）。输出必须与改动前逐字节一致——同一套六条正则已在本机用 32 万条字符串做过差分（真实日志行 + 随机串 + 边界串），0 处不一致；正式判据仍以测试为准。

- [ ] **Step 4: 提交（单独一条，便于按 Task 5 的量测结果回退）**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiagnosticRedactor.kt \
        common/src/test/java/com/shilapi/xcertplay/DiagnosticRedactorTest.kt
git commit -m "perf(redact): 按匹配必要条件跳过不可能命中的地址正则"
```

---

### Task 5: 全量验证与车上量测

**Files:**
- Test: `common/src/test/java/com/shilapi/xcertplay/*`

- [ ] **Step 1: 跑 common 全部单测**

Run: `./gradlew :common:testDebugUnitTest`
Expected: 全部 PASS。若 `WifiP2pGroupManagerTest` 仍占绝大部分时间，那是既有问题，不在本计划范围。

- [ ] **Step 2: 验证编译**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 出包上车（版本号由用户定）**

用户执行：`DIPLAY_VENDOR_HOP_DIR=/Users/g/Documents/project/diplay-vendor-hop scripts/package-geely.sh official`（或 `local`），`versionCode` 由用户指定。

- [ ] **Step 4: 一枪量完（连存两次）**

1. 第一次保存到 U 盘；
2. 再保存一次，打开**第二份**报告的 `--- Performance ---` 块（header 在每次保存开始时生成，所以第一次保存的耗时出现在第二份报告里）；
3. 记录四条：`export/report`、`export/redact`、`export/usb-write`（或 `export/folder-write`）、`export/backup` 的 `p50/max`。

判读：
- `export/report + export/redact` 占大头 → 本计划的 Task 3/Task 4 方向有效；
- `export/usb-write` 占大头 → 瓶颈在 libaums/FAT32 写入，本计划不改它；Task 4 可单独回退（`git revert` 那条提交），继续裁剪只能从报告体积下手；
- 若四项加起来远小于总耗时 → 时间在 `UsbMassStorageManager.openFileSystem`/轮询或界面等待，需要另开一个计划，不要在这份里继续加码。

- [ ] **Step 5: 确认报告口径**

打开的保存文件里只有当前这一场：出现 `NEWER-LINE` 一类的当前行，不出现当天更早归档的段头 `--- diplay-<日期>.log ---`（除非当前文件为空、走了归档兜底）。
