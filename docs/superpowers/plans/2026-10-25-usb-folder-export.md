# 导出前先看目录 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保存诊断报告时，先列出能读的挂载点，点进去看到该目录里的文件和文件夹，确认后再保存。报告只写到这次点开的挂载点根下的 `DiPlay/`。

**Architecture:** 不抄 CX 文件管理器的代码。仓库里没有它的源码，它的 U 盘界面还连着列表适配器、图标、搜索、多选和网盘。借鉴的是三条行为，发现挂载点和写入仍放在现有的 `DiagnosticVolumeProbe` 与 `DiagnosticExportStore`。浏览和保存结果用一张卡片，样子跟 `ConfirmSheetView` 相同，不用系统 `AlertDialog`。旧的「每个卷、下载目录、包目录都写一份」整段删掉。

**Tech Stack:** Kotlin，自绘卡片（`ThemePalette`、`ConfirmSheetView` 那一套），minSdk 19，星瑞 E01 为 API 22。测试用 Robolectric，在 `DiPlay-Legacy-Android-Geely` 里跑 `:common:testDebugUnitTest`。

**Spec:** 本文件「借鉴什么」和「行为」。不另写设计文档。2026-10-17 的报告导出设计已过时，不要按它的「静默写包目录 / 写所有卷」实现。

## Global Constraints

- 代码保持短。不新加 Activity，不加文件管理器控件，不走系统文档选择器，不反编译、不粘贴 CX 的类。
- 这一路的界面跟当前座舱同一套皮肤。颜色只用 `ThemePalette`，圆角、遮罩、强调色细条、按钮按下效果跟 `ConfirmSheetView` 和 `pressable` 一致。不用系统对话框的灰底和标题栏。白皮肤和夜色都走调色板，不写死一套颜色。
- 列目录和写文件都在后台线程，名字用现有的 `diplay-export` 与新的 `diplay-export-list`。
- 用户点保存之前，不创建 `DiPlay` 目录，不写 `.probe` 文件。
- `/mnt/media_rw` 这个空父目录继续跳过，来源 5 也不能把它加回去。`/mnt/media_rw/usbotg-sda1` 这种子挂载点保留。
- `list()` 返回 null 的目录不放进挂载点列表。同一文件夹名有两条路径时，留下不在 `/mnt/media_rw/` 下的那条；只有原始路径能列出时就留原始路径。
- 报告只写到用户这次点开的挂载点根：`<挂载点>/DiPlay/<文件名>`。点进子文件夹只为了看文件，不改变保存位置。文件名仍是 `DiPlay-yyyyMMdd-HHmmss-SSS.txt`。
- 成功对话框保留「查看」和「分享」。分享继续用应用私有目录里的那一份，给 FileProvider 签名。这一份不是死代码，不要删。
- 中文文案写进 `values-zh-rCN/strings.xml`，英文写进 `values/strings.xml`。
- 不改切歌宽限。
- 没有用户明确说提交之前，不要 `git commit`。

## 借鉴什么

从 CX 只借这三件，不借它的界面代码：

1. 一个目录要能 `list()`，才出现在列表里。列不出来的不当成盘。
2. 同一支盘若同时有 `/storage/...` 和 `/mnt/media_rw/...`，给用户看 `/storage` 那条。CX 用的 `StorageManager.getVolumeList()` 返回的就是这条公开路径。
3. 点进目录后，用真实文件名确认这是不是那支盘。标题不要写「物理 U 盘」。

这三件不够覆盖这台车。只留 `getVolumeList()` 会漏掉没登记进系统卷列表的 `/mnt/media_rw/usbotg-sda1`。所以 `/proc/mounts`、插盘广播、厂商根目录的子目录都继续收集。

不要照抄现在 `probe()` 的来源 3。那里 map 的键是卷根，值却是 `Android/data/<包名>/files`。`candidates()` 必须放卷根本身。否则列表里是一个叫 `files` 的目录，看不到盘里的歌，报告也会写进那个隐蔽目录。

