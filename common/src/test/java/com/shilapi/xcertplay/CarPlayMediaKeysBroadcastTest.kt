package com.shilapi.xcertplay

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
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
        assertEquals("Song 2", shadow.broadcastIntents.last().getStringExtra("track"))
    }

    @Test
    fun broadcastMediaChangeAllowsLateArrivingDurationAndArtworkId() {
        CarPlayMediaKeys.resetBroadcastState()
        val shadow = org.robolectric.Shadows.shadowOf(context)
        shadow.clearBroadcastIntents()

        // Packet 1: Initial title/artist arrives first, duration is still 0
        val packet1 = CarPlayNowPlaying(
            title = "Late Song",
            artist = "Late Artist",
            album = "Late Album",
            durationMillis = 0L,
            playing = true,
        )
        CarPlayMediaKeys.broadcastMediaChange(context, packet1, playing = true)
        val initialMeta = shadow.broadcastIntents.first { it.action == "com.android.music.metachanged" }
        assertEquals(0L, initialMeta.getLongExtra("duration", -1L))
        val initialId = initialMeta.getLongExtra("id", -1L)
        assertTrue("Initial ID must be a valid positive non-zero hash", initialId > 0L)

        // Packet 2: Next iAP2 packet delivers duration
        val packet2 = CarPlayNowPlaying(
            title = "Late Song",
            artist = "Late Artist",
            album = "Late Album",
            durationMillis = 240000L,
            playing = true,
        )
        CarPlayMediaKeys.broadcastMediaChange(context, packet2, playing = true)
        val durationMeta = shadow.broadcastIntents.last { it.action == "com.android.music.metachanged" }
        assertEquals("Late arriving duration must trigger broadcast update", 240000L, durationMeta.getLongExtra("duration", -1L))

        // Packet 3: Artwork transfer ID arrives later
        val packet3 = packet2.copy(artworkTransferId = 88)
        CarPlayMediaKeys.broadcastMediaChange(context, packet3, playing = true)
        val artMeta = shadow.broadcastIntents.last { it.action == "com.android.music.metachanged" }
        assertEquals("Late arriving artwork transfer ID must trigger broadcast update", 88L, artMeta.getLongExtra("id", -1L))
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
}
