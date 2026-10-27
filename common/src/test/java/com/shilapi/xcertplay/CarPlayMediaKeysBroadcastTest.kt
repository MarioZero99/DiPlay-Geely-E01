package com.shilapi.xcertplay

import android.app.Application
import android.media.MediaMetadata
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

        val metaIntent = shadow.broadcastIntents.firstOrNull { it.action == "com.android.music.metachanged" }
        assertEquals("AOSP metachanged broadcast must be completely disabled", null, metaIntent)

        val stateIntent = shadow.broadcastIntents.firstOrNull { it.action == "com.android.music.playstatechanged" }
        assertEquals("AOSP playstatechanged broadcast must be completely disabled", null, stateIntent)
    }

    @Test
    fun instrumentBridgeExecutesSafelyWithoutCrashing() {
        val bridge = XingruiInstrumentBridge(context)

        // Test normal update with empty/null titles (must not crash)
        bridge.updateMedia(null, null, null, false)
        bridge.updateMedia("Valid Song", "Valid Artist", "Valid Album", true)

        // Verify string over 31 characters does not crash
        val longTitle = "This is a very very very long song title that exceeds thirty-one characters"
        bridge.updateMedia(longTitle, "Long Artist Name Exceeding Thirty One", "Long Album Name", true)

        bridge.clearMedia()
        bridge.release()
    }

    @Test
    fun clusterMediaSourceDefaultsToUsb() {
        assertEquals("Cluster media source should default to 0 (USB)", 0, AirPlayPersistence.loadClusterMediaSource(context))
        AirPlayPersistence.saveClusterMediaSource(context, 6)
        assertEquals("Cluster media source can be saved as 6 (Online)", 6, AirPlayPersistence.loadClusterMediaSource(context))
        AirPlayPersistence.saveClusterMediaSource(context, 2)
        assertEquals("Bluetooth (2) must auto-migrate to USB (0)", 0, AirPlayPersistence.loadClusterMediaSource(context))
    }

    @Test
    fun lyricFilteringAlignsWithStrictPlanRules() {
        // Initial Genuine Track: 《七里香》
        val qiLiXiang = CarPlayNowPlaying(
            title = "七里香",
            artist = "周杰伦",
            album = "七里香",
            durationMillis = 300_000L,
            elapsedMillis = 1000L,
            playing = true,
        )
        val r0 = CarPlayMediaKeys.filterLyricMetadata(qiLiXiang, null, null, null, null)
        assertFalse(r0.isLyric)
        assertEquals("七里香", r0.genuineTitle)
        assertEquals("周杰伦", r0.genuineArtist)

        // 断言 1: 《七里香》接到《七里香 (Live)》，进度为 0，不是歌词，歌名更新。
        val liveTrack = CarPlayNowPlaying(
            title = "七里香 (Live)",
            artist = "周杰伦",
            album = "演唱会",
            durationMillis = 310_000L,
            elapsedMillis = 0L,
            playing = true,
        )
        val r1 = CarPlayMediaKeys.filterLyricMetadata(liveTrack, r0.genuineTitle, r0.genuineArtist, r0.genuineAlbum, r0.durationMillis)
        assertFalse("《七里香 (Live)》进度为 0 不是歌词，必须放行", r1.isLyric)
        assertEquals("七里香 (Live)", r1.genuineTitle)
        assertEquals("七里香 (Live)", r1.info.title)

        // 断言 2: 进度为 0 的 词：，是歌词，歌名保持上一首。进度 500 毫秒的 词： 同样拦住。
        val lyricCi0 = CarPlayNowPlaying(
            title = "词：方文山",
            artist = "周杰伦",
            album = "七里香",
            durationMillis = 300_000L,
            elapsedMillis = 0L,
            playing = true,
        )
        val r2_0 = CarPlayMediaKeys.filterLyricMetadata(lyricCi0, r0.genuineTitle, r0.genuineArtist, r0.genuineAlbum, r0.durationMillis)
        assertTrue("进度为 0 的 '词：' 必须被识别为歌词拦截", r2_0.isLyric)
        assertEquals("七里香", r2_0.info.title)

        val lyricCi500 = CarPlayNowPlaying(
            title = "词：方文山",
            artist = "周杰伦",
            album = "七里香",
            durationMillis = 300_000L,
            elapsedMillis = 500L,
            playing = true,
        )
        val r2_500 = CarPlayMediaKeys.filterLyricMetadata(lyricCi500, r0.genuineTitle, r0.genuineArtist, r0.genuineAlbum, r0.durationMillis)
        assertTrue("进度为 500ms 的 '词：' 必须被识别为歌词拦截", r2_500.isLyric)
        assertEquals("七里香", r2_500.info.title)

        // 扩充断言 2b: 作词： 作曲: 监制： 混音： 等前缀同样拦截
        for (prefix in listOf("作词：", "作词:", "作曲：", "作曲:", "监制：", "混音：", "出品：")) {
            val creditTrack = CarPlayNowPlaying(
                title = "$prefix 某某某",
                artist = "周杰伦",
                album = "七里香",
                durationMillis = 300_000L,
                elapsedMillis = 300L,
                playing = true,
            )
            val rCredit = CarPlayMediaKeys.filterLyricMetadata(creditTrack, r0.genuineTitle, r0.genuineArtist, r0.genuineAlbum, r0.durationMillis)
            assertTrue("前缀 '$prefix' 必须被识别为歌词拦截", rCredit.isLyric)
            assertEquals("七里香", rCredit.info.title)
        }

        // 断言 3: 歌手被换成上一首歌名，即使进度为 0，也是歌词。
        val swappedKugou = CarPlayNowPlaying(
            title = "雨下整夜 我的爱溢出就像雨水",
            artist = "七里香",
            album = "七里香",
            durationMillis = 300_000L,
            elapsedMillis = 0L,
            playing = true,
        )
        val r3 = CarPlayMediaKeys.filterLyricMetadata(swappedKugou, r0.genuineTitle, r0.genuineArtist, r0.genuineAlbum, r0.durationMillis)
        assertTrue("歌手被对调成上一首歌名，即使进度为 0 也是歌词", r3.isLyric)
        assertEquals("七里香", r3.info.title)
        assertEquals("周杰伦", r3.info.artist)

        // 断言 4: 歌名以 " - " 加上一首歌名结尾，是歌词。
        val suffixLyric = CarPlayNowPlaying(
            title = "院子落叶 跟我的思念厚厚一叠 - 七里香",
            artist = "周杰伦",
            album = "七里香",
            durationMillis = 300_000L,
            elapsedMillis = 20_000L,
            playing = true,
        )
        val r4 = CarPlayMediaKeys.filterLyricMetadata(suffixLyric, r0.genuineTitle, r0.genuineArtist, r0.genuineAlbum, r0.durationMillis)
        assertTrue("以 ' - 当前歌名' 结尾的包必须判定为歌词", r4.isLyric)
        assertEquals("七里香", r4.info.title)

        // 断言 5: 只是包含上一首歌名、又对不上上面三种，不是歌词。
        val containsNotLyric = CarPlayNowPlaying(
            title = "听周杰伦七里香的时候在想你",
            artist = "网络歌手",
            album = "单曲",
            durationMillis = 240_000L,
            elapsedMillis = 15_000L,
            playing = true,
        )
        val r5 = CarPlayMediaKeys.filterLyricMetadata(containsNotLyric, r0.genuineTitle, r0.genuineArtist, r0.genuineAlbum, r0.durationMillis)
        assertFalse("仅标题包含上一首歌名但未命中三种特征，绝对不是歌词，放行新歌", r5.isLyric)
        assertEquals("听周杰伦七里香的时候在想你", r5.genuineTitle)
        assertEquals("网络歌手", r5.genuineArtist)
    }

    @Test
    fun bridgeQueueGenerationAndReleaseLifecycle() {
        val bridge = XingruiInstrumentBridge(context).apply { isSupportedOverrideForTest = true }
        val initialGen = bridge.getCurrentGenerationForTest()
        assertEquals(0, initialGen)
        assertFalse(bridge.isReleasedForTest())

        bridge.clearMedia()
        val afterClearGen = bridge.getCurrentGenerationForTest()
        assertEquals("clearMedia 必须递增 1 次代数", initialGen + 1, afterClearGen)
        assertFalse(bridge.isReleasedForTest())

        bridge.release()
        val afterReleaseGen = bridge.getCurrentGenerationForTest()
        assertEquals("release 自身只递增 1 次代数，不应触发 clearMedia 二次加代数", afterClearGen + 1, afterReleaseGen)
        assertTrue("release 后 released 状态必须为 true", bridge.isReleasedForTest())

        bridge.release()
        assertEquals("多次 release 代数保持不变", afterReleaseGen, bridge.getCurrentGenerationForTest())
    }

    @Test
    fun bridgeGenerationChangeCancelsPendingAction() {
        val bridge = XingruiInstrumentBridge(context).apply { isSupportedOverrideForTest = true }
        val gen1 = bridge.getCurrentGenerationForTest()
        bridge.triggerSchedulePendingActionForTest(gen1, isPlaying = false, delayMs = 1500L)
        assertTrue("必须成功暂存未完成动作", bridge.hasPendingActionForTest())

        bridge.clearMedia()
        val gen2 = bridge.getCurrentGenerationForTest()
        assertTrue(gen2 > gen1)
        assertFalse("代数变了之后暂存任务必须被取消", bridge.hasPendingActionForTest())
        bridge.release()
    }
}
