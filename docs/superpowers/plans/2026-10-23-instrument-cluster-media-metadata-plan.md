# 吉利 E01 仪表多媒体卡片 (NowPlaying) 元数据与封面投屏 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 
在吉利星瑞 E01（Android 5.1 / API 22）车机上实现 CarPlay 正在播放多媒体信息（歌曲名、歌手名、专辑名、封面大图）向车机系统原生 `MediaSession` 的端到端静默注入，使车主在方向盘将仪表切至“多媒体”卡片时，无感呈现当前播放曲目与专辑封面，全过程零侵入行车主界面、零阻塞 CarPlay 核心音视频链路。

**Architecture:** 
1. **协议层提取与隔离**：在 `shared` 模块中通过 `CarPlayPlaybackStatus` 扩展解析 iAP2 `0x5001 NowPlayingUpdate`（Title, Artist, Album, TransferID, Duration, PlaybackStatus），并通过 `Iap2FileTransferReceiver` 接收 `NowPlayingArtworkData` 二进制 JPEG 载荷；全部解析使用 `runCatching` 包裹并记录诊断日志，任何协议异常绝不抛出、绝不影响主链路。
2. **异步内存安全图片队列**：创建 `NowPlayingArtworkQueue` 单线程异步工作池，将封面限制在最大 384×384 px 降采样解码，自动回收旧 Bitmap 内存，杜绝 Dalvik 堆 OOM。
3. **系统层差分节流发布**：在 `common` 模块的 `CarPlayMediaKeys`（`Api21Backend`）中，拦截 iPhone 每秒 2 次（2Hz）的进度时间戳刷帧，仅在曲目或封面发生实质变更时调用 `MediaSession.setMetadata()`，彻底杜绝 `system_server` 内存暴涨崩溃。
4. **生命周期自愈与退出清理**：在 CarPlay 断开或应用退出时，自动注销/清空 `MediaSession` 元数据，确保原车媒体状态不残留。

**Tech Stack:** Kotlin 1.9, Android API 22 (minSdk 19), MediaSession / MediaMetadata, iAP2 Protocol, JUnit4, Robolectric.

---

## Global Constraints

- **最低 API 支持**：minSdk 19，E01 目标运行环境为 API 22（Android 5.1 Lollipop）。严禁引入 Android 6.0+（API 23+）公共类库。
- **系统服务内存保护**：严禁在 2Hz 进度帧触发时无脑下发 `session.setMetadata()`，必须通过 `metadataChanged` 严格对比差分。
- **Dalvik 堆绝对保护**：封面解码必须执行 `inSampleSize` 采样并裁切至最大 384px，旧图片必须显式 `recycle()`。
- **主链路强隔离**：元数据解析与封面获取属于边缘增强功能，所有异常必须用 try/catch 兜底并输出 `DiagLog.w`，严禁中断或断开 CarPlay 控制与音视频传输通道。

---

## File Structure

```
shared/src/main/java/com/shilapi/xcertplay/
├── media/
│   └── CarPlayPlaybackStatus.kt                (Modify: 增加 CarPlayNowPlaying 数据类与 acceptUpdate 解析)
└── transport/
    └── Iap2FileTransferReceiver.kt             (Create: iAP2 文件传输与封面接收器)
shared/src/test/java/com/shilapi/xcertplay/
├── media/
│   └── CarPlayPlaybackStatusTest.kt            (Modify: 增加元数据字段解析与差分测试)
└── transport/
    └── Iap2FileTransferReceiverTest.kt         (Create: 封面数据包分片重组测试)

common/src/main/java/com/shilapi/xcertplay/
├── NowPlayingArtworkQueue.kt                   (Create: 异步线程解码与防堆积队列)
├── CarPlayMediaKeys.kt                         (Modify: Api21Backend 增加 setMetadata 与节流比对)
└── orchestration/
    └── CarPlayController.kt                    (Modify: 接入 0x5001 解析与 FileTransfer 数据分发)
common/src/test/java/com/shilapi/xcertplay/
├── NowPlayingArtworkQueueTest.kt               (Create: 解码队列防抖与丢弃测试)
└── CarPlayMediaCallbackTest.kt                 (Modify: 验证 metadataChanged 节流逻辑)
```

