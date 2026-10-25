# 星瑞仪表盘媒体直连稳定性与健壮性重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底消除星瑞仪表盘直连通道中的 Native SIGSEGV 闪退风险、无封面歌名被原厂清屏吞掉的问题、断开连接清屏失效、ServiceConnection 泄漏、以及暂停/播放频繁写盘的 I/O 磨损，同时恢复标准轻量 AOSP 媒体广播以支持第三方桌面生态。

**Architecture:** 
1. `CoverSlotManager` 增加开机自建自愈的纯黑空白图 (`cover_blank.jpg`)，无图时返回空白图 URI，主动覆盖冲刷旧图并防止 IPKServer 触发 `-102` 清屏。
2. `XingruiInstrumentBridge` 增加原厂服务探针（非星瑞车机优雅禁用）、修正 `linkToDeath`/`unlinkToDeath` 与重连逻辑、增加 Bitmap 引用去重防止重复压缩写盘，并在 `release()` 中执行同步清屏。
3. `CarPlayMediaKeys` 废除多线程并发环境下的手动 `Bitmap.recycle()` 杜绝 Native 段错误，并恢复轻量文本 AOSP 广播以保障嘟嘟桌面/第三方桌面小部件正常工作。

**Tech Stack:** Kotlin, Android Lollipop (API 21+), AIDL IPC (IDimTransListener), Android MediaSession, Robolectric / JUnit 4.

**Spec / Issue Reference:** 
- Commit `1e026311` 引入的系列缺陷排查与逆向东软 `IPKServer`（`com.neusoft.shockwave.instrumentserver`）行为对齐报告。

## Global Constraints

- **最低兼容平台**：Android 5.1 (Lollipop MR1, API 22) 吉利 E01 / MT6735 芯片车机。
- **内存与稳定性底线**：禁止在后台线程访问 Bitmap 期间在主线程调用 `recycle()`；严禁 Native SIGSEGV。
- **协议兼容性**：无封面时严禁向 `MediaService` 传空字符串 `""`；断开 CarPlay 时必须同步发送 `""` 触发 `-102` 清屏。
- **I/O 损耗控制**：暂停/继续播放严禁触发重复 JPEG 压缩写盘；双槽封面文件总量不得超过 3 个（cover_0, cover_1, cover_blank）。

---

### Task 1: 重构 `CoverSlotManager`：增加自愈空白封面与健壮路径构建

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/CoverSlotManager.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt`

**Interfaces:**
- Consumes: `context: Context`, `bitmap: Bitmap?`
- Produces: `saveCover(bitmap: Bitmap?): String`, `getBlankCoverUri(): String`

- [ ] **Step 1: 编写针对自愈空白图与健壮路径的失败测试**

在 `common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt` 中添加测试：
```kotlin
    @Test
    fun coverSlotManagerGeneratesBlankCoverAndFallsBackSafely() {
        val manager = CoverSlotManager(context)
        val blankUri = manager.getBlankCoverUri()
        assertTrue("Blank cover URI must be a valid file URI", blankUri.startsWith("file:///sdcard/"))
        assertTrue("Blank cover URI must point to cover_blank.jpg", blankUri.endsWith("cover_blank.jpg"))

        // Null or recycled bitmap must return blank cover URI instead of empty string
        assertEquals(blankUri, manager.saveCover(null))

        val bmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bmp.recycle()
        assertEquals(blankUri, manager.saveCover(bmp))
    }
```

- [ ] **Step 2: 运行测试验证失败**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.CarPlayMediaKeysBroadcastTest.coverSlotManagerGeneratesBlankCoverAndFallsBackSafely"`
预期：编译失败或测试断言失败（`getBlankCoverUri` 未实现）。

- [ ] **Step 3: 实现 `CoverSlotManager` 的自愈空白图与路径格式化**