保存位置也不跟 CX 的「存到当前文件夹」走。用户点进 `Music` 是为了确认盘，报告仍写到该挂载点根下的 `DiPlay/`。

## 界面

跟 `ConfirmSheetView` 同一张卡片，盖在当前页上，背后的设置页还在。不弹出系统对话框。

- 遮罩 `0xB3000000`。点卡片外面等于关闭。卡片白底或夜色底用 `palette.cardBg`，圆角 20dp，1dp `cardBorder`。顶上一条 36×3dp 的 `palette.accent`。进场 160ms 淡入并从小到大，离开 120ms 淡出，和退出确认一样。
- 卡片宽 640dp，列表在卡片里滚动，卡片本身不超过屏幕高度的七成。车机上每行至少 64dp，方便点。
- 标题 24sp、`sans-serif-medium`、`textMain`。标题下面一行 15sp `textMuted`：在列表页写「点一个位置查看里面的文件」；进入目录后写将要保存的路径，也就是挂载点根下的 `DiPlay/`。
- 挂载点一行两行字：名字 18sp `textMain`，路径 14sp `textMuted`。这是自己画的行，可以换行，不会被系统列表裁掉。行的底是 `cardInner`，圆角 12dp，1dp `cardBorder`，按下用 `pressable`。
- 目录行同样可点，名字后面加 `/`。文件行只展示，16sp `textMuted`，按下没有去处。空目录和读不了是一行说明，不是一颗假按钮。
- 右下角两颗按钮，尺寸和退出确认一样：次按钮「关闭」，主按钮「保存到这个盘」。主按钮只在已经进入某个目录时出现。保存过程中主按钮停用，文字改成现有的「正在保存…」。
- 成功和失败不另开系统对话框，就换这张卡片的内容。成功：标题用 `diagnostic_report_saved`，正文是完整路径，按钮是「查看」「分享」「完成」。失败：沿用现在的失败说明，按钮是「重试」「关闭」。重试仍写同一个挂载点。
- 换目录时不关掉卡片再开一张，只换标题和列表。读目录的那一下，副标题写成「正在读取…」，不要先闪一个空列表。

## 行为

1. 点「保存诊断报告」后，卡片列出挂载点。机身下载目录永远是最后一项，文案用 `export_internal_storage`。
2. 点一个挂载点，同一张卡片换成该目录的内容：文件夹在前，文件在后。记下这个挂载点，后面的子目录都算它的。
3. 可以再点进子文件夹。只要不在挂载点列表，第一行就是「上一级」。挂载点根上的「上一级」回到列表，不走到 `/`。
4. 已经进入某个目录时才有「保存到这个盘」。文件写到第 2 步记下的挂载点根下的 `DiPlay/`，不是当前子目录。
5. 点文件、空目录说明、读不了说明，卡片留着，列表不跳。
6. 关掉、点「关闭」或点遮罩之后，把这次浏览的代号加一。后台线程回来时代号对不上，就不要再改卡片上的字。换层不加代号。
7. 普通写入失败且本机有 root 时，只对这一个目标文件走现有的 `exportViaRoot`。`list()` 失败的下载目录显示「读不了这个目录」，不要显示成空目录；这一项仍可保存。

## 要删的死代码

这些只服务于「不看目录、往所有可疑 U 盘路径各写一份」：

- `DiagnosticExportStore.export`
- `saveWithoutPicker`
- `exportCopiesToAccessibleStorage`
- `usbMountRoots`、`usbVolumeDirectories`、`writeReportTo`
- `saveToDeviceDownloads`、`saveToDownloads`、只被它们使用的 `write(ContentResolver, …)`
- `getAvailableTargets`、`exportToSelectedTarget`、`ExportTarget`、`SavedReport`
- `ExportDestination`。结果改成 `primaryPath: String` 和 `shareableUri: Uri?`
- `DiagnosticVolumeProbe.probe`、`probeVolume`、`testWritable`、`kernelErrno`、`fallbackErrno`、`errnoOf`、`formatSummary`
- `VolumeProbeResult`、`ProbeSummary`
- `DiPlayActivity.showExportTargetPicker`、无参数的 `exportDiagnostics()`、带 `ExportTarget` 的那个重载、只为探测摘要服务的 `probeLine`

