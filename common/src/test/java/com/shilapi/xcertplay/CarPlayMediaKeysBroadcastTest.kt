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
    fun legacyAospBroadcastsAreNotSent() {
        val shadow = org.robolectric.Shadows.shadowOf(context)
        shadow.clearBroadcastIntents()

        val nowPlaying = CarPlayNowPlaying(
            title = "Song Title",
            artist = "Song Artist",
            album = "Song Album",
            durationMillis = 180000L,
            playing = true,
        )

        @Suppress("DEPRECATION")
        CarPlayMediaKeys.broadcastMediaChange(context, nowPlaying, playing = true)

        // Verify that dead com.android.music.metachanged is NOT broadcast to the system
        val metaIntent = shadow.broadcastIntents.firstOrNull { it.action == "com.android.music.metachanged" }
        assertEquals("Legacy AOSP metachanged broadcast must not be sent", null, metaIntent)

        val stateIntent = shadow.broadcastIntents.firstOrNull { it.action == "com.android.music.playstatechanged" }
        assertEquals("Legacy AOSP playstatechanged broadcast must not be sent", null, stateIntent)
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

        // Verify directory file count is capped at 2
        val cacheDir = File(context.externalCacheDir ?: File(context.cacheDir, "covers"), "covers")
        val files = cacheDir.listFiles()?.filter { it.extension == "jpg" } ?: emptyList()
        assertTrue("Total cover cache files must never exceed 2", files.size <= 2)
    }

    @Test
    fun coverSlotManagerHandlesNullAndRecycledBitmapsSafely() {
        val manager = CoverSlotManager(context)

        // Null bitmap
        val nullUri = manager.saveCover(null)
        assertEquals("Null bitmap must return empty string without exception", "", nullUri)

        // Recycled bitmap
        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        bmp.recycle()
        val recycledUri = manager.saveCover(bmp)
        assertEquals("Recycled bitmap must return empty string without exception", "", recycledUri)
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