修改 `common/src/main/java/com/shilapi/xcertplay/instrument/CoverSlotManager.kt`：
```kotlin
package com.shilapi.xcertplay.instrument

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

internal class CoverSlotManager(private val context: Context) {
    private val coversDir: File by lazy {
        val baseDir = context.externalCacheDir 
            ?: File(Environment.getExternalStorageDirectory(), "Android/data/${context.packageName}/cache")
        File(baseDir, "covers").apply {
            if (!exists()) {
                runCatching { mkdirs() }
            }
        }
    }

    private val slotFiles by lazy {
        arrayOf(
            File(coversDir, "cover_0.jpg"),
            File(coversDir, "cover_1.jpg")
        )
    }

    private val blankFile: File by lazy {
        File(coversDir, "cover_blank.jpg").apply {
            if (!exists() || length() == 0L) {
                runCatching {
                    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565).apply {
                        eraseColor(Color.BLACK)
                    }
                    FileOutputStream(this@apply).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        out.flush()
                    }
                }.onFailure { Log.w(TAG, "Failed to create blank cover fallback", it) }
            }
        }
    }

    @Volatile
    private var currentSlot = 0

    fun getBlankCoverUri(): String = toSdcardUri(blankFile)

    /**
     * Saves bitmap to the alternate slot and returns the formatted URI for IPKServer.
     * Returns blank cover URI if save fails or bitmap is null/recycled.
     */
    fun saveCover(bitmap: Bitmap?): String {
        if (bitmap == null || bitmap.isRecycled) return getBlankCoverUri()
        return try {
            val nextSlot = 1 - currentSlot
            val targetFile = slotFiles[nextSlot]

            val parent = targetFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            FileOutputStream(targetFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.flush()
            }
            currentSlot = nextSlot
            toSdcardUri(targetFile)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to save cover art safely, falling back to blank cover", t)
            getBlankCoverUri()
        }
    }

    private fun toSdcardUri(file: File): String {
        return "file:///sdcard/Android/data/${context.packageName}/cache/covers/${file.name}"
    }

    companion object {
        private const val TAG = "CoverSlotManager"
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.CarPlayMediaKeysBroadcastTest.coverSlotManagerGeneratesBlankCoverAndFallsBackSafely"`
预期：PASS。

- [ ] **Step 5: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/CoverSlotManager.kt common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt
git commit -m "fix(instrument): 增加自愈纯黑空白封面与健壮SD卡URI构建"
```

---

### Task 2: 重构 `XingruiInstrumentBridge`：服务探针、去重缓存、消除泄漏与同步清屏

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/instrument/XingruiInstrumentBridge.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt`

**Interfaces:**
- Consumes: `Context`, `title`, `artist`, `album`, `artwork: Bitmap?`, `isPlaying: Boolean`
- Produces: `updateMedia(...)`, `clearMedia()`, `release()`, `isAvailable(): Boolean`

- [ ] **Step 1: 编写桥接器同步释放与空封面防御的测试用例**

在 `common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt` 中增加用例验证：
- 构造 `XingruiInstrumentBridge` 并连续调用 `updateMedia`。
- 相同 Bitmap 多次调用测试。
- `release()` 之后状态测试。

- [ ] **Step 2: 运行测试确认基准**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.CarPlayMediaKeysBroadcastTest.instrumentBridgeExecutesSafelyWithoutCrashing"`
预期：PASS。

- [ ] **Step 3: 实现 `XingruiInstrumentBridge` 的完善重构**

修改 `common/src/main/java/com/shilapi/xcertplay/instrument/XingruiInstrumentBridge.kt`：
- 添加 `isSupported` 检查，仅当目标 Service 存在时才绑定。
- 维护 `lastArtworkRef: WeakReference<Bitmap>?` 与 `lastCoverUri: String`，去重避免暂停/播放写盘。
- 修复 `DeathRecipient`：注销旧 `unlinkToDeath`，不重复 `safeBind()`。
- 修复 `release()`：在主线程直接同步调用 `dimService?.updateMedia(0, " ", " ", " ", "", 6)` 下发 `-102` 复位，解绑并关闭线程池。