保留：`parseMountPoints`、`unescapeMountPoint`、`isMountedFileSystem`、`extractVolumeRoot`、`syncWriteText`、`exportViaRoot`、`saveInternalBackup`、`announce`、FileProvider 的 xml 与 authority。

---

### Task 1: 只发现能列出的目录

**Files:**

- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticVolumeProbe.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt`
- Modify: `common/src/main/res/values/strings.xml`
- Modify: `common/src/main/res/values-zh-rCN/strings.xml`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt`

**Interfaces:**

- Consumes: `parseMountPoints`、`unescapeMountPoint`、`extractVolumeRoot`、`defaultProcMounts`
- Produces:
  - `DiagnosticVolumeProbe.candidates(context: Context, extraMountPaths: Collection<String> = emptySet(), procMountsFile: File? = defaultProcMounts()): List<File>`
  - `data class ExportFolder(val label: String, val directory: File, val usb: Boolean)`
  - `DiagnosticExportStore.folders(context: Context, extraMountPaths: Collection<String> = emptySet(), procMountsFile: File? = DiagnosticVolumeProbe.defaultProcMounts()): List<ExportFolder>`
  - `DiagnosticExportStore.children(directory: File): List<File>`
  - `internal fun preferListedCopy(dirs: List<File>): List<File>`

- [ ] **Step 1: 写会失败的测试**

在 `DiagnosticExportStoreTest` 增加：

```kotlin
@Test fun foldersKeepTheOnlyListedCopyOfTheStick() {
    val context = RuntimeEnvironment.getApplication()
    val raw = File(context.cacheDir, "mnt/media_rw/usbotg-sda1").apply { mkdirs() }
    File(raw, "song.mp3").writeText("a")
    val folders = DiagnosticExportStore.folders(context, setOf(raw.absolutePath), procMountsFile = null)
    assertTrue(folders.any { it.usb && it.directory.absolutePath == raw.absolutePath })
    assertFalse(File(raw, "DiPlay").exists())
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    assertEquals(downloads.absolutePath, folders.last().directory.absolutePath)
    assertFalse(folders.last().usb)
}

@Test fun foldersPreferTheStorageCopyWhenBothList() {
    val context = RuntimeEnvironment.getApplication()
    val raw = File(context.cacheDir, "mnt/media_rw/usbotg-sda1").apply { mkdirs() }
    val shown = File(context.cacheDir, "storage/usbotg/usbotg-sda1").apply { mkdirs() }
    val folders = DiagnosticExportStore.folders(
        context,
        setOf(raw.absolutePath, shown.absolutePath),
        procMountsFile = null,
    )
    val sticks = folders.filter { it.directory.name == "usbotg-sda1" }
    assertEquals(shown.absolutePath, sticks.single().directory.absolutePath)
}

@Test fun foldersDropAPathTheAppCannotList() {
    val context = RuntimeEnvironment.getApplication()
    val blocked = File(context.cacheDir, "not-a-directory").apply { writeText("x") }
    val folders = DiagnosticExportStore.folders(context, setOf(blocked.absolutePath), procMountsFile = null)
    assertTrue(folders.none { it.directory.absolutePath == blocked.absolutePath })
}

@Test fun candidatesReturnsTheMountRootNotThePackageFilesDir() {
    val context = RuntimeEnvironment.getApplication()
    val usbRoot = File(context.cacheDir, "real-usb").apply { mkdirs() }
    File(usbRoot, "MyMusic").mkdirs()
    val pkg = File(usbRoot, "Android/data/${context.packageName}/files").apply { mkdirs() }
    val mounts = File(context.cacheDir, "mounts-root").apply {
        writeText("/dev/block/vold/8_1 ${usbRoot.absolutePath} vfat rw 0 0\n")
    }
    val found = DiagnosticVolumeProbe.candidates(context, emptySet(), mounts)
    assertEquals(listOf(usbRoot.absolutePath), found.map { it.absolutePath })
    assertFalse(found.any { it.absolutePath == pkg.absolutePath })
    assertFalse(found.any { it.name == "MyMusic" })
}

@Test fun childrenPutsDirectoriesFirst() {
    val dir = File(RuntimeEnvironment.getApplication().cacheDir, "stick-list").apply { mkdirs() }
    File(dir, "b.txt").writeText("b")
    File(dir, "a-dir").mkdirs()
    val names = DiagnosticExportStore.children(dir).map { it.name }
    assertEquals(listOf("a-dir", "b.txt"), names)
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportStoreTest.foldersKeepTheOnlyListedCopyOfTheStick --tests com.shilapi.xcertplay.DiagnosticExportStoreTest.foldersPreferTheStorageCopyWhenBothList --tests com.shilapi.xcertplay.DiagnosticExportStoreTest.foldersDropAPathTheAppCannotList --tests com.shilapi.xcertplay.DiagnosticExportStoreTest.candidatesReturnsTheMountRootNotThePackageFilesDir --tests com.shilapi.xcertplay.DiagnosticExportStoreTest.childrenPutsDirectoriesFirst`

