package com.shilapi.xcertplay.instrument

import android.graphics.Bitmap
import android.os.Looper
import android.os.RemoteException
import com.ecarx.xui.adaptapi.diminteraction.IDimListener
import com.ecarx.xui.adaptapi.diminteraction.IDimTransListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class XingruiInstrumentBridgeTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var tempDir: File
    private lateinit var bridge: XingruiInstrumentBridge
    private lateinit var fakeService: TestDimTransListener

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "bridge_test_" + System.currentTimeMillis())
        tempDir.mkdirs()
        fakeService = TestDimTransListener()
        bridge = XingruiInstrumentBridge(context).apply {
            isSupportedOverrideForTest = true
            dimServiceForTest = fakeService
            coverSlotManager = CoverSlotManager(baseDir = tempDir)
            debounceDelayMs = 400L
            minCanIntervalMs = 1500L
            enforceThrottleForTest = true
        }
    }

    private fun drain(advanceMs: Long = 0L) {
        bridge.drainQueueForTest()
        if (advanceMs > 0L) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(advanceMs))
        } else {
            shadowOf(Looper.getMainLooper()).idle()
        }
        bridge.drainQueueForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        bridge.release()
        tempDir.deleteRecursively()
    }

    @Test
    fun asynchronousArtworkArrival_within400ms_coalescesIntoSingleSendWithCover() {
        // Step 1: Initial metadata arrives without artwork (e.g. CarPlay metadata event)
        bridge.updateMedia("Bohemian Rhapsody", "Queen", "A Night at the Opera", isPlaying = true, artworkBitmap = null)
        drain(100L) // Wait 100ms (< 400ms debounce), must NOT have sent yet
        assertEquals("Debounce window active: must not send half-baked packet without artwork", 0, fakeService.updateMediaCalls.size)

        // Step 2: Artwork arrives asynchronously 150ms later (e.g. from onArtworkDecoded) for the exact same track
        val coverBmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        bridge.updateMedia("Bohemian Rhapsody", "Queen", "A Night at the Opera", isPlaying = true, artworkBitmap = coverBmp)
        drain() // Artwork arrival completes debounce and dispatches single full update

        assertEquals("Coalesced into single send; 200ms CAN collision eliminated", 1, fakeService.updateMediaCalls.size)
        val call = fakeService.updateMediaCalls[0]
        assertEquals("Bohemian Rhapsody", call.title)
        assertEquals("Queen", call.artist)
        assertTrue("Media path must use carplay slot URI", call.cover?.startsWith("carplay&&&file:///sdcard/Music/mediacenter/images/") == true)
        assertEquals(6, call.source)

        // Step 3: Repeated identical call should be deduplicated
        bridge.updateMedia("Bohemian Rhapsody", "Queen", "A Night at the Opera", isPlaying = true, artworkBitmap = coverBmp)
        drain()
        assertEquals("Duplicate call must be dropped", 1, fakeService.updateMediaCalls.size)
    }

    @Test
    fun debounceTimeoutWithoutArtwork_sendsDefaultCoverFallback() {
        bridge.updateMedia("Radio Song", "FM Artist", "Radio", isPlaying = true, artworkBitmap = null)
        drain(200L)
        assertEquals(0, fakeService.updateMediaCalls.size)

        // Advance past 400ms debounce timeout
        drain(300L)
        assertEquals(1, fakeService.updateMediaCalls.size)
        val call = fakeService.updateMediaCalls[0]
        assertEquals("Radio Song", call.title)
        assertEquals("carplay&&&file:///sdcard/Music/mediacenter/images/default_cover.jpg", call.cover)
        assertEquals(6, call.source)
        assertTrue(File(tempDir, "default_cover.jpg").exists())
    }

    @Test
    fun subsequentArtworkArrival_afterDefaultCoverFallback_updatesToRealCover() {
        // Step 1: Track arrives without artwork, 400ms timeout occurs -> default cover sent
        bridge.updateMedia("Radio Song", "FM Artist", "Radio", isPlaying = true, artworkBitmap = null)
        drain(500L)
        assertEquals(1, fakeService.updateMediaCalls.size)
        assertEquals("carplay&&&file:///sdcard/Music/mediacenter/images/default_cover.jpg", fakeService.updateMediaCalls[0].cover)

        // Step 2: Late artwork arrives for same track -> updates to real cover after throttle
        val coverBmp = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        bridge.updateMedia("Radio Song", "FM Artist", "Radio", isPlaying = true, artworkBitmap = coverBmp)
        drain(1600L)

        assertEquals(2, fakeService.updateMediaCalls.size)
        val secondCall = fakeService.updateMediaCalls[1]
        assertTrue("Must send real cover after default cover fallback", secondCall.cover?.contains("cover_0.jpg") == true)
        assertTrue(File(tempDir, "cover_0.jpg").exists())
    }

    @Test
    fun rapidTrackSkip_cancelsPendingThrottle_andGivesNewTrackFullDebounceWindow() {
        // Track 1 sent at t=0
        val bmp1 = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        bridge.updateMedia("Track 1", "Artist 1", "Album 1", isPlaying = true, artworkBitmap = bmp1)
        drain()
        assertEquals(1, fakeService.updateMediaCalls.size)
        assertEquals("Track 1", fakeService.updateMediaCalls[0].title)

        // Advance 200ms (within 1500ms CAN throttle window)
        // User skips to Track 2, which receives artwork immediately -> queued in throttle for ~1300ms later
        val bmp2 = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        bridge.updateMedia("Track 2", "Artist 2", "Album 2", isPlaying = true, artworkBitmap = bmp2)
        drain(100L)
        assertEquals("Track 2 must be throttled (1500ms CAN interval)", 1, fakeService.updateMediaCalls.size)
        assertTrue(bridge.hasPendingThrottleForTest())

        // User quickly skips to Track 3 without artwork -> enters 400ms debounce
        // This must cancel Track 2's pending throttle, so Track 2 does not prematurely dispatch Track 3
        bridge.updateMedia("Track 3", "Artist 3", "Album 3", isPlaying = true, artworkBitmap = null)
        drain(100L)
        assertTrue("Track 3 must have active debounce", bridge.hasPendingDebounceForTest())
        assertEquals(1, fakeService.updateMediaCalls.size)

        // Advance past 400ms debounce timeout (total ~500ms since Track 3, elapsed from Track 1 is ~700ms < 1500ms,
        // so debounce schedules throttle, then after 1500ms total, Track 3 dispatches)
        drain(1600L)
        assertEquals(2, fakeService.updateMediaCalls.size)
        val secondCall = fakeService.updateMediaCalls[1]
        assertEquals("Track 3", secondCall.title)
        assertEquals("carplay&&&file:///sdcard/Music/mediacenter/images/default_cover.jpg", secondCall.cover)
    }

    @Test
    fun callSequence_isSourceChangedThenUpdateMediaThenOnAction() {
        val coverBmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bridge.updateMedia("Hotel California", "Eagles", "Hotel California", isPlaying = true, artworkBitmap = coverBmp)
        drain()

        assertEquals(listOf("onSourceChanged:6", "updateMedia:Hotel California", "onAction:1:6"), fakeService.callOrder)
    }

    @Test
    fun replayOfSameCover_doesNotRotateSlot_andNextIdenticalUpdateIsDropped() {
        val coverBmp = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        bridge.updateMedia("Song", "Artist", "Album", isPlaying = true, artworkBitmap = coverBmp)
        drain()

        assertEquals(1, fakeService.updateMediaCalls.size)
        val firstCover = fakeService.updateMediaCalls[0].cover
        assertTrue(firstCover?.contains("cover_0.jpg") == true)
        assertTrue(File(tempDir, "cover_0.jpg").exists())

        bridge.replayCachedForTest()
        drain(1600L) // Advance past 1500ms throttle for replay

        assertEquals(2, fakeService.updateMediaCalls.size)
        assertEquals(firstCover, fakeService.updateMediaCalls[1].cover)
        assertTrue(!File(tempDir, "cover_1.jpg").exists())

        bridge.updateMedia("Song", "Artist", "Album", isPlaying = true, artworkBitmap = coverBmp)
        drain()

        assertEquals(2, fakeService.updateMediaCalls.size)
        assertTrue(!File(tempDir, "cover_1.jpg").exists())
    }

    @Test
    fun clearMedia_clearsCoverSlotsAndSendsEmptyMediaPath() {
        val coverBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bridge.updateMedia("Song", "Artist", "Album", isPlaying = true, artworkBitmap = coverBmp)
        drain()

        fakeService.callOrder.clear()
        bridge.clearMedia()
        drain()

        val lastUpdate = fakeService.updateMediaCalls.lastOrNull()
        assertNotNull(lastUpdate)
        assertEquals("", lastUpdate?.cover)
        assertTrue(!File(tempDir, "cover_0.jpg").exists())
        assertTrue(!File(tempDir, "cover_1.jpg").exists())
    }

    private class TestDimTransListener : IDimTransListener.Stub() {
        data class MediaCall(
            val title: String?,
            val album: String?,
            val artist: String?,
            val cover: String?,
            val source: Int
        )

        val updateMediaCalls = mutableListOf<MediaCall>()
        val callOrder = mutableListOf<String>()

        override fun onSourceChanged(source: Int) {
            callOrder.add("onSourceChanged:$source")
        }

        override fun updateMedia(p0: Int, title: String?, album: String?, artist: String?, cover: String?, source: Int) {
            callOrder.add("updateMedia:$title")
            updateMediaCalls.add(MediaCall(title, album, artist, cover, source))
        }

        override fun onAction(action: Int, source: Int) {
            callOrder.add("onAction:$action:$source")
        }

        override fun onUpdate(p0: Int, p1: Int, p2: String?) {}
        override fun onListChanged(p0: Int, p1: Int) {}
        override fun onRadioListChanged(p0: Int, p1: Int) {}
        override fun registerCallBack(p0: IDimListener?) {}
        override fun setHighLight(p0: String?, p1: String?, p2: Int) {}
        override fun setMode(p0: Int, p1: Int) {}
        override fun setMute(p0: Int) {}
    }
}