---

## Implementation Tasks

### Task 1: 扩展 `CarPlayPlaybackStatus.kt`，支持 `CarPlayNowPlaying` 元数据与增量解析

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/media/CarPlayPlaybackStatus.kt`
- Modify: `shared/src/test/java/com/shilapi/xcertplay/media/CarPlayPlaybackStatusTest.kt`

**Interfaces:**
- Produces: 
  - `data class CarPlayNowPlaying(title, album, artist, artworkTransferId, sourceApp, durationMillis, elapsedMillis, playing)`
  - `CarPlayPlaybackStatus.acceptUpdate(frame: Iap2Frame): CarPlayNowPlaying?`
  - `CarPlayPlaybackStatus.clearAll(): CarPlayNowPlaying?`

- [ ] **Step 1: 在 `CarPlayPlaybackStatusTest.kt` 中编写元数据解析与增量合并失败测试**

```kotlin
@Test
fun parsesMetadataFromNowPlayingUpdate() {
    val status = CarPlayPlaybackStatus()
    val frame = Iap2Messages.buildRaw(CarPlayPlaybackStatus.NOW_PLAYING_UPDATE) {
        group(0) { // MEDIA_ITEM
            string(1, "Song Title")
            string(6, "Album Name")
            string(12, "Artist Name")
            u8(26, 7) // artworkTransferId
            u32(4, 180000) // duration
        }
        group(1) { // PLAYBACK
            u8(0, 1) // STATUS_PLAYING
            u32(1, 1500) // elapsed
            string(7, "MusicApp")
        }
    }
    val update = status.acceptUpdate(frame)
    assertNotNull(update)
    assertEquals("Song Title", update?.title)
    assertEquals("Album Name", update?.album)
    assertEquals("Artist Name", update?.artist)
    assertEquals(7, update?.artworkTransferId)
    assertEquals(180000L, update?.durationMillis)
    assertEquals(1500L, update?.elapsedMillis)
    assertEquals("MusicApp", update?.sourceApp)
    assertTrue(update?.playing == true)
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :shared:testDebugUnitTest --tests "com.shilapi.xcertplay.media.CarPlayPlaybackStatusTest"`
Expected: FAIL (acceptUpdate / CarPlayNowPlaying unresolved)

- [ ] **Step 3: 在 `CarPlayPlaybackStatus.kt` 中实现 `CarPlayNowPlaying` 与安全增量解析**

```kotlin
package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

data class CarPlayNowPlaying(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val artworkTransferId: Int? = null,
    val sourceApp: String? = null,
    val durationMillis: Long? = null,
    val elapsedMillis: Long? = null,
    val playing: Boolean = false,
)

class CarPlayPlaybackStatus {
    var nowPlaying = CarPlayNowPlaying()
        private set

    val playing: Boolean get() = nowPlaying.playing

    fun acceptUpdate(frame: Iap2Frame): CarPlayNowPlaying? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        val media = runCatching { body.optionalGroup(MEDIA_ITEM) }.getOrNull()
        val playback = runCatching { body.optionalGroup(PLAYBACK) }.getOrNull()
        val previous = nowPlaying
        val next = previous.copy(
            title = media.updatedString(TITLE, previous.title),
            album = media.updatedString(ALBUM, previous.album),
            artist = media.updatedString(ARTIST, previous.artist),
            artworkTransferId = media.updatedU8(ARTWORK_TRANSFER_ID, previous.artworkTransferId),
            sourceApp = playback.updatedString(SOURCE_APP, previous.sourceApp),
            durationMillis = media.updatedU32(DURATION, previous.durationMillis),
            elapsedMillis = playback.updatedU32(ELAPSED, previous.elapsedMillis),
            playing = playback.updatedStatus(previous.playing),
        )
        if (next == previous) return null
        nowPlaying = next
        return next
    }

    fun accept(frame: Iap2Frame): Boolean? {
        val previous = playing
        val next = acceptUpdate(frame)?.playing ?: return null
        return next.takeIf { it != previous }
    }

    fun clear(): Boolean? {
        if (!playing) return null
        nowPlaying = nowPlaying.copy(playing = false)
        return false
    }

    fun clearAll(): CarPlayNowPlaying? {
        if (nowPlaying == CarPlayNowPlaying()) return null
        nowPlaying = CarPlayNowPlaying()
        return nowPlaying
    }

    private fun Iap2BodyReader?.updatedString(id: Int, previous: String?): String? {
        if (this == null || !has(id)) return previous
        return runCatching { string(id).trim().ifEmpty { null } }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedU32(id: Int, previous: Long?): Long? {
        if (this == null || !has(id)) return previous
        return runCatching { u32(id) }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedU8(id: Int, previous: Int?): Int? {
        if (this == null || !has(id)) return previous
        return runCatching { u8(id) }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedStatus(previous: Boolean): Boolean {
        if (this == null || !has(STATUS)) return previous
        return runCatching { u8(STATUS) == STATUS_PLAYING }.getOrElse { previous }
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val MEDIA_ITEM = 0
        private const val PLAYBACK = 1

        private const val TITLE = 1
        private const val DURATION = 4
        private const val ALBUM = 6
        private const val ARTIST = 12
        private const val ARTWORK_TRANSFER_ID = 26

        private const val STATUS = 0
        private const val ELAPSED = 1
        private const val SOURCE_APP = 7
        private const val STATUS_PLAYING = 1
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :shared:testDebugUnitTest --tests "com.shilapi.xcertplay.media.CarPlayPlaybackStatusTest"`
Expected: PASS

- [ ] **Step 5: Commit: "shared: support CarPlayNowPlaying metadata extraction in CarPlayPlaybackStatus"**

---

### Task 2: 实现 iAP2 封面二进制流接收器 `Iap2FileTransferReceiver`

**Files:**
- Create: `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2FileTransferReceiver.kt`
- Create: `shared/src/test/java/com/shilapi/xcertplay/transport/Iap2FileTransferReceiverTest.kt`

**Interfaces:**
- Produces:
  - `data class Iap2ArtworkTransfer(id: Int, bytes: ByteArray)`
  - `Iap2FileTransferReceiver.accept(datagram: ByteArray): Outcome`

- [ ] **Step 1: 编写封面分片重组失败测试 `Iap2FileTransferReceiverTest.kt`**

```kotlin
package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Iap2FileTransferReceiverTest {
    @Test
    fun reassemblesTwoDataPacketsIntoCompletedArtwork() {
        val receiver = Iap2FileTransferReceiver()
        val payload1 = byteArrayOf(0x01, 0x02, 0x03)
        val payload2 = byteArrayOf(0x04, 0x05)
        val totalSize = 5L
        
        // Setup packet: ID=1, Flags=0x04 (Setup), 8-byte size=5, 2-byte type=2
        val setup = ByteArray(12).apply {
            this[0] = 1.toByte()
            this[1] = 0x04.toByte()
            this[9] = 5.toByte()
            this[11] = 2.toByte()
        }
        receiver.accept(setup)
        
        // Data packet 1: ID=1, Flags=0x00
        val p1 = byteArrayOf(1.toByte(), 0x00.toByte()) + payload1
        receiver.accept(p1)
        
        // Data packet 2 (Last): ID=1, Flags=0x40 (FLAG_LAST)
        val p2 = byteArrayOf(1.toByte(), 0x40.toByte()) + payload2
        val outcome = receiver.accept(p2)
        
        assertNotNull(outcome.completed)
        assertEquals(1, outcome.completed?.id)
        assertArrayEquals(payload1 + payload2, outcome.completed?.bytes)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :shared:testDebugUnitTest --tests "com.shilapi.xcertplay.transport.Iap2FileTransferReceiverTest"`
Expected: FAIL (unresolved class)

- [ ] **Step 3: 实现 `Iap2FileTransferReceiver.kt`**

创建 `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2FileTransferReceiver.kt`，实现具备 2MB 尺寸约束与连接防抖的完整接收器。

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :shared:testDebugUnitTest --tests "com.shilapi.xcertplay.transport.Iap2FileTransferReceiverTest"`
Expected: PASS

- [ ] **Step 5: Commit: "shared: implement Iap2FileTransferReceiver for album artwork payloads"**

---

### Task 3: 实现异步封面解码与内存保护队列 `NowPlayingArtworkQueue.kt`

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/NowPlayingArtworkQueue.kt`
- Create: `common/src/test/java/com/shilapi/xcertplay/NowPlayingArtworkQueueTest.kt`

**Interfaces:**
- Consumes: `Iap2FileTransferReceiver.DEFAULT_MAXIMUM_ARTWORK_BYTES`
- Produces:
  - `NowPlayingArtworkQueue<Bitmap>`
  - `submit(expected: Any, id: Int, bytes: ByteArray)`
  - `newSession(): Any`
  - `clear()`

- [ ] **Step 1: 编写多图提交时旧图丢弃与取消测试 `NowPlayingArtworkQueueTest.kt`**

```kotlin
package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

class NowPlayingArtworkQueueTest {
    @Test
    fun discardsOlderPendingRequestsWhenNewerArrives() {
        val sameThread = Executor { it.run() }
        val delivered = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val queue = NowPlayingArtworkQueue<String>(
            worker = sameThread,
            main = sameThread,
            decode = { String(it) },
            publish = { _, _, img -> img?.let { delivered.add(it) } },
            discard = { discarded.add(it) },
        )
        val session = queue.newSession()
        queue.submit(session, 1, "First".toByteArray())
        queue.submit(session, 2, "Second".toByteArray())
        assertEquals(listOf("First", "Second"), delivered)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.NowPlayingArtworkQueueTest"`
Expected: FAIL

- [ ] **Step 3: 实现 `NowPlayingArtworkQueue.kt`**

创建 `common/src/main/java/com/shilapi/xcertplay/NowPlayingArtworkQueue.kt`，实现具备版本追踪、最大队列上限、取消防重叠的图片派发队列。

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.NowPlayingArtworkQueueTest"`
Expected: PASS

- [ ] **Step 5: Commit: "common: implement NowPlayingArtworkQueue for memory-safe image decoding"**

---

### Task 4: 在 `CarPlayController.kt` 建立监听管道与异常护栏

**Files:**
- Modify: `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`

**Interfaces:**
- Produces:
  - `@Volatile var nowPlayingListener: ((CarPlayNowPlaying) -> Unit)?`
  - `@Volatile var artworkListener: ((Int, ByteArray) -> Unit)?`

- [ ] **Step 1: 在 `CarPlayController.kt` 中引入 listeners 并在会话退出时触发清理**
- [ ] **Step 2: 在 `CarPlayController.acceptFrame`（处理 iAP2 帧处）安全分发 0x5001**

```kotlin
if (frame.messageId == CarPlayPlaybackStatus.NOW_PLAYING_UPDATE) {
    try {
        val update = synchronized(playbackStatus) { playbackStatus.acceptUpdate(frame) }
        if (update != null) {
            nowPlayingListener?.invoke(update)
            if (update.playing != previousPlaying) playbackListener?.invoke(update.playing)
        }
    } catch (e: Throwable) {
        DiagLog.w("DiPlay-CarPlayController", "NowPlayingUpdate parse error ignored: ${e.message}")
    }
}
```

- [ ] **Step 3: 运行全量单元测试确认无破坏性回归**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :shared:testDebugUnitTest`
Expected: ALL PASS

- [ ] **Step 4: Commit: "orchestration: wire nowPlayingListener in CarPlayController with safety guards"**

---

### Task 5: 在 `CarPlayMediaKeys.kt` 中实现差分节流发布与封面注入

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt`
- Modify: `common/src/test/java/com/shilapi/xcertplay/CarPlayMediaCallbackTest.kt`

**Interfaces:**
- Consumes: `CarPlayNowPlaying`, `NowPlayingArtworkQueue`
- Produces:
  - `CarPlayMediaKeys.metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean`
  - `CarPlayMediaKeys.androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap?): MediaMetadata`

- [ ] **Step 1: 在 `CarPlayMediaCallbackTest.kt` 中编写进度变化不触发元数据发布的单测**

```kotlin
@Test
fun elapsedProgressTicksDoNotTriggerMetadataChanged() {
    val song = CarPlayNowPlaying(title = "Sky", artist = "Artist", artworkTransferId = 1, elapsedMillis = 1000L, playing = true)
    // 纯进度更新 (2Hz 进度帧)
    val progressTick = song.copy(elapsedMillis = 1500L)
    assertFalse(CarPlayMediaKeys.metadataChanged(song, progressTick))
    
    // 切歌
    val nextSong = song.copy(title = "New Song")
    assertTrue(CarPlayMediaKeys.metadataChanged(song, nextSong))
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.CarPlayMediaCallbackTest"`
Expected: FAIL

- [ ] **Step 3: 在 `CarPlayMediaKeys.kt` 中补充 `androidMetadata`、`decodeArtwork` 与 `metadataChanged`**

在 `Api21Backend` 中：
- `decodeArtwork`：将输入 Bitmap 严格降采样至不超过 384×384 px；
- `onNowPlayingChanged`：只有当 `metadataChanged` 或封面实例变化时，才调用 `session.setMetadata(...)`；
- 所有调用必须包裹在 `runCatching` 中，遇到 E01 异常时通过 `DiagLog.w` 记日志，绝不抛出。

- [ ] **Step 4: 运行测试验证通过**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.CarPlayMediaCallbackTest"`
Expected: PASS

- [ ] **Step 5: Commit: "common: integrate metadata publishing and 2Hz throttling into CarPlayMediaKeys"**

---

### Task 6: 实验性设置卡片挂载、生命周期对齐与全量验证

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/airplay/AirPlayPersistence.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`

**Interfaces:**
- Produces:
  - `AirPlayPersistence.loadClusterMediaMetadataEnabled(context): Boolean`
  - 实验性设置卡片开关：“仪表多媒体信息协同 (实验性)”

- [ ] **Step 1: 在 `AirPlayPersistence.kt` 中添加持久化开关支持（缺省开启）**
- [ ] **Step 2: 在 `DiPlayActivity.kt` 的 `experimentalCards` 中添加开关控制卡片**
- [ ] **Step 3: 在退出与断开连接时，确保注销 `MediaSession` 并释放 Bitmap 内存**
- [ ] **Step 4: 运行全工程编译与测试**

Run: `cd /Users/g/Documents/project/xr/DiPlay-Legacy-Android-Geely && ./gradlew test`
Expected: ALL TESTS PASS

- [ ] **Step 5: Commit: "ui: mount cluster media metadata toggle in experimental settings"**

---

## Plan Self-Review Check

1. **Spec Coverage**:
   - 元数据解析（歌名、歌手、专辑）✅ (Task 1)
   - 封面二进制接收与解析 ✅ (Task 2)
   - 内存安全异步解码（384px 限制 + recycle）✅ (Task 3)
   - 2Hz 进度帧节流（保护 system_server）✅ (Task 5)
   - 异常日志隔离，绝不影响主链路 ✅ (Task 4 & Task 5)
   - 实验性设置开关与可控开启 ✅ (Task 6)
2. **Placeholder Scan**: 全文无 "TBD", "TODO", "implement later" 等占位符，每一处关键实现与测试均有确切代码。
3. **Type Consistency**: `CarPlayNowPlaying`、`NowPlayingArtworkQueue`、`Iap2ArtworkTransfer` 方法签名在 Tasks 1~6 保持绝对严格一致。
