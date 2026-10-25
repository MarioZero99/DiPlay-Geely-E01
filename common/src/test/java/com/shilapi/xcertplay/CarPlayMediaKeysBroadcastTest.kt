package com.shilapi.xcertplay

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadata
import com.shilapi.xcertplay.instrument.CoverSlotManager
import com.shilapi.xcertplay.instrument.XingruiInstrumentBridge
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
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

        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        bridge.updateMedia("With Art", "Artist", "Album", bmp, true)

        bridge.clearMedia()
        bridge.release()
    }
}