工作目录：`/Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely`

Expected: 编译失败，`folders`、`candidates` 或 `children` 不存在。

- [ ] **Step 3: 写最短实现**

`candidates` 用来源 1、2、4、5 收集目录，返回 `List<File>`。不要调用 `probeVolume` 或 `testWritable`。来源 3 只用来发现卷：对 `getExternalFilesDirs` 从第 2 项起调用 `extractVolumeRoot`，放进列表的是这个卷根，不是 `files` 目录。每一项在加入前丢掉精确路径 `/`、`/mnt`、`/mnt/media_rw`。来源 5 仍只在前面没有任何候选时才扫厂商根目录，并且同样丢掉 `/mnt/media_rw` 自己；它的子目录可以留。

`preferListedCopy`：

```kotlin
internal fun preferListedCopy(dirs: List<File>): List<File> {
    val chosen = linkedMapOf<String, File>()
    for (dir in dirs) {
        val have = chosen[dir.name]
        val raw = dir.path.contains("/mnt/media_rw/")
        if (have == null || (have.path.contains("/mnt/media_rw/") && !raw)) {
            chosen[dir.name] = dir
        }
    }
    return chosen.values.toList()
}
```

`folders`：`candidates` 的结果里留下 `isDirectory && list() != null` 的，再 `preferListedCopy`。U 盘项用 `ExportFolder(directory.name, directory, usb = true)`。最后追加机身存储，不管它的 `list()` 是否成功：

```kotlin
val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
return usb + ExportFolder(context.getString(R.string.export_internal_storage), downloads, usb = false)
```

`export_internal_storage`：机身存储 / Phone storage。

`children`：`listFiles()` 为 null 时返回空列表。排序是目录在前，然后按小写名字。调用方要用另一个办法区分「空」和「读不了」：`directory.list()` 为 null 就是读不了。

这一步先别删 `probe()` 和 `export()`，Activity 还在调用它们。

- [ ] **Step 4: 再跑 Step 2 的测试**

Expected: PASS。

---

### Task 2: 只写挂载点根目录，并删掉旧的多路写入

**Files:**

- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticVolumeProbe.kt`
- Create: `common/src/main/java/com/shilapi/xcertplay/ui/component/ExportFolderSheet.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`（选择与保存，约 3175–3288 行。只负责把卡片盖上去、换内容和保存，不在这里画每一行）
- Modify: `common/src/main/res/values/strings.xml`
- Modify: `common/src/main/res/values-zh-rCN/strings.xml`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportFallbackTest.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticVolumeProbeTest.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt`

**Interfaces:**

- Consumes: Task 1 的 `folders`、`children`、`candidates`
- Produces: `DiagnosticExportStore.save(context: Context, directory: File, fileName: String, report: String): ExportOutcome`
- `ExportOutcome` 只留 `primaryPath: String`、`shareableUri: Uri?`

- [ ] **Step 1: 写保存测试**

