# USB FAT32 直接保存 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 金士顿 DataTraveler（FAT32）在系统没有挂载分区时，仍然出现在诊断报告的保存列表里；点这一行就用 libaums 把报告写到 U 盘 `/DiPlay/`，写完释放接口。

**Architecture:** 列表只识别设备，不打开盘。系统已经挂上、并且目录名不是空壳的分区，继续用现有的文件夹写入。否则每一支 libaums 大容量设备占一行。点这行才申请 USB 权限（还没有权限时），然后在后台只 `init` 一次，写入，核对字节数，`close()`。失败只显示失败和重试，不写 MTK 的空目录，不用 root。

**Tech Stack:** Kotlin，libaums `0.10.0`（已在 `gradle/libs.versions.toml`），Android 5.1 / API 22 车机，Robolectric 4.17，`:common:testDebugUnitTest`。

**Spec:** 本文「约定」一节。它取代 `docs/plan-cluster-media-and-usb-export.md` 里 U 盘第一屏和写入的冲突部分。仪表媒体不要改。

## Global Constraints

- 车机是 Android 5.1 / API 22。不要新增 USB 库，不要把 libaums 升版本。
- U 盘按 FAT32 处理。继续用现有的 `RobustFat32FileSystemCreator`。不要加 exFAT、NTFS，不要 `mount`，不要 `RootShellBridge`，不要解绑 `usb-storage`。
- `DiagnosticExportStore.folders()` 和保存列表的绘制都不准调用 `UsbMassStorageManager.openFileSystem`。
- 不准把文件写到 `/storage/usbotg`、`/mnt/media_rw/usbotg`、`sdcard1`、`sdcard2`。这些目录不是这支盘。
- 一次保存只打开一次设备。写入结束（成功或失败）后调用 `UsbMassStorageManager.close()`。
- 工作区里还有未提交的仪表改动。提交时只 `git add` 本计划点名的路径，不要 `git add -A`。
- 已有的「真挂载分区当文件夹浏览再保存」留下。`DiagnosticExportUiTest.browsingAStickShowsItsFilesAndSavesAtTheMountRoot` 必须继续通过。
- 日志行格式固定，方便下车后 `grep`：
  - `usb open vid=0951 pid=1666 perm=true partitions=1 fat32=true error=none`
  - `usb save written=true bytes=1234 path=USB:/DiPlay/DiPlay-20261105-103650-220.txt`
  - `vid` / `pid` 是四位小写十六进制。没有异常时 `error=none`。打不开时 `error=` 后面是 `异常类名: 消息`，或 `no filesystem`。

## 约定

11 月 3 日 10:17:56，应用启动画列表时就打开了 DataTraveler，同一毫秒两次。日志只有 `libaums returned no filesystem`，没有权限、异常和分区。11 月 5 日的版本据此把这一行删了，盘在 `usb/list` 里（`0951:1666`），保存列表只剩机身存储。

CX 文件管理（`ax.m6.d` / `ax.b2.l`）是点进去之后才 `hasPermission`，然后 `claimInterface(interface, true)`，再读 FAT32。libaums 0.10 的 `getMassStorageDevices` 已经只收接口 class 8、subclass 6、protocol 80，和 CX 相同。不要再写一套扫描。

列表规则：

1. `DiagnosticVolumeProbe.candidates()` 里，目录名（小写）不是 `sdcard1`、`sdcard2`、`usbotg`、`udisk` 的，算已经挂上的盘，用文件夹那一行。
2. 这样的行存在时，不再加 libaums 行。
3. 不存在时，`UsbMassStorageManager.getDevices()` 的每一支设备加一行。标签用产品名，没有产品名就是 `U 盘 (USB 存储)`。副标题是 `写入 U 盘 /DiPlay`。不读容量，不碰 `partitions`。
4. 最后一项始终是机身下载目录。

点 libaums 那一行就保存，不浏览文件。没有权限才走现有的 `requestPermission`。允许之后调用现有的 `exportToUsb`。拒绝时保持现在的文案「未获得 USB 设备访问权限」和重试。

## 文件

- 修改 `common/src/main/res/values/strings.xml`
- 修改 `common/src/main/res/values-zh-rCN/strings.xml`
- 修改 `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt`
- 修改 `common/src/main/java/com/shilapi/xcertplay/UsbMassStorageManager.kt`
- 修改 `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- 测试 `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt`
- 测试 `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt`

`DiagnosticVolumeProbe.kt` 不改。空壳过滤放在 `folders()`，避免动探测试。

---

### Task 1: 列表只识别，不打开

**Files:**
- Modify: `common/src/main/res/values/strings.xml`
- Modify: `common/src/main/res/values-zh-rCN/strings.xml`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt`

