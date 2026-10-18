# U 盘诊断报告导出（方案一）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 解决吉利星瑞 E01（Android 5.1 / API 22）车机上诊断报告无法保存至 U 盘的问题，实现免 DocumentsUI、三源卷枚举、真实内核 errno 取证、四层落地（包目录 → 根目录 → Download → 内部保底）与物理刷盘同步（fsync + MediaScanner）的 U 盘一键导出。

**Architecture:** 
1. `DiagnosticVolumeProbe`：汇总系统 `getExternalFilesDirs(null)`（第 2 项起）、`ACTION_MEDIA_MOUNTED` 动态广播记录路径、以及厂商硬编码挂载根三类源头。通过 `android.system.Os` 探测包目录与根目录可写性并捕获真实 Linux errno（如 `EACCES`、`EROFS`），输出结构化状态与 12sp 诊断取证行。
2. `DiagnosticExportStore`：
   - 物理落地顺序：`b. 包目录` → `a. 根目录` → `c. Download` → `d. 内部保底`（d 只用来签发分享 Uri，不参与 destination 判定）；
   - 物理刷盘保障：使用 `FileOutputStream.fd.sync()` 强制将脏页刷入 U 盘硬件（**必须在关流之前**），调用 `MediaScannerConnection.scanFile` 通知系统媒体库免重启立即可见；
   - FileProvider 安全边界：外置 U 盘不进入 FileProvider（`diagnostic_report_paths.xml` 只映射本包 `files/` 与 `external-files/` 下的 `diagnostic-reports/`，任何 U 盘路径都会抛 `IllegalArgumentException`），内部私有目录保留一份受管备份用于系统「分享」Intent，车机「查看报告」直接在内存中展示文本。
3. `DiPlayActivity`：
   - 动态广播注册：在 `onResume`/`onPause`（跟现有 `registerUsbTopology` 同一对生命周期回调）注册带 `addDataScheme("file")` 的 `ACTION_MEDIA_MOUNTED` 广播，动态跟踪插拔；
   - 单一按钮出口：移除在 E01 上无法唤起的 `CreateDocument` 及冗余的「选择保存位置」按钮，主线程绝不执行阻塞性 I/O 探测，点击后统一进入后台线程完成探测与写入；
   - 如实弹窗：区分「已存入 U 盘」与「未检测到可写 U 盘（已保存至车机）」弹窗，底部附带探测小字。

**Tech Stack:** Kotlin, Android SDK 22 (minSdk 19, Android 5.1 baseline), `android.system.Os`, `FileOutputStream.fd.sync()`, `MediaScannerConnection`, Robolectric 4.17（单测只能跑 SDK 23+）。

**Spec:** `docs/superpowers/specs/2026-10-17-report-export-handover.md` 及 `docs/superpowers/specs/2026-10-17-report-destination-design.md`。

## Global Constraints

- **最低 API 支持**：minSdk 19，E01 为 API 22。使用 `android.system.Os` 和 `android.system.ErrnoException`（API 21+ 引入，E01 原生支持）。
- **零外部依赖**：不引入任何第三方存储或文件库。
- **真凭实据，绝不谎报**：写入 a 或 b 成功才提示“已存入 U 盘”；仅 c 成功明确提示“未检测到可写 U 盘，已保存至车机”；底部 12sp 小字透出真实挂载点与内核 errno 取证信息。
- **写盘硬契约**：必须调用 `FileDescriptor.sync()` 确保拔盘数据不丢，必须调用 `MediaScannerConnection.scanFile`。
- **FileProvider 安全隔离**：U 盘物理文件不交由 `FileProvider` 解析（避免 paths.xml 缺失根映射崩溃），由受管内部备份专供 `ACTION_SEND`。
- **UI 主线程零阻塞**：主线程绘制设置卡片时不执行任何磁盘试写，探测全部在导出后台线程执行。

---

## 执行前检查（Preflight，动第一个 Step 之前必须做完）

1. **工作树是共用的。** 现在 `git status` 里就有另一条线（座舱 UI 复审 / QuickHud）的未提交改动，其中 `DiPlayActivity.kt`、`values/strings.xml`、`values-zh-rCN/strings.xml` 正是本计划 Task 3 要提交的文件；这行清单随时会变，**开工前现看一次 `git status`，别背这份清单**。
   - 先让那条线落地或 `git stash push -u`，确认 `git status` 干净再开工；
   - 提交时只把本计划的 hunk 加进去（`git add -p`），**不要** `git add <整文件>`，否则别人的改动会混进 feat commit。
2. **记下绿色基线**：`./gradlew :common:testDebugUnitTest` 应全过（当前 259 例）。基线不绿就别往下走，否则分不清是谁弄红的。
3. **确认一个待拍板项**：交接文档 `specs/2026-10-17-report-export-handover.md` §六.1 还没定——诊断按钮是「始终显示，点了再探测」还是「探测到可写卷才显示」。本计划按**前者**实现：按钮常显，探测放在点击后的后台线程（主线程零阻塞这条硬约束不允许在 `render()` 里试写）。若用户改选「探测到才显示」，Task 3 要加一步：`onResume` 后台 probe 一次缓存结果、`render()` 读缓存决定按钮可见性——先问，别默认。
4. **本机不能打 release 包**：release 签名读 `ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`，仓库里没有 jks，当前环境也未设。Task 4 用 debug 构建 + lint，不要跑 `assembleRelease`。
5. **API 22 只能靠车机验**：Robolectric 4.17 最低只支持 SDK 23（已核对 `DefaultSdkProvider` 注册的 23..37），单测证明的是逻辑；E01 到底怎么挂 U 盘、`getExternalFilesDirs` 给不给第 2 项，只有 Task 5 的车机能证明。

---

