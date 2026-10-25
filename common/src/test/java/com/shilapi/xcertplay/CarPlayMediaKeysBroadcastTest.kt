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
    fun broadcastMediaChangeSendsAndroidMusicMetachangedIntent() {
        CarPlayMediaKeys.resetBroadcastState()
        val nowPlaying = CarPlayNowPlaying(
            title = "Song Title",
            artist = "Song Artist",
            album = "Song Album",
            durationMillis = 180000L,
            elapsedMillis = 5000L,
            playing = true,
        )

        CarPlayMediaKeys.broadcastMediaChange(context, nowPlaying, playing = true)

        val broadcastIntents = org.robolectric.Shadows.shadowOf(context).broadcastIntents
        val metaIntent = broadcastIntents.firstOrNull { it.action == "com.android.music.metachanged" }
        assertNotNull(metaIntent)
        assertEquals("Song Title", metaIntent?.getStringExtra("track"))
        assertEquals("Song Artist", metaIntent?.getStringExtra("artist"))
        assertEquals("Song Album", metaIntent?.getStringExtra("album"))
        assertEquals(true, metaIntent?.getBooleanExtra("playing", false))
        assertEquals(180000L, metaIntent?.getLongExtra("duration", 0L))
        assertEquals(5000L, metaIntent?.getLongExtra("position", 0L))

        val stateIntent = broadcastIntents.firstOrNull { it.action == "com.android.music.playstatechanged" }
        assertNotNull(stateIntent)
        assertEquals(true, stateIntent?.getBooleanExtra("playing", false))
    }

    @Test
    fun broadcastMediaChangeDeduplicatesRepeatedEvents() {
        CarPlayMediaKeys.resetBroadcastState()
        val shadow = org.robolectric.Shadows.shadowOf(context)
        shadow.clearBroadcastIntents()

        val song1 = CarPlayNowPlaying(
            title = "Song 1",
            artist = "Artist 1",
            album = "Album 1",
            playing = true,
        )

        // First publication: 1 meta + 1 state
        CarPlayMediaKeys.broadcastMediaChange(context, song1, playing = true)
        assertEquals(2, shadow.broadcastIntents.size)

        // Second publication with identical metadata (e.g. artwork decoded later): 0 new intents
        CarPlayMediaKeys.broadcastMediaChange(context, song1, playing = true)
        assertEquals(2, shadow.broadcastIntents.size)

        // Playback paused: only 1 state intent, no duplicate meta intent
        CarPlayMediaKeys.broadcastMediaChange(context, song1, playing = false)
        assertEquals(3, shadow.broadcastIntents.size)
        val lastIntent = shadow.broadcastIntents.last()
        assertEquals("com.android.music.playstatechanged", lastIntent.action)
        assertEquals(false, lastIntent.getBooleanExtra("playing", true))

        // New track arrives: 1 new meta intent
        val song2 = CarPlayNowPlaying(
            title = "Song 2",
            artist = "Artist 2",
            album = "Album 2",
            playing = false,
        )
        CarPlayMediaKeys.broadcastMediaChange(context, song2, playing = false)
        assertEquals(4, shadow.broadcastIntents.size)
        assertEquals("com.android.music.metachanged", shadow.broadcastIntents.last().action)
    }

    @Test
    fun broadcastMediaChangeSendsStoppedOnDisconnectAndPreservesPositionOnPause() {
        CarPlayMediaKeys.resetBroadcastState()
        val shadow = org.robolectric.Shadows.shadowOf(context)
        shadow.clearBroadcastIntents()

        // 1. Song starts with elapsed = 0
        val songSnapshotAtStart = CarPlayNowPlaying(
            title = "Position Song",
            artist = "Position Artist",
            elapsedMillis = 0L,
            durationMillis = 180000L,
            playing = true,
        )
        CarPlayMediaKeys.broadcastMediaChange(context, songSnapshotAtStart, playing = true)

        // 2. Playback runs, 2Hz ticks update lastKnownPosition to 52000L, then pause occurs.
        // The cached lastPublishedNowPlaying has elapsed = 0L, but forcedPosition = 52000L is provided.
        CarPlayMediaKeys.broadcastMediaChange(context, songSnapshotAtStart, playing = false, forcedPosition = 52000L)
        val pauseIntent = shadow.broadcastIntents.last { it.action == "com.android.music.playstatechanged" }
        assertEquals(2, pauseIntent.getIntExtra("playstate", -1)) // 2 = PAUSED
        assertEquals("Pause broadcast must preserve current playing position instead of old 0", 52000L, pauseIntent.getLongExtra("position", -1L))

        // 3. CarPlay disconnects with empty NowPlaying (not null, but blank fields)
        val emptyNowPlaying = CarPlayNowPlaying(title = "", artist = "", album = "", playing = false)
        CarPlayMediaKeys.broadcastMediaChange(context, emptyNowPlaying, playing = false)
        val stopIntent = shadow.broadcastIntents.last { it.action == "com.android.music.playstatechanged" }
        assertEquals("Empty/cleared NowPlaying must send playstate 1 (STOPPED) to clear cluster display", 1, stopIntent.getIntExtra("playstate", -1))
        assertEquals(0L, stopIntent.getLongExtra("position", -1L))
    }

    @Test
    fun trackChangeResetsLastKnownPositionAndPreventsPreviousSongPositionBleed() {
        CarPlayMediaKeys.resetBroadcastState()
        val shadow = org.robolectric.Shadows.shadowOf(context)
        shadow.clearBroadcastIntents()

        // 1. First song playing up to 120000ms
        val song1 = CarPlayNowPlaying(
            title = "First Song",
            artist = "Artist",
            elapsedMillis = 120000L,
            durationMillis = 180000L,
            playing = true,
        )
        CarPlayMediaKeys.broadcastMediaChange(context, song1, playing = true)

        // 2. Second song arrives without elapsedMillis in initial metadata packet
        val song2Initial = CarPlayNowPlaying(
            title = "Second Song",
            artist = "Artist",
            elapsedMillis = null,
            durationMillis = 240000L,
            playing = true,
        )
        CarPlayMediaKeys.broadcastMediaChange(context, song2Initial, playing = true)

        val metaIntent = shadow.broadcastIntents.last { it.action == "com.android.music.metachanged" }
        assertEquals("Second Song", metaIntent.getStringExtra("track"))
        assertEquals("Track change without elapsedMillis must reset position to 0 instead of bleeding previous song's 120000ms", 0L, metaIntent.getLongExtra("position", -1L))
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
