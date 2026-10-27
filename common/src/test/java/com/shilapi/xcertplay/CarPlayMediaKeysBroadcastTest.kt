package com.shilapi.xcertplay

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadata
import com.shilapi.xcertplay.instrument.CoverSlotManager
import com.shilapi.xcertplay.instrument.XingruiInstrumentBridge
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMediaKeysBroadcastTest {

    private val context: Application = RuntimeEnvironment.application

    @Test
    fun buildMetadataPopulatesDisplayFields() {
        val nowPlaying = CarPlayNowPlaying(
            title = "Anytime Anywhere",
            artist = "milet",
            album = "Frieren OST",
            durationMillis = 210000L,
            playing = true,
        )

        val metadata = CarPlayMediaKeys.buildMediaMetadata(nowPlaying, null)
        assertNotNull(metadata)
        assertEquals("Anytime Anywhere", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Anytime Anywhere", metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        assertEquals("milet", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals("milet", metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        assertEquals("Frieren OST", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertEquals("Frieren OST", metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
        assertEquals(210000L, metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION))
    }

    @Test
    fun legacyAospBroadcastsAreCompletelyDisabled() {
        val shadow = org.robolectric.Shadows.shadowOf(context)
        shadow.clearBroadcastIntents()

        val nowPlaying = CarPlayNowPlaying(
            title = "Song Title",
            artist = "Song Artist",
            album = "Song Album",
            durationMillis = 180000L,
            elapsedMillis = 5000L,
            playing = true,
        )

        @Suppress("DEPRECATION")
        CarPlayMediaKeys.broadcastMediaChange(context, nowPlaying, playing = true)

        // 验证系统广播总线绝对干净，不产生无谓的 AOSP 广播轮询与唤醒
        val metaIntent = shadow.broadcastIntents.firstOrNull { it.action == "com.android.music.metachanged" }
        assertEquals("AOSP metachanged broadcast must be completely disabled", null, metaIntent)

        val stateIntent = shadow.broadcastIntents.firstOrNull { it.action == "com.android.music.playstatechanged" }
        assertEquals("AOSP playstatechanged broadcast must be completely disabled", null, stateIntent)
    }

    @Test
    fun coverSlotManagerPingPongRotationMaintainsExactTwoFiles() {
        val manager = CoverSlotManager(context)
        val bmp1 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val bmp2 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        // Save first cover
        val uri1 = manager.saveCover(bmp1)
        assertTrue("URI must start with file:///sdcard/", uri1.startsWith("file:///sdcard/"))
        assertTrue("URI must contain cover_1.jpg or cover_0.jpg", uri1.contains("cover_1.jpg") || uri1.contains("cover_0.jpg"))

        // Save second cover (rotates slot)
        val uri2 = manager.saveCover(bmp2)
        assertTrue("URI must start with file:///sdcard/", uri2.startsWith("file:///sdcard/"))
        assertTrue("Subsequent saves must alternate slot", uri1 != uri2)

        // Save third cover (rotates back to first slot)
        val uri3 = manager.saveCover(bmp1)
        assertEquals("Third save must reuse the first slot", uri1, uri3)

        // Verify directory file count is capped at 3 (cover_0, cover_1, cover_blank)
        val cacheDir = File(context.externalCacheDir ?: File(context.cacheDir, "covers"), "covers")
        val files = cacheDir.listFiles()?.filter { it.extension == "jpg" } ?: emptyList()
        assertTrue("Total cover cache files must never exceed 3", files.size <= 3)
    }

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

    @Test
    fun coverSlotManagerHandlesNullAndRecycledBitmapsSafely() {
        val manager = CoverSlotManager(context)
        val blankUri = manager.getBlankCoverUri()

        // Null bitmap
        val nullUri = manager.saveCover(null)
        assertEquals("Null bitmap must return blank cover URI", blankUri, nullUri)

        // Recycled bitmap
        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        bmp.recycle()
        val recycledUri = manager.saveCover(bmp)
        assertEquals("Recycled bitmap must return blank cover URI", blankUri, recycledUri)
    }

    @Test
    fun instrumentBridgeExecutesSafelyWithoutCrashing() {
        val bridge = XingruiInstrumentBridge(context)

        // Test normal update with empty/null titles (must not crash)
        bridge.updateMedia(null, null, null, null, false)
        bridge.updateMedia("Valid Song", "Valid Artist", "Valid Album", null, true)

        // Verify string over 31 characters does not crash
        val longTitle = "This is a very very very long song title that exceeds thirty-one characters"
        bridge.updateMedia(longTitle, "Long Artist Name Exceeding Thirty One", "Long Album Name", null, true)

        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        bridge.updateMedia("With Art", "Artist", "Album", bmp, true)

        bridge.clearMedia()
        bridge.release()
    }

    @Test
    fun clusterMediaSourceDefaultsToUsb() {
        assertEquals("Cluster media source should default to 0 (USB)", 0, AirPlayPersistence.loadClusterMediaSource(context))
        AirPlayPersistence.saveClusterMediaSource(context, 6)
        assertEquals("Cluster media source can be saved as 6 (Online)", 6, AirPlayPersistence.loadClusterMediaSource(context))
        // Saving 2 (Bluetooth) must auto-migrate to 0 (USB) to prevent EastSoft IPKServer blocking when A2DP is disconnected
        AirPlayPersistence.saveClusterMediaSource(context, 2)
        assertEquals("Bluetooth (2) must auto-migrate to USB (0)", 0, AirPlayPersistence.loadClusterMediaSource(context))
    }

    @Test
    fun dynamicLyricFilteringRejectsKugouSwapAndSpam() {
        // 1. Initial Genuine Track
        val initialTrack = CarPlayNowPlaying(
            title = "さよならの夏～コクリコ坂から～",
            artist = "手嶌葵",
            album = "コクリコ坂から歌集",
            durationMillis = 288_000L,
            elapsedMillis = 1000L,
            playing = true,
        )
        val r1 = CarPlayMediaKeys.filterLyricMetadata(initialTrack, null, null, null, null)
        assertFalse(r1.isLyric)
        assertEquals("さよならの夏～コクリコ坂から～", r1.genuineTitle)
        assertEquals("手嶌葵", r1.genuineArtist)
        assertEquals("コクリコ坂から歌集", r1.genuineAlbum)
        assertEquals(288_000L, r1.durationMillis)

        // 2. Kugou Lyric Swap: artist is set to previous song title, title is lyric text
        val kugouLyric = CarPlayNowPlaying(
            title = "光る海に かすむ船は",
            artist = "さよならの夏～コクリコ坂から～",
            album = "コクリコ坂から歌集",
            durationMillis = 288_000L,
            elapsedMillis = 25_000L,
            playing = true,
        )
        val r2 = CarPlayMediaKeys.filterLyricMetadata(kugouLyric, r1.genuineTitle, r1.genuineArtist, r1.genuineAlbum, r1.durationMillis)
        assertTrue("Kugou swapped lyric packet must be flagged as lyric", r2.isLyric)
        assertEquals("Title must stay genuine track name", "さよなら的夏～コクリコ坂から～".replace("的", "の"), r2.info.title)
        assertEquals("Artist must stay genuine artist", "手嶌葵", r2.info.artist)

        // 3. Lyric credit line: "词：万里村ゆき子"
        val creditLine = CarPlayNowPlaying(
            title = "词：万里村ゆき子",
            artist = "さよならの夏～コクリコ坂から～",
            album = "コクリコ坂から歌集",
            durationMillis = 288_000L,
            elapsedMillis = 30_000L,
            playing = true,
        )
        val r3 = CarPlayMediaKeys.filterLyricMetadata(creditLine, r2.genuineTitle, r2.genuineArtist, r2.genuineAlbum, r2.durationMillis)
        assertTrue("Credit line must be flagged as lyric", r3.isLyric)
        assertEquals("さよならの夏～コクリコ坂から～", r3.info.title)
        assertEquals("手嶌葵", r3.info.artist)

        // 4. Formatted suffix lyric: "夏日长 有无尽的远方 - 当世界年纪小"
        val suffixLyric = CarPlayNowPlaying(
            title = "夏日长 有无尽的远方 - 当世界年纪小",
            artist = "当世界年纪小",
            album = "",
            durationMillis = 210_000L,
            elapsedMillis = 40_000L,
            playing = true,
        )
        val r4 = CarPlayMediaKeys.filterLyricMetadata(suffixLyric, "当世界年纪小", "任素汐", "", 210_000L)
        assertTrue(r4.isLyric)
        assertEquals("当世界年纪小", r4.info.title)
        assertEquals("任素汐", r4.info.artist)

        // 5. Real track skip (new song with different duration and reset elapsed)
        val nextTrack = CarPlayNowPlaying(
            title = "下一站天后",
            artist = "Twins",
            album = "Touch Of Love",
            durationMillis = 205_000L,
            elapsedMillis = 0L,
            playing = true,
        )
        val r5 = CarPlayMediaKeys.filterLyricMetadata(nextTrack, r4.genuineTitle, r4.genuineArtist, r4.genuineAlbum, r4.durationMillis)
        assertFalse("Real track skip must not be flagged as lyric", r5.isLyric)
        assertEquals("下一站天后", r5.genuineTitle)
        assertEquals("Twins", r5.genuineArtist)
        assertEquals(205_000L, r5.durationMillis)
    }
}
