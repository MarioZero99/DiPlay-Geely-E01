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
}