**Interfaces:**
- Consumes: `UsbMassStorageManager.getDevices`、`DiagnosticVolumeProbe.candidates`、`ExportFolder`
- Produces:
  - `DiagnosticExportStore.isRealMountedStick(dir: File): Boolean`
  - `folders()` 在没有真分区时返回带 `usbDevice` 的 `ExportFolder`，`detail` 为 `@string/export_usb_direct_detail`
  - `formatUsbDeviceLabel` 只读 `productName`，不读 `partitions`，不调用 `openFileSystem`

- [ ] **Step 1: 写会失败的测试**

在 `DiagnosticExportStoreTest` 里删掉 `foldersDoNotOfferLibaumsWhenNothingIsMounted`。把 `foldersPreferMountedVolumeOverLibaums` 的目录从 `mnt/media_rw/usbotg` 改成 `mnt/media_rw/usbotg-sda1`，并加上打开次数断言。再加下面三个测试。`blankUsbDevice()` 已经在这个类里。

```kotlin
@Test fun foldersPreferMountedVolumeOverLibaums() {
    val raw = File(context.cacheDir, "mnt/media_rw/usbotg-sda1").apply { mkdirs() }
    File(raw, "song.mp3").writeText("a")
    DiagnosticVolumeProbe.volumeListProvider = { arrayOf(TestVolume(raw.absolutePath)) }
    UsbMassStorageManager.deviceListProvider = { listOf(blankUsbDevice()) }
    var opens = 0
    UsbMassStorageManager.openFileSystemProvider = { opens += 1; null }
    UsbMassStorageManager.hasPermissionProvider = { _, _ -> true }

    val folders = DiagnosticExportStore.folders(context)
    val usb = folders.filter { it.usb }
    assertEquals(1, usb.size)
    assertEquals(raw.absolutePath, usb.single().directory?.absolutePath)
    assertTrue(usb.single().usbDevice == null)
    assertEquals(0, opens)
}

@Test fun foldersListsLibaumsDeviceWithoutOpeningWhenNothingIsMounted() {
    var opens = 0
    UsbMassStorageManager.deviceListProvider = { listOf(blankUsbDevice()) }
    UsbMassStorageManager.openFileSystemProvider = { opens += 1; null }
    UsbMassStorageManager.hasPermissionProvider = { _, _ -> true }
    DiagnosticVolumeProbe.volumeListProvider = { emptyArray<Any>() }
    DiagnosticVolumeProbe.volumePathsProvider = { emptyArray() }
    DiagnosticVolumeProbe.procMountsProvider = { "" }

    val folders = DiagnosticExportStore.folders(context)
    val usb = folders.filter { it.usbDevice != null }
    assertEquals(1, usb.size)
    assertEquals("U 盘 (USB 存储)", usb.single().label)
    assertEquals(context.getString(R.string.export_usb_direct_detail), usb.single().detail)
    assertEquals("USB: /", usb.single().displayPath)
    assertEquals(0, opens)
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    assertEquals(downloads.absolutePath, folders.last().directory?.absolutePath)
    assertFalse(folders.last().usb)
}

@Test fun foldersRejectShellsEvenWhenTheProbeReturnsThem() {
    val shell = File(context.cacheDir, "storage/usbotg").apply { mkdirs() }
    File(shell, "song.mp3").writeText("a")
    val sd = File(context.cacheDir, "storage/sdcard1").apply { mkdirs() }
    File(sd, "song.mp3").writeText("a")
    DiagnosticVolumeProbe.volumeListProvider = {
        arrayOf(TestVolume(shell.absolutePath), TestVolume(sd.absolutePath, description = "SD"))
    }
    UsbMassStorageManager.deviceListProvider = { listOf(blankUsbDevice()) }
    var opens = 0
    UsbMassStorageManager.openFileSystemProvider = { opens += 1; null }

    val folders = DiagnosticExportStore.folders(context)
    assertTrue(folders.none { it.directory?.absolutePath == shell.absolutePath })
    assertTrue(folders.none { it.directory?.absolutePath == sd.absolutePath })
    assertEquals(1, folders.count { it.usbDevice != null })
    assertEquals(0, opens)
}

@Test fun isRealMountedStickRejectsTheKnownShells() {
    assertFalse(DiagnosticExportStore.isRealMountedStick(File("/storage/usbotg")))
    assertFalse(DiagnosticExportStore.isRealMountedStick(File("/storage/udisk")))
    assertFalse(DiagnosticExportStore.isRealMountedStick(File("/storage/sdcard1")))
    assertFalse(DiagnosticExportStore.isRealMountedStick(File("/mnt/media_rw/sdcard2")))
    assertTrue(DiagnosticExportStore.isRealMountedStick(File("/storage/usbotg/usbotg-sda1")))
    assertTrue(DiagnosticExportStore.isRealMountedStick(File("/mnt/media_rw/udisk1")))
}
```

