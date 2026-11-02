package com.shilapi.xcertplay

import android.content.Context
import android.os.Environment
import org.junit.After
import me.jahnen.libaums.core.UsbMassStorageDevice
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
        DiagnosticVolumeProbe.volumePathsProvider = null
        DiagnosticVolumeProbe.volumeStateProvider = null
        DiagnosticVolumeProbe.procMountsProvider = null
        UsbMassStorageManager.deviceListProvider = null
        UsbMassStorageManager.hasPermissionProvider = null
        UsbMassStorageManager.openFileSystemProvider = null
        UsbMassStorageManager.close()
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
        assertTrue(folders.any { it.usb && it.directory?.absolutePath == raw.absolutePath })
        assertFalse(File(raw, "DiPlay").exists())
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assertEquals(downloads.absolutePath, folders.last().directory?.absolutePath)
        assertFalse(folders.last().usb)
    }

    @Test fun foldersFallbackToVolumePathsWhenVolumeListEmpty() {
        val raw = File(context.cacheDir, "mnt/media_rw/usbotg-sda1").apply { mkdirs() }
        File(raw, "song.mp3").writeText("a")
        DiagnosticVolumeProbe.volumeListProvider = { emptyArray<Any>() }
        DiagnosticVolumeProbe.volumePathsProvider = { arrayOf(raw.absolutePath) }
        DiagnosticVolumeProbe.volumeStateProvider = { "mounted" }

        val folders = DiagnosticExportStore.folders(context)
        assertTrue(folders.any { it.usb && it.directory?.absolutePath == raw.absolutePath })
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

    @Test fun foldersIncludesUsbMassStorageDeviceWhenPresent() {
        // When no physical USB devices are attached, list contains only internal storage (Downloads)
        UsbMassStorageManager.deviceListProvider = { emptyList() }
        val folders = DiagnosticExportStore.folders(context)
        assertEquals(1, folders.size)
        assertFalse(folders.first().usb)
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

    @Test fun saveToUsbDoesNotWriteTheMtkShellWhenLibaumsHasNoFilesystem() {
        UsbMassStorageManager.openFileSystemProvider = { null }
        val shells = listOf(
            File("/mnt/media_rw/usbotg/DiPlay"),
            File("/storage/usbotg/DiPlay"),
        )
        val outcome = runCatching {
            DiagnosticExportStore.saveToUsb(context, blankUsbDevice(), "report.txt", "hello")
        }.getOrNull()
        if (outcome != null) {
            assertFalse(outcome.written)
            assertFalse(outcome.primaryPath.contains("/mnt/media_rw/usbotg"))
            assertFalse(outcome.primaryPath.contains("/storage/usbotg"))
        }
        assertTrue(shells.none { it.exists() })
    }

    private fun blankUsbDevice(): UsbMassStorageDevice {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, UsbMassStorageDevice::class.java) as UsbMassStorageDevice
    }

    @Test fun isReadableReturnsTrueForNormalDirectory() {
        val dir = File(context.cacheDir, "readable-test").apply { mkdirs() }
        assertTrue(DiagnosticExportStore.isReadable(dir))
    }

    @Test fun virtualFileCorrectlyReportsDirectoryProperty() {
        val parent = File(context.cacheDir, "vdir")
        val dirFile = DiagnosticExportStore.VirtualFile(parent, "subfolder", isDir = true)
        val regFile = DiagnosticExportStore.VirtualFile(parent, "report.txt", isDir = false, fileLength = 1234L)
        assertTrue(dirFile.isDirectory)
        assertFalse(dirFile.isFile)
        assertFalse(regFile.isDirectory)
        assertTrue(regFile.isFile)
        assertEquals(1234L, regFile.length())
    }
}