把下面的测试放在 `DiagnosticExportFallbackTest`。那个类已经注册了 FileProvider。放在 `DiagnosticExportStoreTest` 里的话，`shareableUri` 会因为提供者没注册而是 null，正确实现也会失败。

```kotlin
@Test fun saveWritesOnlyTheChosenDirectory() {
    val chosen = File(context.cacheDir, "chosen-stick").apply { mkdirs() }
    val other = File(context.cacheDir, "other-stick").apply { mkdirs() }
    val outcome = DiagnosticExportStore.save(context, chosen, "DiPlay-test.txt", "report body")
    val saved = File(chosen, "DiPlay/DiPlay-test.txt")
    assertEquals(saved.absolutePath, outcome.primaryPath)
    assertEquals("report body", saved.readText())
    assertFalse(File(other, "DiPlay/DiPlay-test.txt").exists())
    assertNotNull(outcome.shareableUri)
    assertEquals("report body", read(outcome.shareableUri!!))
}
```

分享行为改成走 `save`：连续两次 `save` 到同一目录，两次 Uri 不同，各自还能读回自己的内容；连续 `save` 12 次后私有 `diagnostic-reports` 只留 8 个。FileProvider 不能读 `logs/diplay.log` 的测试留下，准备文件改成直接 `save`。

删掉这些测试：`saveWithoutPicker` 的全部用例、`export()` 的包目录与下载目录回退、`saveToDownloads` 的 Android 10 用例、`getAvailableTargets` 用例、`probe()` 和 `formatSummary` 的用例。`parseMountPoints` 和八进制转义留下。`probeTreatsMountPointItselfAsDiscreteVolumeWithoutSplittingSubdirectories` 改成断言 `candidates()`：只返回挂载点本身，不返回 `MyMusic`，也不返回 `Android/data/.../files`。Task 1 已有一份同样的断言，两份留一份即可，删掉调用 `probe()` 的那份。

- [ ] **Step 2: 跑新保存测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportFallbackTest.saveWritesOnlyTheChosenDirectory`

Expected: `save` 不存在，或仍会写到别的目录。

- [ ] **Step 3: 实现 save，并接上对话框**

`save`：

```kotlin
fun save(context: Context, directory: File, fileName: String, report: String): ExportOutcome {
    val file = File(directory, "DiPlay/$fileName")
    val written = syncWriteText(file, report) ||
        exportViaRoot(file, report, context.cacheDir)
    if (!written) throw IOException("Report storage is unavailable")
    announce(context, listOf(file.absolutePath))
    val backup = runCatching { saveInternalBackup(context, fileName, report) }.getOrNull()
    return ExportOutcome(file.absolutePath, backup?.uri)
}
```

浏览是 `ExportFolderSheet`，放在 `ui/component`，跟 `ConfirmSheetView` 并列。骨架直接照那张卡片：遮罩、圆角、强调色细条、`pressable` 按钮、160ms 进来、120ms 离开。卡片宽 640dp。列表放在 `ScrollView` 里，卡片高度不超过屏幕的七成。换目录只清掉列表再加行，不移除这张卡片。

行用 `pressable(palette.cardInner, 12dp, 1dp, palette.cardBorder)`，最小高度 64dp。挂载点两行字：名字 18sp `textMain`，路径 14sp `textMuted`。目录行名字后加 `/`。文件行 16sp `textMuted`，不设点击。空目录和读不了各是一行 `textMuted` 说明。

Activity 只留这一段流程：

```kotlin
private var browseGeneration = 0
private var exportSheet: ExportFolderSheet? = null