### Task 1: 存储卷三源枚举与真实内核 Errno 可写性探测 (`DiagnosticVolumeProbe`)

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/DiagnosticVolumeProbe.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/DiagnosticVolumeProbeTest.kt`

**Interfaces:**
- Consumes: `android.content.Context`, `extraMountPaths: Collection<String>`
- Produces: 
  ```kotlin
  data class VolumeProbeResult(
      val name: String,
      val rootDir: File?,
      val rootWritable: Boolean,
      val rootErrno: String? = null,
      val packageDir: File?,
      val packageWritable: Boolean,
      val packageErrno: String? = null,
  )

  data class ProbeSummary(
      val volumes: List<VolumeProbeResult>,
      val summaryLine: String,
  )

  object DiagnosticVolumeProbe {
      fun probe(context: Context, extraMountPaths: Collection<String> = emptySet()): ProbeSummary
      fun formatSummary(volumes: List<VolumeProbeResult>): String
  }
  ```

- [x] **Step 1: Write failing unit tests covering volume sources and real errno formatting**

在 `common/src/test/java/com/shilapi/xcertplay/DiagnosticVolumeProbeTest.kt`：
```kotlin
package com.shilapi.xcertplay

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.17 最低只支持 SDK 23：它的 DefaultSdkProvider 里注册的是 23..37，写 22 会在
// 加载 runner 时直接报 unsupported SDK，测试一个都跑不到。这里验证的是卷枚举与 errno 格式化的
// 逻辑，API 22 的平台差异由 Task 5 在车机上验，不靠单测假装覆盖。
@Config(sdk = [23], manifest = Config.NONE)
class DiagnosticVolumeProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun emptyVolumesGeneratesClearSummary() {
        val summary = DiagnosticVolumeProbe.probe(context)
        assertTrue(summary.summaryLine.contains("未发现外置存储卷") || summary.summaryLine.contains("No external volume"))
    }

    @Test fun detectsSecondaryVolumeFromExternalFilesDirs() {
        val fakeUsbRoot = temp.newFolder("usbotg-sda1")
        val fakePkgDir = File(fakeUsbRoot, "Android/data/${context.packageName}/files").apply { mkdirs() }
        
        val contextWithUsb = object : ContextWrapper(context) {
            override fun getExternalFilesDirs(type: String?): Array<File?> {
                return arrayOf(context.getExternalFilesDir(null), fakePkgDir)
            }
        }
        val summary = DiagnosticVolumeProbe.probe(contextWithUsb)
        assertEquals(1, summary.volumes.size)
        val vol = summary.volumes.first()
        assertEquals("usbotg-sda1", vol.name)
        assertTrue(vol.packageWritable)
        assertTrue(vol.rootWritable)
        assertTrue(summary.summaryLine.contains("usbotg-sda1"))
    }

    @Test fun formatsPackageDirWritableAndRootEaccesTruthfully() {
        val result = VolumeProbeResult(
            name = "usbotg-sda1",
            rootDir = File("/storage/usbotg/usbotg-sda1"),
            rootWritable = false,
            rootErrno = "EACCES",
            packageDir = File("/storage/usbotg/usbotg-sda1/Android/data/com.shilapi.xcertplay/files"),
            packageWritable = true,
            packageErrno = null,
        )
        val line = DiagnosticVolumeProbe.formatSummary(listOf(result))
        assertTrue(line.contains("usbotg-sda1"))
        assertTrue(line.contains("可写(包目录)"))
        assertTrue(line.contains("EACCES"))
    }

    @Test fun capturesExtraMountPathsFromMountedBroadcast() {
        val mountedDir = temp.newFolder("mnt-media-rw-usb")
        val summary = DiagnosticVolumeProbe.probe(context, setOf(mountedDir.absolutePath))
        assertTrue(summary.volumes.any { it.name == "mnt-media-rw-usb" })
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticVolumeProbeTest`
Expected: FAIL with compilation error (Unresolved reference: DiagnosticVolumeProbe).

- [x] **Step 3: Implement DiagnosticVolumeProbe with real errno capture**

编写 `common/src/main/java/com/shilapi/xcertplay/DiagnosticVolumeProbe.kt`：
```kotlin
package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal data class VolumeProbeResult(
    val name: String,
    val rootDir: File?,
    val rootWritable: Boolean,
    val rootErrno: String? = null,
    val packageDir: File?,
    val packageWritable: Boolean,
    val packageErrno: String? = null,
)

internal data class ProbeSummary(
    val volumes: List<VolumeProbeResult>,
    val summaryLine: String,
)

internal object DiagnosticVolumeProbe {
    private val vendorMountRoots = listOf(
        "/storage/usbotg",
        "/storage/udisk",
        "/mnt/usb_storage",
        "/mnt/udisk",
        "/mnt/usb",
        "/mnt/media_rw",
    )

    fun probe(context: Context, extraMountPaths: Collection<String> = emptySet()): ProbeSummary {
        val candidates = linkedMapOf<String, File>()

        // 来源 1：系统契约 getExternalFilesDirs(null)，第 2 项及以后
        runCatching {
            val dirs = context.getExternalFilesDirs(null) ?: emptyArray()
            for (i in 1 until dirs.size) {
                val dir = dirs[i] ?: continue
                val name = extractVolumeName(dir)
                candidates[name] = dir
            }
        }

        // 来源 2：ACTION_MEDIA_MOUNTED 广播携带的挂载路径
        for (path in extraMountPaths) {
            val file = File(path)
            if (file.isDirectory) {
                candidates[file.name] = file
            }
        }

        // 来源 3：厂商私有挂载根目录兜底扫描
        for (rootPath in vendorMountRoots) {
            val root = File(rootPath)
            if (root.isDirectory) {
                root.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                    candidates.putIfAbsent(sub.name, sub)
                }
            }
        }

        val results = candidates.map { (name, target) ->
            probeVolume(context, name, target)
        }

        return ProbeSummary(results, formatSummary(results))
    }

    private fun extractVolumeName(packageDir: File): String {
        val path = packageDir.absolutePath
        val marker = "/Android/data"
        val idx = path.indexOf(marker)
        return if (idx > 0) File(path.substring(0, idx)).name else packageDir.name
    }

    private fun extractVolumeRoot(packageDir: File): File? {
        val path = packageDir.absolutePath
        val marker = "/Android/data"
        val idx = path.indexOf(marker)
        return if (idx > 0) File(path.substring(0, idx)) else null
    }

    private fun probeVolume(context: Context, name: String, target: File): VolumeProbeResult {
        val (rootDir, pkgDir) = if (target.absolutePath.contains("/Android/data/")) {
            extractVolumeRoot(target) to target
        } else {
            target to File(target, "Android/data/${context.packageName}/files")
        }

        val (rootWritable, rootErrno) = testWritable(rootDir?.let { File(it, "DiPlay") })
        val (pkgWritable, pkgErrno) = testWritable(pkgDir?.let { File(it, "DiPlay") })

        return VolumeProbeResult(
            name = name,
            rootDir = rootDir,
            rootWritable = rootWritable,
            rootErrno = rootErrno,
            packageDir = pkgDir,
            packageWritable = pkgWritable,
            packageErrno = pkgErrno,
        )
    }

    private fun testWritable(dir: File?): Pair<Boolean, String?> {
        if (dir == null) return false to "ENOENT"
        // 同名文件占着路径时 mkdirs 永远失败，必须先按 ENOTDIR 报出来，
        // 否则测试里用文件挡住根目录的写法只会得到一句 FileNotFoundException。
        if (dir.exists() && !dir.isDirectory) return false to "ENOTDIR"
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                val err = extractKernelErrno(dir)
                return false to err
            }
            val testFile = File(dir, ".probe_${System.currentTimeMillis()}")
            FileOutputStream(testFile).use { stream ->
                stream.write(42)
                stream.fd.sync()
            }
            val ok = testFile.exists() && testFile.length() > 0
            testFile.delete()
            if (ok) true to null else false to "EIO"
        } catch (e: Exception) {
            false to extractErrnoFromException(e)
        }
    }

    private fun extractKernelErrno(target: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                android.system.Os.access(target.absolutePath, android.system.OsConstants.W_OK)
            } catch (e: android.system.ErrnoException) {
                return android.system.OsConstants.errnoName(e.errno) ?: "errno_${e.errno}"
            } catch (_: Exception) {}
        }
        return if (!target.exists()) "ENOENT" else if (!target.canWrite()) "EACCES" else "unwritable"
    }

    private fun extractErrnoFromException(e: Throwable): String {
        var current: Throwable? = e
        while (current != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && current is android.system.ErrnoException) {
                return android.system.OsConstants.errnoName(current.errno) ?: "errno_${current.errno}"
            }
            val msg = current.message
            if (msg != null) {
                val regex = Regex("""\b(EACCES|EROFS|EPERM|ENOENT|ENOTDIR|ENOSPC|EIO)\b""")
                val match = regex.find(msg)
                if (match != null) return match.value
            }
            current = current.cause
        }
        return e.javaClass.simpleName
    }

    fun formatSummary(volumes: List<VolumeProbeResult>): String {
        if (volumes.isEmpty()) {
            return "未发现外置存储卷 (No external volume)"
        }
        val count = volumes.size
        val details = volumes.joinToString(" · ") { v ->
            when {
                v.rootWritable -> "${v.name} 可写(根目录)"
                v.packageWritable -> "${v.name} 可写(包目录)${v.rootErrno?.let { " · 根目录 $it" } ?: ""}"
                else -> "${v.name} 不可写(${v.packageErrno ?: v.rootErrno ?: "unknown"})"
            }
        }
        return "发现 $count 个卷 · $details"
    }
}
```

实现注意：
- `OsConstants.errnoName(int)` 和 `Os.access` 都是 API 21+，该在公开 API 里（`data/api-versions.xml` 中 `android/system/OsConstants` 标 `since="21"`，`errnoName` 没有更晚的 since），E01（22）能用；但 `minSdk = 19`，两处调用必须留在 `Build.VERSION.SDK_INT >= LOLLIPOP` 分支内，否则 lint `NewApi` 会拦。
- **别在单测里断言 `Os.access` 的返回值**：实测 Robolectric 4.17（SDK 23/28）下它对一个确实可写的 cacheDir 返回 `false`，是桩不是真实语义。所以本任务只测 `formatSummary` 的格式化与卷枚举；errno 的真值靠 Task 5 的车机取证。
- `testWritable` 会在探测时真的建 `<卷>/DiPlay/` 再删探针文件，这是有意的副作用（建目录本身也是可写性证据）；探测在后台线程，卡顿不会带到 UI。

- [x] **Step 4: Run tests to verify they pass**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticVolumeProbeTest`
Expected: PASS