```kotlin
package com.shilapi.xcertplay.instrument

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.ecarx.xui.adaptapi.diminteraction.IDimTransListener
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class XingruiInstrumentBridge(context: Context) {
    private val appContext = context.applicationContext
    private val coverManager = CoverSlotManager(appContext)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "XingruiInstrument-Worker").apply { isDaemon = true }
    }

    private val targetIntent = Intent().apply {
        component = ComponentName(
            "com.neusoft.shockwave.instrumentserver",
            "com.ecarx.xui.adaptapi.diminteraction.MediaService"
        )
    }

    val isSupported: Boolean = runCatching {
        appContext.packageManager.resolveService(targetIntent, 0) != null
    }.getOrDefault(false)

    @Volatile
    private var dimService: IDimTransListener? = null
    @Volatile
    private var boundBinder: IBinder? = null
    private val isBound = AtomicBoolean(false)

    // Bitmap 引用去重缓存，防止暂停/播放重复做写盘 I/O
    private var lastArtworkRef: WeakReference<Bitmap>? = null
    private var lastCoverUri: String? = null

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "IPKServer MediaService died, clearing local stub")
        boundBinder?.unlinkToDeath(this@XingruiInstrumentBridge.deathRecipient, 0)
        boundBinder = null
        dimService = null
        isBound.set(false)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to Xingrui IPK MediaService: $name")
            boundBinder = service
            try {
                service?.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                Log.w(TAG, "linkToDeath failed", e)
            }
            dimService = IDimTransListener.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Disconnected from Xingrui IPK MediaService: $name")
            boundBinder?.unlinkToDeath(deathRecipient, 0)
            boundBinder = null
            dimService = null
            isBound.set(false)
        }
    }

    init {
        if (isSupported) {
            safeBind()
        }
    }

    private fun safeBind() {
        if (!isSupported || isBound.get()) return
        try {
            val success = appContext.bindService(targetIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            isBound.set(success)
            Log.i(TAG, "bindService to MediaService result=$success")
        } catch (t: Throwable) {
            Log.w(TAG, "safeBind encountered exception", t)
        }
    }

    fun updateMedia(
        title: String?,
        artist: String?,
        album: String?,
        artwork: Bitmap?,
        isPlaying: Boolean
    ) {
        if (!isSupported || executor.isShutdown) return
        executor.execute {
            try {
                if (dimService == null && !isBound.get()) {
                    safeBind()
                }

                val service = dimService
                if (service == null) {
                    Log.d(TAG, "MediaService not ready yet, skip update")
                    return@execute
                }

                val safeTitle = if (!title.isNullOrBlank()) title else " "
                val safeArtist = if (!artist.isNullOrBlank()) artist else " "
                val safeAlbum = if (!album.isNullOrBlank()) album else " "

                // 封面去重与空白图保障
                val coverPath = if (artwork != null && !artwork.isRecycled) {
                    val prevBmp = lastArtworkRef?.get()
                    if (prevBmp === artwork && lastCoverUri != null) {
                        lastCoverUri!!
                    } else {
                        val uri = coverManager.saveCover(artwork)
                        lastArtworkRef = WeakReference(artwork)
                        lastCoverUri = uri
                        uri
                    }
                } else {
                    lastArtworkRef = null
                    lastCoverUri = null
                    coverManager.getBlankCoverUri()
                }

                try {
                    service.onSourceChanged(SOURCE_ONLINE_MUSIC)
                    service.updateMedia(0, safeTitle, safeAlbum, safeArtist, coverPath, SOURCE_ONLINE_MUSIC)
                    service.onAction(if (isPlaying) 1 else 2, SOURCE_ONLINE_MUSIC)
                    Log.d(TAG, "Sent to Xingrui IPK: $safeTitle - $safeArtist (cover=$coverPath, playing=$isPlaying)")
                } catch (e: RemoteException) {
                    Log.w(TAG, "RemoteException calling MediaService, will reconnect", e)
                    dimService = null
                    isBound.set(false)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Safe catch-all to prevent any crash in instrument bridge", t)
            }
        }
    }

    fun clearMedia() {
        if (!isSupported || executor.isShutdown) return
        executor.execute {
            try {
                val service = dimService ?: return@execute
                try {
                    service.updateMedia(0, " ", " ", " ", "", SOURCE_ONLINE_MUSIC)
                    service.onAction(2, SOURCE_ONLINE_MUSIC)
                } catch (e: RemoteException) {
                    Log.w(TAG, "RemoteException while clearing media", e)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in clearMedia", t)
            }
        }
    }

    fun release() {
        try {
            // 同步清屏：在当前线程直接调用已有 service 引用，确保解绑前指令送达
            dimService?.let { service ->
                runCatching {
                    service.updateMedia(0, " ", " ", " ", "", SOURCE_ONLINE_MUSIC)
                    service.onAction(2, SOURCE_ONLINE_MUSIC)
                }
            }
            boundBinder?.unlinkToDeath(deathRecipient, 0)
            boundBinder = null
            dimService = null
            if (isBound.compareAndSet(true, false)) {
                appContext.unbindService(serviceConnection)
            }
            executor.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "Error during release", t)
        }
    }

    companion object {
        private const val TAG = "XingruiInstrument"
        const val SOURCE_ONLINE_MUSIC = 6
    }
}
```