`DiagnosticExportStoreTest` 补上 `import com.shilapi.xcertplay.host.R`。

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportStoreTest`

Expected: `foldersListsLibaumsDeviceWithoutOpeningWhenNothingIsMounted` 失败，因为现在没有 libaums 行。`isRealMountedStickRejectsTheKnownShells` 因函数不存在而失败。

- [ ] **Step 3: 加字符串并改 `folders()`**

`values/strings.xml` 在 `export_usb_storage` 下一行：

```xml
<string name="export_usb_direct_detail">Write to USB /DiPlay</string>
```

`values-zh-rCN/strings.xml` 在 `export_usb_storage` 下一行：

```xml
<string name="export_usb_direct_detail">写入 U 盘 /DiPlay</string>
```

`DiagnosticExportStore.kt` 顶部补 `import com.shilapi.xcertplay.host.R`（文件里如果已经有这个 import 就不要再加）。把 `folders()` 换成：

```kotlin
fun folders(context: Context): List<ExportFolder> {
    val result = mutableListOf<ExportFolder>()
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val mounted = DiagnosticVolumeProbe.candidates(context)
        .filter { it.absolutePath != downloads.absolutePath }
        .filter { isRealMountedStick(it) }
        .map { dir ->
            ExportFolder(
                label = formatVolumeLabel(context, dir, usb = true),
                directory = dir,
                usb = true,
                detail = formatVolumeDetail(context, dir),
            )
        }
    result.addAll(mounted)
    if (mounted.isEmpty()) {
        for (device in UsbMassStorageManager.getDevices(context)) {
            result.add(
                ExportFolder(
                    label = formatUsbDeviceLabel(device),
                    usb = true,
                    detail = context.getString(R.string.export_usb_direct_detail),
                    usbDevice = device,
                ),
            )
        }
    }
    result.add(
        ExportFolder(
            context.getString(R.string.export_internal_storage),
            directory = downloads,
            usb = false,
            detail = formatVolumeDetail(context, downloads),
        ),
    )
    DiagLog.i("DiagnosticExport", result.joinToString(" | ") { folder ->
        "${folder.label} [${folder.displayPath}] usb=${folder.usb}"
    }.ifEmpty { "places empty" })
    return result
}

internal fun isRealMountedStick(dir: File): Boolean {
    val leaf = dir.name.lowercase()
    return leaf != "sdcard1" && leaf != "sdcard2" && leaf != "usbotg" && leaf != "udisk"
}
```

把 `formatUsbDeviceLabel` 换成下面这个。`formatUsbDeviceDetail` 这一步留着，但函数体改成只返回 `context.getString(R.string.export_usb_direct_detail)`，不准再调用 `openFileSystem`。Task 3 会删掉它和 `DiPlayActivity` 里的调用。这一步删掉的话，`:common` 的界面代码编译不过。

```kotlin
internal fun formatUsbDeviceLabel(device: UsbMassStorageDevice): String {
    val productName = runCatching { device.usbDevice.productName }.getOrNull()?.takeIf { it.isNotBlank() }
    return if (productName != null) "U 盘 ($productName)" else "U 盘 (USB 存储)"
}
```

删掉 `folders()` 上面「libaums 在这台车上打不开文件系统」那段注释。

- [ ] **Step 4: 再跑，确认通过**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportStoreTest`

Expected: PASS。`foldersKeepTheOnlyListedCopyOfTheStick` 和 `foldersFallbackToVolumePathsWhenVolumeListEmpty` 仍用 `usbotg-sda1`，继续通过。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/res/values/strings.xml \
  common/src/main/res/values-zh-rCN/strings.xml \
  common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt \
  common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt
git commit -m "fix(export): list the FAT32 stick without opening it"
```

---

### Task 2: 打开结果和写入结果各记一行，写完释放

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/UsbMassStorageManager.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt` 的 `saveToUsb`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `folders()` 不打开设备
- Produces:
  - `UsbMassStorageManager.UsbOpenFacts(partitionCount: Int, fat32: Boolean, error: String?)`
  - `UsbMassStorageManager.lastOpenFacts: UsbOpenFacts`
  - `UsbMassStorageManager.describeOpen(vendorId: Int, productId: Int, permission: Boolean, facts: UsbOpenFacts): String`
  - `DiagnosticExportStore.describeSave(written: Boolean, bytes: Int, path: String): String`
  - `saveToUsb` 在返回前 `close()`，并打出上面两行日志

- [ ] **Step 1: 写会失败的测试**

`DiagnosticExportStoreTest.tearDown()` 末尾加 `DiagSink.reset()`。文件补 `import com.shilapi.xcertplay.DiagSink`。

```kotlin
@Test fun describeOpenUsesFourHexDigitsAndNoneForNoError() {
    val line = UsbMassStorageManager.describeOpen(
        vendorId = 0x0951,
        productId = 0x1666,
        permission = true,
        facts = UsbMassStorageManager.UsbOpenFacts(partitionCount = 1, fat32 = true, error = null),
    )
    assertEquals("usb open vid=0951 pid=1666 perm=true partitions=1 fat32=true error=none", line)
}

@Test fun saveToUsbLogsTheMissAndDoesNotCreateAShell() {
    UsbMassStorageManager.openFileSystemProvider = { null }
    UsbMassStorageManager.hasPermissionProvider = { _, _ -> true }
    val lines = mutableListOf<String>()
    DiagSink.attach { lines.add(it) }
    val shells = listOf(File("/mnt/media_rw/usbotg/DiPlay"), File("/storage/usbotg/DiPlay"))

    val outcome = DiagnosticExportStore.saveToUsb(context, blankUsbDevice(), "report.txt", "hello")

    assertFalse(outcome.written)
    assertEquals("USB: /DiPlay/report.txt", outcome.primaryPath)
    assertTrue(shells.none { it.exists() })
    val text = lines.joinToString("\n")
    assertTrue(text, text.contains("usb open vid=0000 pid=0000 perm=true partitions=0 fat32=false error=no filesystem"))
    assertTrue(text, text.contains("usb save written=false bytes=5 path=USB:/DiPlay/report.txt"))
}
```

`saveToUsbDoesNotWriteTheMtkShellWhenLibaumsHasNoFilesystem` 留下，不要删。

- [ ] **Step 2: 跑这两个测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportStoreTest.describeOpenUsesFourHexDigitsAndNoneForNoError --tests com.shilapi.xcertplay.DiagnosticExportStoreTest.saveToUsbLogsTheMissAndDoesNotCreateAShell`

Expected: `describeOpen` 不存在，编译失败。

- [ ] **Step 3: 记下打开事实，并在 `saveToUsb` 里写日志、释放设备**

在 `UsbMassStorageManager` 里，`openFileSystemProvider` 下面加：

```kotlin
internal data class UsbOpenFacts(
    val partitionCount: Int,
    val fat32: Boolean,
    val error: String?,
)

internal var lastOpenFacts: UsbOpenFacts = UsbOpenFacts(0, false, "not opened")

internal fun describeOpen(vendorId: Int, productId: Int, permission: Boolean, facts: UsbOpenFacts): String {
    val error = facts.error ?: "none"
    return "usb open vid=%04x pid=%04x perm=%s partitions=%d fat32=%s error=%s".format(
        vendorId,
        productId,
        permission,
        facts.partitionCount,
        facts.fat32,
        error,
    )
}
```

`openFileSystem` 的每个出口都要先写 `lastOpenFacts`：

- 进入函数第一行：`lastOpenFacts = UsbOpenFacts(0, false, "not opened")`
- `openFileSystemProvider` 返回前：文件系统是 null 时 `error = "no filesystem"`，否则 `error = null`，`fat32` 为文件系统是否非 null，`partitionCount = 0`
- `device.init()` 之后、分区读完：`partitionCount = partitions.size`。主文件系统非 null 时 `fat32 = true`、`error = null`。分区读完仍没有文件系统时 `fat32 = false`、`error = "no filesystem"`，然后保持现有的 `close()`
- `catch` 里：`lastOpenFacts = UsbOpenFacts(0, false, "${e.javaClass.simpleName}: ${e.message}")`，然后保持现有的 `close()` 和 `return null`

不要在 `openFileSystem` 里打那行 `usb open`。行由 `saveToUsb` 打，这样权限用的是调用方手里的 `Context`。

`DiagnosticExportStore` 加：

```kotlin
internal fun describeSave(written: Boolean, bytes: Int, path: String): String =
    "usb save written=$written bytes=$bytes path=$path"