private fun showExportBrowser() {
    if (exportInProgress || exportSheet != null) return
    val generation = ++browseGeneration
    val mounted = mountedUsbPaths.toSet()
    val sheet = ExportFolderSheet(this, palette, onDismiss = {
        browseGeneration++
        exportSheet = null
    })
    exportSheet = sheet
    (cockpitHost ?: findViewById(android.R.id.content)).addView(sheet, FrameLayout.LayoutParams(-1, -1))
    sheet.showLoading()
    Thread({
        val roots = DiagnosticExportStore.folders(applicationContext, mounted)
        runOnUiThread {
            if (generation != browseGeneration || isFinishing || isDestroyed) return@runOnUiThread
            showRows(generation, current = null, mountRoot = null, entries = emptyList(), roots = roots, readable = true)
        }
    }, "diplay-export-list").start()
}
```

`openFolder` 先 `sheet.showLoading()`，再在 `diplay-export-list` 里列目录。回到主线程时代号不对就返回。`showRows` 把行交给同一张卡片。

点击时：

- 「上一级」，并且 `current` 就是某个挂载点：回到挂载点列表。
- 「上一级」，并且父目录就是 `mountRoot`：打开这个挂载点。
- 「上一级」的其他情况：打开父目录，`mountRoot` 不变。
- 列表页点中一项：`mountRoot` 定成它，后面不再改。
- 点到文件夹：进入该文件夹，`mountRoot` 不变。
- 点到文件：什么都不做。

主按钮文案 `export_save_to_disk`，只在 `current != null` 时出现。它调用 `exportHere(mountRoot ?: current)`。人在 `Music` 里点保存，写入的仍是挂载点根。保存时按钮停用，文字用现有的 `saving_report`。

成功后调用 `sheet.showSaved(path, onView, onShare)`，不新开对话框。失败调用 `sheet.showFailed(onRetry)`，重试仍是 `exportHere` 的同一个目录。不再使用 `probeLine`，成功时也不用 `no_writable_usb_detected`。

设置页按钮改为 `showExportBrowser()`。点遮罩、关闭和返回键都走 `sheet.dismiss()`，并把代号加一。

新字符串：

- `export_internal_storage`：机身存储 / Phone storage（Task 1 已加则这里不重复）
- `export_save_to_disk`：保存到这个盘 / Save to this drive
- `export_up`：上一级 / Up
- `export_empty`：（这个目录是空的） / (This folder is empty)
- `export_unreadable`：（读不了这个目录） / (Cannot read this folder)
- `export_saved_to`：报告已保存到：\n%1$s / Saved to:\n%1$s
- `export_pick_hint`：点一个位置，查看里面的文件 / Choose a place and look at its files
- `export_loading`：正在读取… / Reading…

然后按「要删的死代码」删除。`candidates` 成为唯一的发现入口。删完后修掉没用到的 import。

- [ ] **Step 4: 改界面测试**

`DiagnosticExportUiTest` 的语言是 `en`。不再用 `ShadowAlertDialog`。卡片是盖在 `android.R.id.content` 上的 `ExportFolderSheet`。找字用 `getString`，不要写死中文。后台线程结束后 `idle()` 主线程，再在这张卡片里按文字找行并 `performClick()`。

- 没有 U 盘：打开卡片，点机身存储，点 Save to this drive。同一张卡片里出现 `Download/DiPlay/` 下的路径。
- 有 U 盘：往 `mountedUsbPaths` 放一个缓存目录，里面写 `readme.txt`，再写一个子目录 `Music`。第一层能看到这个目录名和它的路径，两行都在。点进去后能看到 `readme.txt` 和 `Music/`。点 `readme.txt`，卡片还在，保存按钮还在。点进 `Music` 再保存。文件仍在 `<挂载点>/DiPlay/`，不在 `Music/DiPlay/`。卡片正文里的路径与这个文件一致，并且有分享按钮。

- [ ] **Step 5: 跑这一组测试**

Run:

```bash
./gradlew :common:testDebugUnitTest \
  --tests com.shilapi.xcertplay.DiagnosticExportStoreTest \
  --tests com.shilapi.xcertplay.DiagnosticExportFallbackTest \
  --tests com.shilapi.xcertplay.DiagnosticExportUiTest \
  --tests com.shilapi.xcertplay.DiagnosticVolumeProbeTest \
  --tests com.shilapi.xcertplay.DiagnosticVolumeMountTest
```

Expected: PASS。仓库里不再出现 `saveWithoutPicker`、`exportCopiesToAccessibleStorage`、`getAvailableTargets`、`fun export(`、`testWritable`。

不要提交。
