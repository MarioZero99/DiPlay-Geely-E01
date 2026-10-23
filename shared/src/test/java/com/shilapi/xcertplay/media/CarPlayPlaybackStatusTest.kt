package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayPlaybackStatusTest {
    private fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(CarPlayPlaybackStatus.NOW_PLAYING_UPDATE, block)

    @Test
    fun reportsOnlyChangesOfThePlaybackStatus() {
        val status = CarPlayPlaybackStatus()

        assertEquals(true, status.accept(update { group(1) { u8(0, 1) } }))
        assertNull(status.accept(update { group(1) { u8(0, 1) } }))
        // Elapsed time alone, as the iPhone sends it every half second: no status, no change.
        assertNull(status.accept(update { group(1) { u32(1, 120_706L) } }))
        assertEquals(false, status.accept(update { group(1) { u8(0, 2) } }))
    }

    @Test
    fun otherMessagesAndSessionEnd() {
        val status = CarPlayPlaybackStatus()
        assertNull(status.accept(Iap2Messages.buildRaw(0x5201) { group(1) { u8(0, 1) } }))
        assertNull(status.clear())

        status.accept(update { group(1) { u8(0, 1) } })
        assertEquals(false, status.clear())
    }

    @Test
    fun parsesMetadataFromNowPlayingUpdate() {
        val status = CarPlayPlaybackStatus()
        val frame = update {
            group(0) { // MEDIA_ITEM
                string(1, "Song Title")
                string(6, "Album Name")
                string(12, "Artist Name")
                u8(26, 7) // artworkTransferId
                u32(4, 180000L) // duration
            }
            group(1) { // PLAYBACK
                u8(0, 1) // STATUS_PLAYING
                u32(1, 1500L) // elapsed
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

    @Test
    fun incrementalUpdatesRetainOmittedFields() {
        val status = CarPlayPlaybackStatus()
        status.acceptUpdate(update {
            group(0) {
                string(1, "First Title")
                string(12, "First Artist")
            }
        })
        // Second update only changes elapsed time, title and artist should be retained
        val second = status.acceptUpdate(update {
            group(1) {
                u32(1, 2000L)
            }
        })
        assertNotNull(second)
        assertEquals("First Title", second?.title)
        assertEquals("First Artist", second?.artist)
        assertEquals(2000L, second?.elapsedMillis)
    }
}