- [x] **Step 5: Commit Task 1**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiagnosticVolumeProbe.kt common/src/test/java/com/shilapi/xcertplay/DiagnosticVolumeProbeTest.kt
git commit -m "feat(diagnostics): 新增 DiagnosticVolumeProbe 实现三源卷枚举与内核 errno 真实探测"
```

---

### Task 2: 重构 DiagnosticExportStore 实现三层安全写入、物理刷盘与 FileProvider 隔离

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt`
- Modify: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportFallbackTest.kt`
- Don't touch: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportStoreTest.kt` —— `saveToDownloads`/`write` 的签名和行为都不变，那 5 例应当原样通过；它变红说明实现被改坏了，回头改实现，别改测试。

**Interfaces:**
- Consumes: `DiagnosticVolumeProbe`, `context: Context`, `fileName: String`, `report: String`, `extraMountPaths: Collection<String>`
- Produces:
  ```kotlin
  enum class ExportDestination {
      USB_ROOT,
      USB_PACKAGE_DIR,
      DEVICE_DOWNLOADS,
      DEVICE_PRIVATE,
  }

  data class ExportOutcome(
      val destination: ExportDestination,
      val primaryPath: String,
      // 内部保底文件写不出来时（车机 /data 满、私有目录被占）就没有可分享的 Uri。
      // 外置写入不该因为这个失败，所以这里必须可空，弹窗在 null 时隐藏「分享」。
      val shareableUri: Uri?,
      val probeSummary: String,
      val allSavedPaths: List<String>,
  )

  object DiagnosticExportStore {
      fun export(
          context: Context,
          fileName: String,
          report: String,
          extraMountPaths: Collection<String> = emptySet(),
      ): ExportOutcome
  }
  ```