```

`saveToUsb` 在 `openFileSystem` 返回后立刻打开放行，在算出 `written` 之后打保存行。`try` 外面加 `finally { UsbMassStorageManager.close() }`。`primaryPath` 保持 `USB: /DiPlay/$fileName`。打开失败不准调用 `exportViaRoot`。字节数用 `report.toByteArray(Charsets.UTF_8).size`。

```kotlin
val fs = UsbMassStorageManager.openFileSystem(device)
val vendorId = runCatching { device.usbDevice.vendorId }.getOrDefault(0)
val productId = runCatching { device.usbDevice.productId }.getOrDefault(0)
val permission = UsbMassStorageManager.hasPermission(context, device)
DiagLog.i(
    "DiagnosticExport",
    UsbMassStorageManager.describeOpen(vendorId, productId, permission, UsbMassStorageManager.lastOpenFacts),
)
```

成功和失败都打：

```kotlin
DiagLog.i("DiagnosticExport", describeSave(written, report.toByteArray(Charsets.UTF_8).size, primaryPath))
```

`finally` 必须在这两行之后仍会执行。`close()` 不准包在 `if (written)` 里。

- [ ] **Step 4: 再跑保存测试**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportStoreTest`

Expected: PASS。`blankUsbDevice()` 的 `usbDevice` 是 null，所以 `vid=0000 pid=0000`。真机上 `vendorId` 能读到，会是 `vid=0951 pid=1666`。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/UsbMassStorageManager.kt \
  common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt \
  common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt
git commit -m "fix(export): log the FAT32 open and release the stick after save"
```

---

### Task 3: 点这一行就保存

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ExportFolder.usbDevice` 和 `detail`；Task 2 的 `saveToUsb`
- Produces: `DiPlayActivity.saveUsb(folder: ExportFolder)`。libaums 行的点击调用它。它不浏览目录。

- [ ] **Step 1: 写会失败的界面测试**

加到 `DiagnosticExportUiTest`。这个类已经有 `awaitText`、`clickRow`、`texts`。补 import：`DiagSink`、`UsbMassStorageManager`、`DiagnosticExportStoreTest`。

```kotlin
@Test fun aLibaumsStickIsListedWithoutOpeningAndSaveTriesOnce() {
    val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
    val activity = controller.get()
    registerReportProvider(activity.applicationContext)
    var opens = 0
    UsbMassStorageManager.deviceListProvider = { listOf(DiagnosticExportStoreTest.blankUsbDevice()) }
    UsbMassStorageManager.hasPermissionProvider = { _, _ -> true }
    UsbMassStorageManager.openFileSystemProvider = { opens += 1; null }
    DiagnosticVolumeProbe.volumeListProvider = { emptyArray<Any>() }
    DiagnosticVolumeProbe.volumePathsProvider = { emptyArray() }
    DiagnosticVolumeProbe.procMountsProvider = { "" }
    try {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
        awaitText(activity, "U 盘 (USB 存储)")
        awaitText(activity, activity.getString(R.string.export_usb_direct_detail))
        assertEquals(0, opens)
        clickRow(activity, "U 盘 (USB 存储)")
        awaitText(activity, activity.getString(R.string.export_failed_kept))
        assertEquals(1, opens)
        assertTrue(texts(activity).none { it.text?.contains("readme.txt") == true })
    } finally {
        UsbMassStorageManager.deviceListProvider = null
        UsbMassStorageManager.hasPermissionProvider = null
        UsbMassStorageManager.openFileSystemProvider = null
        UsbMassStorageManager.close()
        DiagnosticVolumeProbe.volumeListProvider = null
        DiagnosticVolumeProbe.volumePathsProvider = null
        DiagnosticVolumeProbe.procMountsProvider = null
        DiagSink.reset()
        controller.pause().stop().destroy()
    }
}
```

