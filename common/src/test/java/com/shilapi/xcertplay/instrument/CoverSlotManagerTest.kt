package com.shilapi.xcertplay.instrument

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CoverSlotManagerTest {

    private lateinit var tempDir: File
    private lateinit var manager: CoverSlotManager

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "covers_test_" + System.currentTimeMillis())
        tempDir.mkdirs()
        manager = CoverSlotManager(baseDir = tempDir)
    }

    @After
    fun tearDown() {
        manager.clear()
        tempDir.deleteRecursively()
    }

    @Test
    fun saveCover_nullOrRecycledBitmap_returnsNull() {
        assertNull(manager.saveCover(null))

        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.recycle()
        assertNull(manager.saveCover(bitmap))
    }

    @Test
    fun saveCover_rotatesPingPongSlots_andReturnsSdcardUri() {
        val bmp1 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val uri1 = manager.saveCover(bmp1)
        assertNotNull(uri1)
        assertEquals("file:///sdcard/Music/mediacenter/images/cover_0.jpg", uri1)
        assertTrue(File(tempDir, "cover_0.jpg").exists())

        val bmp2 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val uri2 = manager.saveCover(bmp2)
        assertNotNull(uri2)
        assertEquals("file:///sdcard/Music/mediacenter/images/cover_1.jpg", uri2)
        assertTrue(File(tempDir, "cover_1.jpg").exists())

        // Third save should rotate back to cover_0.jpg
        val bmp3 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val uri3 = manager.saveCover(bmp3)
        assertEquals("file:///sdcard/Music/mediacenter/images/cover_0.jpg", uri3)
    }

    @Test
    fun saveCover_scalesDownLargeImagesToMax214() {
        val largeBmp = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
        val uri = manager.saveCover(largeBmp)
        assertNotNull(uri)

        val savedFile = File(tempDir, "cover_0.jpg")
        assertTrue(savedFile.exists())
        val decoded = BitmapFactory.decodeFile(savedFile.absolutePath)
        assertNotNull(decoded)
        assertTrue("Width ${decoded.width} must be <= 214", decoded.width <= 214)
        assertTrue("Height ${decoded.height} must be <= 214", decoded.height <= 214)
        // Original aspect ratio 1000:500 (2:1) -> 214:107
        assertEquals(214, decoded.width)
        assertEquals(107, decoded.height)
    }

    @Test
    fun saveDefaultCover_generatesFallbackImage_andReturnsSdcardUri() {
        val uri = manager.saveDefaultCover(null)
        assertNotNull(uri)
        assertEquals("file:///sdcard/Music/mediacenter/images/default_cover.jpg", uri)

        val defaultFile = File(tempDir, "default_cover.jpg")
        assertTrue(defaultFile.exists())
        val decoded = BitmapFactory.decodeFile(defaultFile.absolutePath)
        assertNotNull(decoded)
        assertTrue(decoded.width <= 214)
        assertTrue(decoded.height <= 214)
    }

    @Test
    fun saveDefaultCover_withContext_rendersDrawableOnSolidBackground() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val uri = manager.saveDefaultCover(context)
        assertNotNull(uri)
        assertEquals("file:///sdcard/Music/mediacenter/images/default_cover.jpg", uri)

        val defaultFile = File(tempDir, "default_cover.jpg")
        assertTrue(defaultFile.exists())
        val decoded = BitmapFactory.decodeFile(defaultFile.absolutePath)
        assertNotNull(decoded)
        assertTrue(decoded.width <= 214)
        assertTrue(decoded.height <= 214)
    }

    @Test
    fun clear_deletesSongSlots_preservesDefaultCoverUnlessExplicit() {
        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        manager.saveCover(bmp)
        manager.saveCover(bmp)
        manager.saveDefaultCover(null)
        assertTrue(File(tempDir, "cover_0.jpg").exists())
        assertTrue(File(tempDir, "cover_1.jpg").exists())
        assertTrue(File(tempDir, "default_cover.jpg").exists())

        // Standard clear preserves default cover to avoid eMMC flash wear
        manager.clear()
        assertTrue(!File(tempDir, "cover_0.jpg").exists())
        assertTrue(!File(tempDir, "cover_1.jpg").exists())
        assertTrue("Default cover should be preserved on clear", File(tempDir, "default_cover.jpg").exists())

        manager.clear(deleteDefaultCover = true)
        assertTrue(!File(tempDir, "default_cover.jpg").exists())
    }
}