- [x] **Step 1: Write failing tests for three-layer write and FileProvider security in DiagnosticExportFallbackTest**

在 `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportFallbackTest.kt` 添加测试用例：
```kotlin
    @Test fun exportsToUsbPackageDirWhenRootUnwritableWithFsSync() {
        // 假 U 盘根必须真的写不进去，否则按本计划的 destination 优先级（USB_ROOT 先于
        // USB_PACKAGE_DIR）结果会是 USB_ROOT，这条测试永远红。用同名文件占住 <根>/DiPlay
        // 让 mkdirs 失败——仓库里既有测试挡 reports 目录用的就是这一招。
        val fakeUsbRoot = File(context.cacheDir, "fake-usbotg-sda1").apply { mkdirs() }
        File(fakeUsbRoot, "DiPlay").writeText("blocks root writes")
        val fakePkg = File(fakeUsbRoot, "Android/data/${context.packageName}/files").apply { mkdirs() }
        val fakeContext = object : ContextWrapper(context) {
            override fun getExternalFilesDirs(type: String?): Array<File?> {
                return arrayOf(context.getExternalFilesDir(null), fakePkg)
            }
        }
        val outcome = DiagnosticExportStore.export(fakeContext, "DiPlay-usb-sync.txt", "usb content with fsync")
        assertEquals(ExportDestination.USB_PACKAGE_DIR, outcome.destination)
        assertTrue(outcome.primaryPath.contains("fake-usbotg-sda1"))
        // 内容真的落了盘：fsync 必须在关流之前，否则这里读到空文件或抛异常
        assertEquals("usb content with fsync", File(outcome.primaryPath).readText())
        // 分享 Uri 一律来自本包 Provider，U 盘路径绝不进 FileProvider
        assertEquals("${context.packageName}.diagnostic-reports", outcome.shareableUri!!.authority)
    }

    @Test fun fallsBackToDeviceDownloadsWhenNoUsbWritable() {
        val outcome = DiagnosticExportStore.export(context, "DiPlay-fallback-device.txt", "device fallback content")
        assertEquals(ExportDestination.DEVICE_DOWNLOADS, outcome.destination)
        assertTrue(outcome.primaryPath.contains("DiPlay"))
        assertTrue(outcome.probeSummary.isNotBlank())
        assertTrue(File(outcome.primaryPath).exists())
    }
```
（已实测：Robolectric 4.17 下 `FileOutputStream.fd.sync()` 能正常返回，文件内容也读得到，所以上面这条断言真的能守住「sync 在关流之前」这个顺序；而 `Os.access` 在同一个环境里不可信，见 Task 1。）

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportFallbackTest.exportsToUsbPackageDirWhenRootUnwritableWithFsSync`
Expected: FAIL with compilation error (Unresolved reference: ExportDestination).

- [x] **Step 3: Update DiagnosticExportStore implementation**

在 `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt`：
- 定义 `ExportDestination` 与 `ExportOutcome`（`shareableUri` 可空，见 Interfaces）。
- 实现带 `FileOutputStream.fd.sync()` 的物理写入辅助函数：

  ```kotlin
  /**
   * 落盘并刷到硬件。三行顺序不能动：write -> flush -> fd.sync() -> 由 use 关流。
   * 先 `bufferedWriter().use { }` 再 `fd.sync()` 是初稿的写法，那把底层流一起关掉了，
   * sync 拿到的是已失效的 fd（EBADF / Stream Closed），异常被 catch 吞掉后**每一层写入
   * 都判失败**，功能整体静默失效。
   */
  private fun syncWriteText(file: File, content: String): Boolean {
      return try {
          val parent = file.parentFile
          if (parent != null && !parent.exists() && !parent.mkdirs()) return false
          if (parent != null && !parent.canWrite()) return false
          FileOutputStream(file).use { stream ->
              stream.write(content.toByteArray(Charsets.UTF_8))
              stream.flush()
              stream.fd.sync()
          }
          true
      } catch (_: Exception) {
          false
      }
  }
  ```

- 实现 `export()`：
  1. 调用 `DiagnosticVolumeProbe.probe(context, extraMountPaths)` 获得所有卷；
  2. **b. 包目录写入**：遍历所有 `vol.packageWritable` 的卷，写向 `<vol.packageDir>/DiPlay/$fileName`（先建目录再写，用 `syncWriteText`）。成功则记录到 `allSavedPaths`；
  3. **a. 根目录写入**：遍历所有 `vol.rootWritable` 的卷，写向 `<vol.rootDir>/DiPlay/$fileName`。成功则记录到 `allSavedPaths`；
  4. **c. 本机公共目录保底**：写入 `Download/DiPlay/$fileName`（API 29+ 走 `saveToDownloads`，API < 29 走 `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` + `syncWriteText`；API 22 写公共目录依赖清单里已声明的 `WRITE_EXTERNAL_STORAGE`（`maxSdkVersion=28`），装机即授予）；
  5. **内部保底**：`saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)` 生成受管备份，用它的 `FileProvider` Uri 当 `shareableUri`。这一步**放在外置写入之后**，并且整段包 `runCatching`：`/data` 满或私有目录被占时，`shareableUri = null`，外置那份照样算成功，不能因为备份失败把整次导出判死；
  6. 对所有落到外置/公共目录的文件调 `MediaScannerConnection.scanFile(context, paths.toTypedArray(), arrayOf("text/plain"), null)`，否则车机文件管理器要拔插才看得见；
  7. 判定 `destination`（只按实际写成功的那一层算，不许按"探测到"算）：
     `USB_ROOT` -> `USB_PACKAGE_DIR` -> `DEVICE_DOWNLOADS` -> `DEVICE_PRIVATE`；
     `primaryPath` 取判定到的那一层的第一个路径，其余路径留在 `allSavedPaths` 里备查；
  8. 返回 `ExportOutcome`，`probeSummary` 直接取 `probe(context, …).summaryLine`。
- 保留 `saveWithoutPicker` 兼容既有 6 个测试；它内部的行/列逻辑不要改，`export()` 复用 `syncWriteText` 与 `saveToDownloads`，不要再复制一份写入实现。

- [x] **Step 4: Run all DiagnosticExport tests to verify they pass**

Run: `./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiagnosticExport*"`
Expected: PASS