`blankUsbDevice` 现在是 `DiagnosticExportStoreTest` 的 private 方法。挪到这个类的 `companion object`，签名保持 `internal fun blankUsbDevice(): UsbMassStorageDevice`。类里面的测试改成 `DiagnosticExportStoreTest.blankUsbDevice()`。不要新写一份 `Unsafe`。不要 `DiagnosticExportStoreTest()` 这样建实例，`@After` 会把 provider 清掉。

- [ ] **Step 2: 跑界面测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportUiTest.aLibaumsStickIsListedWithoutOpeningAndSaveTriesOnce`

Expected: 等不到 `U 盘 (USB 存储)`，因为当前 `showMountList` 还不会列出它；或者列表出来了但点击后 `opens` 不是 1（现在的点击会去浏览，打开次数和文案都不对）。

- [ ] **Step 3: 点击直接保存，删掉浏览 U 盘的路径**

`showMountList` 里不要再调用 `formatUsbDeviceLabel` 和 `formatUsbDeviceDetail`。标签用 `folder.label`，副标题用 `folder.detail ?: folder.displayPath`。`folder.usbDevice != null` 时点击调用 `saveUsb(folder)`，不要调用 `openUsbRoot`。`formatUsbDeviceDetail` 随这次调用一起删掉。

用下面这个函数替换 `openUsbRoot`。权限拒绝的文案保持这句汉字，不要新加字符串。

```kotlin
private fun saveUsb(folder: ExportFolder) {
    val device = folder.usbDevice ?: return
    if (exportInProgress) return
    val sheet = exportSheet ?: return
    if (!UsbMassStorageManager.hasPermission(this, device)) {
        sheet.showLoading(folder.label, getString(R.string.export_loading))
        UsbMassStorageManager.requestPermission(this, device) { granted ->
            if (granted) {
                exportToUsb(device)
            } else {
                sheet.showChoices(
                    folder.label,
                    "未获得 USB 设备访问权限",
                    listOf(ExportFolderSheet.Choice(getString(R.string.export_up), null, clickable = true) { showMountList() }),
                    saveLabel = getString(R.string.retry),
                    onSave = { saveUsb(folder) },
                    closeLabel = getString(R.string.close),
                )
            }
        }
    } else {
        exportToUsb(device)
    }
}
```

删除 `initAndOpenUsb`、`openUsbDirectory`、`showUsbDirectory`。它们只为了在保存前把盘里的文件列出来，那一次 `openFileSystem` 就是 11 月 3 日失败的打开。`DiagnosticExportStore.usbChildren` 删掉后如果没有引用，也删掉。文件夹那条 `openDirectory` 不动。

- [ ] **Step 4: 跑导出测试**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportStoreTest --tests com.shilapi.xcertplay.DiagnosticExportUiTest`

Expected: PASS。其中 `browsingAStickShowsItsFilesAndSavesAtTheMountRoot` 仍是点进 `usb-otg-sda1` 文件夹再保存，不是 libaums 行。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt \
  common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt \
  common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt \
  common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt
git commit -m "fix(export): save the diagnostic report when the USB row is tapped"
```

---

## 车上怎么验收

装上这三笔提交之后的包。U 盘插好，打开「保存诊断报告」。

- 列表里有 `U 盘 (DataTraveler 3.0)`，副标题是 `写入 U 盘 /DiPlay`。没有 `sdcard1`、`sdcard2`、单独的 `usbotg`。
- 点这一行。第一次如果弹出 USB 权限，点允许。不要先看到盘里的文件列表。
- 拔下 U 盘插到电脑，根目录有 `DiPlay/DiPlay-日期.txt`。
- 再上传一份云端日志。里面要有 `usb open vid=0951 pid=1666`，`fat32=true`，以及 `usb save written=true`。失败时 `error=` 后面必须是异常类名和消息，不能再只有 `libaums returned no filesystem`。

## Self-review

- 列表不打开、空壳不显示、真分区优先、点行保存、写完释放、日志格式、不用 root：分别落在 Task 1、Task 1、Task 1、Task 3、Task 2、Task 2。
- 没有留下「内核占着接口所以不显示」的旧结论。
- `describeOpen`、`describeSave`、`UsbOpenFacts`、`isRealMountedStick`、`saveUsb`、`export_usb_direct_detail` 在后文用到的名字和这里一致。
- 仪表、热点、蓝牙文件不在任何一个 `git add` 里。
