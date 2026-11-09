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
    fun songReachesEveryMediaServiceThatMightHoldTheCallback() {
        val other = TestDimTransListener()
        bridge.extraDimServicesForTest = listOf(other)
        bridge.updateMedia("Bohemian Rhapsody", "Queen", "A Night at the Opera", isPlaying = true)
        drain()
        assertEquals(listOf("onSourceChanged:2", "updateMedia:Bohemian Rhapsody", "onAction:1:2"), fakeService.callOrder)
        assertEquals(fakeService.callOrder, other.callOrder)
        assertEquals(2, fakeService.updateMediaCalls[0].source)
        assertEquals(2, other.updateMediaCalls[0].source)
    }

    @Test
    fun defaultCanIntervalIsTwoSeconds() {
        val fresh = XingruiInstrumentBridge(context).apply { isSupportedOverrideForTest = true }
        assertEquals(2000L, fresh.minCanIntervalMs)
        fresh.release()
    }

    @Test
    fun blankNowPlayingDoesNotConsumeTheCanLock() {
        bridge.updateMedia(null, null, null, isPlaying = false)
        bridge.updateMedia("  ", " ", null, isPlaying = true)
        drain()
        assertEquals(0, fakeService.updateMediaCalls.size)

        bridge.updateMedia("Bohemian Rhapsody", "Queen", "A Night at the Opera", isPlaying = true)
        drain()
        assertEquals(1, fakeService.updateMediaCalls.size)
        val call = fakeService.updateMediaCalls[0]
        assertEquals("Bohemian Rhapsody", call.title)
        assertEquals("Queen", call.artist)
        assertEquals("http://carplay", call.cover)
        assertEquals(2, call.source)
        assertTrue(tempDir.list().isNullOrEmpty())
    }

    @Test
    fun artworkForTheSameTrackDoesNotSendAgain() {
        bridge.updateMedia("Bohemian Rhapsody", "Queen", "A Night at the Opera", isPlaying = true, artworkBitmap = null)
        drain()
        val coverBmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        bridge.updateMedia("Bohemian Rhapsody", "Queen", "A Night at the Opera", isPlaying = true, artworkBitmap = coverBmp)
        drain(1600L)

        assertEquals(1, fakeService.updateMediaCalls.size)
        assertEquals("http://carplay", fakeService.updateMediaCalls[0].cover)
        assertEquals(2, fakeService.updateMediaCalls[0].source)
        assertTrue(!File(tempDir, "cover_0.jpg").exists())
        assertTrue(!File(tempDir, "default_cover.jpg").exists())
    }

    @Test
    fun rapidTrackSkip_keepsOnlyTheLatestTrackInsideTheCanWindow() {
        bridge.updateMedia("Track 1", "Artist 1", "Album 1", isPlaying = true)
        drain()
        assertEquals(1, fakeService.updateMediaCalls.size)
        assertEquals("http://carplay", fakeService.updateMediaCalls[0].cover)
        assertEquals(2, fakeService.updateMediaCalls[0].source)

        bridge.updateMedia("Track 2", "Artist 2", "Album 2", isPlaying = true)
        drain(100L)
        assertEquals(1, fakeService.updateMediaCalls.size)
        assertTrue(bridge.hasPendingThrottleForTest())

        bridge.updateMedia("Track 3", "Artist 3", "Album 3", isPlaying = true)
        drain(100L)
        assertTrue(bridge.hasPendingThrottleForTest())
        assertEquals(1, fakeService.updateMediaCalls.size)

        drain(1600L)
        assertEquals(2, fakeService.updateMediaCalls.size)
        val secondCall = fakeService.updateMediaCalls[1]
        assertEquals("Track 3", secondCall.title)
        assertEquals("http://carplay", secondCall.cover)
        assertEquals(2, secondCall.source)
        assertTrue(tempDir.list().isNullOrEmpty())
    }

    @Test
    fun callSequence_isSourceChangedThenUpdateMediaThenOnAction() {
        bridge.updateMedia("Hotel California", "Eagles", "Hotel California", isPlaying = true)
        drain()

        assertEquals(listOf("onSourceChanged:2", "updateMedia:Hotel California", "onAction:1:2"), fakeService.callOrder)
    }

    @Test
    fun replaySendsTheSameHttpPathAndDropsTheNextIdenticalUpdate() {
        val coverBmp = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        bridge.updateMedia("Song", "Artist", "Album", isPlaying = true, artworkBitmap = coverBmp)
        drain()

        assertEquals("http://carplay", fakeService.updateMediaCalls[0].cover)
        assertTrue(!File(tempDir, "cover_0.jpg").exists())

        bridge.replayCachedForTest()
        drain()

        assertEquals(2, fakeService.updateMediaCalls.size)
        assertEquals("http://carplay", fakeService.updateMediaCalls[1].cover)

        bridge.updateMedia("Song", "Artist", "Album", isPlaying = true, artworkBitmap = coverBmp)
        drain()

        assertEquals(2, fakeService.updateMediaCalls.size)
        assertTrue(!File(tempDir, "cover_1.jpg").exists())
    }

    @Test
    fun clearMedia_sendsEmptyMediaPathOnHddAudio() {
        bridge.updateMedia("Song", "Artist", "Album", isPlaying = true)
        drain()

        fakeService.callOrder.clear()
        bridge.clearMedia()
        drain()

        val lastUpdate = fakeService.updateMediaCalls.lastOrNull()
        assertNotNull(lastUpdate)
        assertEquals("", lastUpdate?.cover)
        assertEquals(2, lastUpdate?.source)
        assertEquals(listOf("onSourceChanged:2", "updateMedia: ", "onAction:2:2"), fakeService.callOrder)
        assertTrue(!File(tempDir, "cover_0.jpg").exists())
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