- [x] **Step 5: Commit Task 2**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt common/src/test/java/com/shilapi/xcertplay/DiagnosticExportFallbackTest.kt
git commit -m "feat(diagnostics): DiagnosticExportStore 实现三层安全写入、强制 fsync 刷盘与 FileProvider 隔离"
```

---

### Task 3: 改造 DiPlayActivity 界面、动态广播与文案收口

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`
- Modify: `common/src/main/res/values/strings.xml`
- Modify: `common/src/main/res/values-zh-rCN/strings.xml`
- Modify: `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt`

**Interfaces:**
- Consumes: `DiagnosticExportStore.export(context, fileName, report, mountedUsbPaths)`
- Produces: 动态注册 `ACTION_MEDIA_MOUNTED` 记录热插拔路径；移除 `CreateDocument` 及「选择保存位置」按钮；单一出口根据落盘层级弹出真实结果与 12sp 诊断行。

- [x] **Step 1: Write failing UI test in DiagnosticExportUiTest**

在 `common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt`，把 `missingPickerSavesAReportAndProvidesSelectableTextInsideDiPlay` 整条替换掉（它反射 mock `export` 再调 `chooseReportDestination`，这两个入口本任务都要删）。新用例只走一个出口：

```kotlin
    @Test fun oneButtonExportReportsTheRealDestinationAndTheProbeLine() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val context = activity.applicationContext
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        ShadowContentResolver.registerProviderInternal(authority, DiagnosticReportProvider().apply { attachInfo(context, info) })
        try {
            // 无参版本：uri 参数随 CreateDocument 一起删掉，否则反射找不到 0 参方法
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "exportDiagnostics")
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ShadowAlertDialog.getLatestAlertDialog() == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val saved = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val texts = descendants(saved.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            // Robolectric 里没有 U 盘卷：必须如实说"没检测到可写 U 盘"，并给出真实落点
            assertTrue(texts.any { it.contains("No writable USB drive detected") })
            assertTrue(texts.any { it.contains("Download/DiPlay/DiPlay-") })
            // 取证行来自 probe，不是写死的
            assertTrue(texts.any { it.contains("No external volume") })
            // 「查看报告」仍然是可选中文本的正文
            saved.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val viewer = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(descendants(viewer.window!!.decorView).filterIsInstance<TextView>()
                .any { it.isTextSelectable && it.text.contains("Android 9 / API 28") })
        } finally {
            controller.pause().stop().destroy()
        }
    }
```

（实测：Robolectric 4.17 的 `getExternalFilesDirs(null)` 只返回 1 项，probe 因此必然给 `No external volume`；`Environment.getExternalStoragePublicDirectory(DOWNLOADS)` 落在 `<tmp>/external-files/Download`，所以上面两条断言是稳的，不是碰运气。）

注意：`descendants`（文件末尾那个私有函数）与 `ShadowAlertDialog` / `ShadowContentResolver` 的 import 已经在了，不用新增。同时删掉 `ActivityResultLauncher`、`ActivityOptionsCompat`、`ActivityNotFoundException`、`File`、`PackageManager` 里不再被引用的 import（Kotlin 不报错，但 lint 会挂 unused）。

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.DiagnosticExportUiTest`
Expected: FAIL——`exportDiagnostics` 这时还带 `Uri?` 参数，反射按 0 参找不到方法；就算找到，弹窗里也还印着 `diagnostic-reports` 的旧路径。

- [x] **Step 3: Update string resources in English and Chinese**

在 `common/src/main/res/values/strings.xml`：
```xml
    <string name="diagnostic_report_saved_to_usb">Report was saved to USB drive:\n%1$s\n\nYou can also share or view it here. Nothing is sent automatically.</string>
    <string name="diagnostic_report_saved_to_device_no_usb">No writable USB drive detected (or no write permission). Report was saved to head unit:\n%1$s\n\nYou can also share or view it here. Nothing is sent automatically.</string>
    <string name="no_writable_usb_detected">No writable USB drive detected</string>
    <string name="reports_save_to_usb_then_downloads">Saves to the USB drive when one is writable; otherwise to Download/DiPlay on the head unit.</string>
    <string name="diagnostic_report_save_failed_body">Storage is unavailable. Free some space and try again.</string>
