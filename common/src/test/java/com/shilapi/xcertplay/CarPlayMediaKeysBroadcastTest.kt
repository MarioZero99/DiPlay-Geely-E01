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
}