- [ ] **Step 4: 运行单元测试验证**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.CarPlayMediaKeysBroadcastTest.instrumentBridgeExecutesSafelyWithoutCrashing"`
预期：PASS。

- [ ] **Step 5: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/instrument/XingruiInstrumentBridge.kt
git commit -m "fix(instrument): 修复服务探针、去重缓存、DeathRecipient与同步解绑清屏"
```

---

### Task 3: 改造 `CarPlayMediaKeys`：消除 Bitmap 并发释放段错误并恢复轻量 AOSP 广播

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt`

**Interfaces:**
- Consumes: `CarPlayNowPlaying`, `Bitmap`, `Context`
- Produces: `broadcastMediaChange(...)`, `publishMetadata(...)`, `updateArtwork(...)`

- [ ] **Step 1: 编写恢复 AOSP 广播与 Bitmap 安全引用的测试**

在 `common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt` 中恢复对 `com.android.music.metachanged` 和 `com.android.music.playstatechanged` 的验证断言。

- [ ] **Step 2: 运行测试验证失败**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.CarPlayMediaKeysBroadcastTest"`
预期：FAIL（AOSP 广播未发送）。

- [ ] **Step 3: 修改 `CarPlayMediaKeys.kt`**

1. 废除 `updateArtwork` 中的 `old.recycle()`，全交由 GC 安全回收，从根本上杜绝 Native SIGSEGV 段错误。
2. 恢复轻量级 AOSP 广播逻辑（只广播 track, artist, album, duration, position 等纯文本/数值，不携带 Bitmap，杜绝 TransactionTooLargeException），为第三方桌面 Launcher 及普通车机提供兼容。

- [ ] **Step 4: 运行所有单元测试验证通过**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest`
预期：BUILD SUCCESSFUL。

- [ ] **Step 5: 提交更改**

```bash
git add common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt common/src/test/java/com/shilapi/xcertplay/CarPlayMediaKeysBroadcastTest.kt
git commit -m "fix(media): 废除手动recycle规避Native崩溃，恢复轻量AOSP广播兜底生态"
```

---

### Task 4: 全工程集成与回归验证

**Files:**
- Test: All tests in `:common`, `:automotive`, `:mobile`, `:shared`, `:governance`

- [ ] **Step 1: 运行全量单元测试与编译检查**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew test`
预期：所有模块单元测试 100% 通过。

- [ ] **Step 2: 验证 APK 正常装配构建**

运行：`cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew assembleDebug`
预期：APK 正常生成，无任何编译错误或打包冲突。

- [ ] **Step 3: 提交并打标签**

```bash
git commit --allow-empty -m "chore(release): 星瑞仪表盘媒体直连全面加固与防爆验证通过"
```