```
在 `common/src/main/res/values-zh-rCN/strings.xml`：
```xml
    <string name="diagnostic_report_saved_to_usb">报告已存入 U 盘：\n%1$s\n\n您也可以在此分享或查看报告。不会自动发送任何内容。</string>
    <string name="diagnostic_report_saved_to_device_no_usb">未检测到可写 U 盘（或 U 盘无写入权限），报告已保存至车机：\n%1$s\n\n您可以在此分享或查看报告。不会自动发送任何内容。</string>
    <string name="no_writable_usb_detected">未检测到可写 U 盘</string>
    <string name="reports_save_to_usb_then_downloads">U 盘可写时存入 U 盘，否则保存到车机的 Download/DiPlay。</string>
    <string name="diagnostic_report_save_failed_body">存储不可用，请清理空间后重试。</string>
```

说明：`%1$s` 是唯一格式化参数，两条都要保留；中文串里的全角冒号不要改成半角，否则和既有文案风格不一致。删掉两个按钮后 `choose_save_location` / `choose_where_to_save_your_report` / `reports_save_to_downloads_diplay` 可能变成无引用资源，先全仓 grep 确认再删，不确定就留着（unused resource 只是 warning）。

- [x] **Step 4: Update DiPlayActivity.kt**

下面的行号按当前 HEAD 核过（不是交接文档里的老行号），改之前先对着 `grep -n` 确认一次。

1. 删去 `private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { … }`（`:218-220`）。
2. 添加动态广播接收器跟踪 U 盘热插拔（放在 `usbTopology` 附近）：
   ```kotlin
   private val mountedUsbPaths = java.util.Collections.synchronizedSet(mutableSetOf<String>())
   private val mediaMountReceiver = object : BroadcastReceiver() {
       override fun onReceive(context: Context, intent: Intent) {
           val path = intent.data?.path ?: return
           when (intent.action) {
               Intent.ACTION_MEDIA_MOUNTED -> mountedUsbPaths.add(path)
               Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL -> {
                   mountedUsbPaths.remove(path)
               }
           }
       }
   }
   ```
   注册/反注册写成一对方法，紧挨现有 `registerUsbTopology()`（`:2512`）/`unregisterUsbTopology()`（`:2520`），**调用点跟它们一致**：`onResume`（`:303`）与 `onPause`（`:310`），不是 `onCreate`/`onDestroy`——这两条广播只在页面可见时需要，跟着 onResume 走才不会漏反注册。
   ```kotlin
   private fun registerMediaMount() {
       val filter = IntentFilter().apply {
           addAction(Intent.ACTION_MEDIA_MOUNTED)
           addAction(Intent.ACTION_MEDIA_UNMOUNTED)
           addAction(Intent.ACTION_MEDIA_REMOVED)
           addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
           addDataScheme("file")
       }
       // targetSdk 37：Android 13+ 动态注册必须给导出标志，否则抛 SecurityException。
       // 媒体挂载广播只可能由系统发出，用 NOT_EXPORTED；和 registerUsbTopology 一样包 runCatching，
       // 老 ROM 上注册失败不能让设置页崩掉（探测还会走厂商根兜底）。
       runCatching { ContextCompat.registerReceiver(this, mediaMountReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
   }

   private fun unregisterMediaMount() = runCatching { unregisterReceiver(mediaMountReceiver) }
   ```
   `ContextCompat.registerReceiver` 需要 `androidx.core.content.ContextCompat`（core-ktx 1.13.1 已在依赖里）。
3. `systemCards` 的诊断卡片（`:793-807`）：
   - 删掉「选择保存位置」按钮整行（`:804`）；
   - 保存按钮（`:799-802`）去掉 `if (Build.VERSION.SDK_INT >= Q)` 分支，只调 `exportDiagnostics()`；
   - 说明文案（`:805-806`）换成 `getString(R.string.reports_save_to_usb_then_downloads) + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden)`。
4. 删去 `chooseReportDestination()`（`:2660-2665`）。**这一步会连带断两处引用，必须同时改，否则编译不过：**
   - 失败弹窗的 Positive（`:2709`，原来调它）→ 改成 `{ _, _ -> exportDiagnostics() }`，文案改用 `R.string.diagnostic_report_save_failed_body`；
   - `DiagnosticExportUiTest` 里反射调它的那个用例，Step 1 已经整条换掉。
5. 把 `exportDiagnostics(uri: Uri? = null)` 改成无参 `exportDiagnostics()`：`uri` 分支只服务 CreateDocument，留着会让反射调 0 参方法失败。线程体里的核心调用换成：
   ```kotlin
   val outcome = DiagnosticExportStore.export(
       appContext, fileName, report, mountedUsbPaths.toSet(),
   )
   ```
   仍然在 `Thread({ … }, "diplay-export")` 里跑（`buildDiagnosticReport` 也是阻塞的，别挪回主线程）。保留下面的既有行为，别顺手丢：
   - `exportInProgress` 幂等守卫、按钮禁用与 `saving_report` 文案；
   - `isFinishing || isDestroyed` 提前返回、UI 线程恢复按钮；
   - 整个 `buildDiagnosticReport` + 写入包在 `runCatching`，失败走失败弹窗。
6. 弹窗按 `destination` 分支，并把取证行做成 12sp 小字（`setMessage(CharSequence)` 会保留 span）：
   ```kotlin
   private fun probeLine(summary: String): CharSequence {
       if (summary.isBlank()) return ""
       return SpannableString("\n\n$summary").apply {
           setSpan(AbsoluteSizeSpan(12, true), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
           setSpan(ForegroundColorSpan(MUTED), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
       }
   }
   ```
   ```kotlin
   val toUsb = outcome.destination == ExportDestination.USB_ROOT || outcome.destination == ExportDestination.USB_PACKAGE_DIR
   val title = getString(if (toUsb) R.string.diagnostic_report_saved else R.string.no_writable_usb_detected)
   val body = getString(
       if (toUsb) R.string.diagnostic_report_saved_to_usb else R.string.diagnostic_report_saved_to_device_no_usb,
       outcome.primaryPath,
   )
   val builder = AlertDialog.Builder(this)
       .setTitle(title)
       .setMessage(TextUtils.concat(body, probeLine(outcome.probeSummary)))
       .setPositiveButton(getString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
       .setNegativeButton(getString(R.string.done), null)
   // 内部保底文件没写成时没有可分享的 Uri：宁可不显示「分享」，也不能拿 U 盘路径去撞 FileProvider
   outcome.shareableUri?.let { shareUri ->
       builder.setNeutralButton(getString(R.string.share)) { _, _ ->
           runCatching {
               startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                   type = "text/plain"
                   putExtra(Intent.EXTRA_STREAM, shareUri)
                   clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), shareUri)
                   addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
               }, getString(R.string.share_diagnostic_report)))
           }.onFailure { showDiagnosticReport(report) }
       }
   }
   builder.show()
   ```
   新增 import：`android.text.SpannableString`、`android.text.Spanned`、`android.text.TextUtils`、`android.text.style.AbsoluteSizeSpan`、`android.text.style.ForegroundColorSpan`、`androidx.core.content.ContextCompat`。`MUTED` 是本文件里已有的颜色常量。

- [x] **Step 5: Run compilation and tests**

Run:
```bash
./gradlew :common:compileDebugKotlin
./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.DiagnosticExport*"
```
Expected: PASS

- [x] **Step 6: Commit Task 3**

```bash
git add common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt common/src/main/res/values/strings.xml common/src/main/res/values-zh-rCN/strings.xml common/src/test/java/com/shilapi/xcertplay/DiagnosticExportUiTest.kt
git commit -m "feat(diagnostics): 移除 CreateDocument，接入热插拔监听与 U 盘一键导出真实弹窗"
```

---

### Task 4: 全量回归与完整性审查

**Files:**
- None (Verification task)

- [x] **Step 1: Run full unit test suite on common and shared**

Run:
```bash
./gradlew :shared:testDebugUnitTest
./gradlew :common:testDebugUnitTest
```
Expected: All suites PASS（`common` 基线 259 例 + 本计划新增的 4 例 probe / 2 例导出的净增，总数只增不减）。
若有既有用例变红，先判断是不是"旧测试断言的是旧行为"（比如 UI 测试断言 `diagnostic-reports` 路径），是的话按 Task 3 的新行为改断言，不是的话回退实现。

- [x] **Step 2: Compile debug (not release)**

Run:
```bash
./gradlew :common:compileDebugKotlin
./gradlew :mobile:assembleDebug
```
Expected: BUILD SUCCESSFUL.

**不要**跑 `:mobile:assembleRelease`：release 签名读 `ANDROID_KEYSTORE_PATH` 等环境变量，仓库里没有 jks，本机必失败，且这个失败与本次改动无关。

- [x] **Step 3: Lint the module**

Run:
```bash
./gradlew :common:lintDebug
```
Expected: 无新增 error。重点看两类：
- `NewApi`：`android.system.Os` / `OsConstants` / `ErrnoException` 是 API 21+，而 `minSdk = 19`；所有调用必须留在 `Build.VERSION.SDK_INT >= LOLLIPOP` 分支内（计划里的实现已经是这样），别让 IDE 的自动 import 把裸调用带出来；
- `UnusedResources`：删掉两个按钮后可能多出的 `choose_save_location` / `choose_where_to_save_your_report`，确认无引用再删，warning 可以留。

- [x] **Step 4: 残留引用与提交范围自检**

Run:
```bash
grep -rn "chooseReportDestination\|CreateDocument" common/src
git status --short
```
Expected: 第一条只剩 `DiagnosticExportUiTest` 里已经删掉的那条测试的痕迹（理想是零命中；`ActivityResultContracts` 的 import 可以留）；第二条只列本计划的 8 个文件（probe 2 + store 2 + activity/strings/ui test 4）——出现别人的文件就说明 Preflight 第 1 条没做，回去用 `git add -p`。

---

### Task 5: E01 车机验证（单测替不了的那一半）

**Files:**
- None (On-device verification)

Robolectric 4.17 最低只支持 SDK 23，E01 是 API 22；这一节是唯一能证明"插盘真的写得进去"的地方。构建 `assembleDebug`（或按项目脚本出包）装到车机，按顺序走：

- [ ] **Step 1: 不插 U 盘**点「保存诊断报告」。
  - 标题必须是「未检测到可写 U 盘」，正文路径是 `.../Download/DiPlay/DiPlay-<时间戳>.txt`；
  - 底部取证行应为 `未发现外置存储卷 (No external volume)`；
  - 截图存档。
- [ ] **Step 2: 插 U 盘，等 3–5 秒**（等 `ACTION_MEDIA_MOUNTED` 走完）再点保存。
  - 标题必须是「Diagnostic report saved / 报告已保存」；
  - 正文路径要么是 `<卷根>/DiPlay/...`，要么是 `<卷>/Android/data/com.shihab.diplay/files/DiPlay/...`；
  - 取证行应出现卷名与「可写(根目录)」或「可写(包目录)」，若带 `EACCES` 说明根目录被 FUSE 拒——这是设计稿 §三 预期的 EACCES，属于**正常**，不是失败。
- [ ] **Step 3: 真凭实据**：拔盘插到电脑（或用车上文件管理器）确认文件存在、内容非空、时间戳对得上。这一步是"绝不谎报"的唯一证据；做不到就等于没验。
- [ ] **Step 4: 拔盘后立刻再点保存**：应回到 Step 1 的结果，不能残留上一次的卷路径（验证 `mountedUsbPaths` 的 UNMOUNTED/REMOVED 清理生效）。
- [ ] **Step 5: 存档取证行原文**：把三次的取证行截图放进 `docs/`，和在 Step 2 记下的真实落点一起写进 `CHANGELOG.md`。若 E01 的挂载形态与设计稿假设不符（比如根本没有第 2 个 external files dir、也没有 `MEDIA_MOUNTED`），回来改 probe 的兜底顺序与文案，**不要改测试让它变绿**。

---


---

## 执行记录（2026-10-18，实现完成后回填）

**结论：Task 1–4 全部落地并验证；Task 5（E01 车机）没有做，下面「未做」一节写明了原因，不要把它读成已完成。**

### 提交

| 提交 | 内容 |
|---|---|
| `48f6eda2` | Task 1：DiagnosticVolumeProbe 三源卷枚举 + 内核 errno |
| `7a58b481` | Task 2：DiagnosticExportStore 四层写入 + fsync + FileProvider 隔离 |
| `7cf2bb6f` | Task 3：移除 CreateDocument，热插拔监听与真实弹窗 |
| `7fc2b280` | 修复：车机自身存储的挂载广播不再算作 U 盘 |
| `08827900` | 修复：errno 读取按 API 21 守卫（lint NewApi） |
| `bbed6874` | 对齐修订版计划（onResume 注册、setMessage 取证行、私有目录保底） |

### 验证结果（本机）

| 命令 | 结果 |
|---|---|
| `./gradlew :shared:testDebugUnitTest` | 493 例，0 失败（1 例既有 skip） |
| `./gradlew :common:testDebugUnitTest` | 273 例，0 失败（基线 265；净增 8 = probe 6 + 导出 2，UI 旧例 1 条被 2 条替换） |
| `./gradlew :mobile:assembleDebug` | BUILD SUCCESSFUL，APK 已产出 |
| `./gradlew :common:lintDebug` | 0 error（修完 4 条 NewApi 后；其余 119 条 warning 是既有的） |
| `grep -rn "chooseReportDestination\|CreateDocument" common/src` | 零命中 |

### 与计划不一致的地方（都有理由，别当成遗漏）

1. **probe 测试比计划的 4 例多 2 例**：`aVolumeRootBlockedByAFileIsReportedAsNotADirectory`（同名文件挡根目录时必须是 `ENOTDIR`，否则计划里那句"用文件挡住根目录"的测法只会得到 `FileNotFoundException`）和 `theHeadUnitsOwnStorageIsNeverCountedAsAStick`（见第 4 条）。
2. **多一条 U 盘分支的 UI 测试**：`aReportOnAStickSaysItWentToTheStickAndNamesTheFile` 断言弹窗里的路径真的存在且内容非空——计划只写了"没有 U 盘"那半边。
3. **`fallsBackToDeviceDownloadsWhenNoUsbWritable` 断言的是具体取证行**（`未发现外置存储卷`），不是计划里的 `isNotBlank()`：Robolectric 里确实没有第二卷，断言可以更严。
4. **新增修复：主存储挂载广播不算 U 盘**。`ACTION_MEDIA_MOUNTED` 对车机自身存储同样会发；照单全收会把 `/storage/emulated/0` 当 U 盘，弹窗就会谎报"已存入 U 盘"——正是本功能要消灭的那句话。probe 现在排除主存储根。
5. **c 层（Download）按修订版计划每次都写**，不只是 USB 失败时写；`destination` 仍按 USB_ROOT → USB_PACKAGE_DIR → DEVICE_DOWNLOADS → DEVICE_PRIVATE 的优先级判定，所以弹窗说的仍是实际那份。
6. **`syncWriteText` 里 `fd.sync()` 必须在关流之前**：初稿那种 `bufferedWriter().use { }` 再 `fd.sync()` 拿到的是已关闭的 fd，异常被吞掉后每一层都判失败，功能会整体静默失效。实现按修订版计划补充了 `flush()` 与父目录 `canWrite()` 检查。
7. **`ExportOutcome.shareableUri` 可空**，测试里必须 `outcome.shareableUri!!.authority`；计划最初那版片段直接 `.authority` 编译不过。

### 未做（不是遗漏）

- **Task 5：E01 车机验证**。Robolectric 4.17 最低 SDK 23，单测只证明枚举/写入/文案逻辑；E01 到底给不给 `getExternalFilesDirs` 第 2 项、FUSE 允不允许写包目录、`MEDIA_MOUNTED` 带什么 data，只有插上真车机能证明。这一节的验收条件原样保留在下面，未勾选。
- **`:mobile:assembleRelease`**：preflight 第 4 条——release 签名要 `ANDROID_KEYSTORE_*`，本机没有 jks；Task 4 用 debug 构建 + lint 代替。

### 施工环境记录

施工期间工作树里有另一条线（座舱 UI / QuickHud）在同时改 `DiPlayActivity.kt`、两个 `strings.xml`、`HeroStageCard.kt`、`SettingsDrawerView.kt`。本计划的提交只用 `git add -p` 的方式挑本计划 hunk（对方新增的 `pair_iphone_to_connect` / `hud_*` / `close_settings` 等仍未提交、未被触碰）。中途出现过两次对方半成品导致的编译失败和一次 `gradlew --stop` 打断构建，重试后均恢复。计划文档本身也在施工中被改写（585 → 754 行），最后一轮 `bbed6874` 就是按修订版对齐的。

## 完成定义（Definition of Done）

- 代码：8 个文件按 Task 1–3 落地，`git status` 里没有本计划之外的文件被带进提交。
- 测试：`:common:testDebugUnitTest` 全绿，净增 6 例（probe 4 + 导出 2），UI 旧例被同数量替换；`:shared:testDebugUnitTest` 不受影响。
- 行为：设置页诊断卡片只剩一个保存按钮；`ChooseReportDestination` / `CreateDocument` 全仓零命中；弹窗标题与正文只按"真的写进了哪一层"分支。
- 取证：不插盘 / 插盘 / 拔盘后三种情况的弹窗截图各一张，落盘文件在电脑上能打开且非空。
- 未决：Preflight 第 3 条的按钮可见性拍板；若用户改主意，按该条给的 `onResume` 缓存方案补一步，别在 `render()` 里试写。
