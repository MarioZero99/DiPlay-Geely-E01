package com.shilapi.xcertplay

import android.content.Context
import android.os.Environment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagnosticExportStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun tearDown() {
        DiagnosticVolumeProbe.volumeListProvider = null
    }

    class TestVolume(
        val path: String,
        val removable: Boolean = true,
        val state: String = "mounted",
        val primary: Boolean = false,
        val description: String = "USB 存储",
    ) {
        fun isRemovable(): Boolean = removable
        fun isPrimary(): Boolean = primary
        fun getDescription(context: Context): String = description
    }

    @Test fun foldersKeepTheOnlyListedCopyOfTheStick() {
        val raw = File(context.cacheDir, "mnt/media_rw/usbotg-sda1").apply { mkdirs() }
        File(raw, "song.mp3").writeText("a")
        DiagnosticVolumeProbe.volumeListProvider = { arrayOf(TestVolume(raw.absolutePath)) }

        val folders = DiagnosticExportStore.folders(context)
        assertTrue(folders.any { it.usb && it.directory.absolutePath == raw.absolutePath })
        assertFalse(File(raw, "DiPlay").exists())
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assertEquals(downloads.absolutePath, folders.last().directory.absolutePath)
        assertFalse(folders.last().usb)
    }

    @Test fun childrenPutsDirectoriesFirst() {
        val dir = File(context.cacheDir, "stick-list").apply { mkdirs() }
        File(dir, "b.txt").writeText("b")
        File(dir, "a-dir").mkdirs()
        val names = DiagnosticExportStore.children(dir).map { it.name }
        assertEquals(listOf("a-dir", "b.txt"), names)
    }

    @Test fun exportViaRootGracefullyHandlesUnavailableService() {
        val target = File("/storage/usbotg/DiPlay/report.txt")
        assertFalse(DiagnosticExportStore.exportViaRoot(target, "test report", context.cacheDir))
    }

    @Test fun formatVolumeLabelFormatsUsbDriveFriendlyName() {
        val usbFile = File("/storage/usbotg")
        val label = DiagnosticExportStore.formatVolumeLabel(context, usbFile, usb = true)
        assertTrue("label should describe USB and include usbotg: $label", label.contains("usbotg") && (label.contains("USB") || label.contains("U 盘")))

        val sdFile = File("/storage/sdcard1")
        val sdLabel = DiagnosticExportStore.formatVolumeLabel(context, sdFile, usb = true)
        assertTrue("sdLabel should describe SD and include sdcard1: $sdLabel", sdLabel.contains("sdcard1") && (sdLabel.contains("SD") || sdLabel.contains("SD 卡")))

        val internal = DiagnosticExportStore.formatVolumeLabel(context, File("/storage/emulated/0/Download"), usb = false)
        assertTrue("internal storage label: $internal", internal.isNotBlank())
    }

    @Test fun formatVolumeDetailIncludesAbsolutePath() {
        val usbFile = File("/storage/usbotg")
        val detail = DiagnosticExportStore.formatVolumeDetail(context, usbFile)
        assertTrue("detail must include path: $detail", detail.contains("/storage/usbotg"))
    }
}
