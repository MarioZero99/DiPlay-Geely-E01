package com.shilapi.xcertplay

import android.content.Context
import android.os.Environment
import com.shilapi.xcertplay.host.R
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
        DiagSink.reset()
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

    @Test fun foldersPreferMountedVolumeOverLibaums() {
        val raw = File(context.cacheDir, "mnt/media_rw/usbotg-sda1").apply { mkdirs() }
        File(raw, "song.mp3").writeText("a")
        DiagnosticVolumeProbe.volumeListProvider = { arrayOf(TestVolume(raw.absolutePath)) }
        UsbMassStorageManager.deviceListProvider = { listOf(DiagnosticExportStoreTest.blankUsbDevice()) }
        var opens = 0
        UsbMassStorageManager.openFileSystemProvider = { opens += 1; null }
        UsbMassStorageManager.hasPermissionProvider = { _, _ -> true }

        val folders = DiagnosticExportStore.folders(context)
        val usb = folders.filter { it.usb }
        assertEquals(1, usb.size)
        assertEquals(raw.absolutePath, usb.single().directory?.absolutePath)
        assertTrue(usb.single().usbDevice == null)
        assertEquals(0, opens)
    }

    @Test fun foldersListsLibaumsDeviceWithoutOpeningWhenNothingIsMounted() {
        var opens = 0
        UsbMassStorageManager.deviceListProvider = { listOf(DiagnosticExportStoreTest.blankUsbDevice()) }
        UsbMassStorageManager.openFileSystemProvider = { opens += 1; null }
        UsbMassStorageManager.hasPermissionProvider = { _, _ -> true }
        DiagnosticVolumeProbe.volumeListProvider = { emptyArray<Any>() }
        DiagnosticVolumeProbe.volumePathsProvider = { emptyArray() }
        DiagnosticVolumeProbe.procMountsProvider = { "" }

        val folders = DiagnosticExportStore.folders(context)
        val usb = folders.filter { it.usbDevice != null }
        assertEquals(1, usb.size)
        assertEquals("U 盘 (USB 存储)", usb.single().label)
        assertEquals(context.getString(R.string.export_usb_direct_detail), usb.single().detail)
        assertEquals("USB: /", usb.single().displayPath)
        assertEquals(0, opens)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assertEquals(downloads.absolutePath, folders.last().directory?.absolutePath)
        assertFalse(folders.last().usb)
    }

    @Test fun foldersRejectShellsEvenWhenTheProbeReturnsThem() {
        val shell = File(context.cacheDir, "storage/usbotg").apply { mkdirs() }
        File(shell, "song.mp3").writeText("a")
        val sd = File(context.cacheDir, "storage/sdcard1").apply { mkdirs() }
        File(sd, "song.mp3").writeText("a")
        DiagnosticVolumeProbe.volumeListProvider = {
            arrayOf(TestVolume(shell.absolutePath), TestVolume(sd.absolutePath, description = "SD"))
        }
        UsbMassStorageManager.deviceListProvider = { listOf(DiagnosticExportStoreTest.blankUsbDevice()) }
        var opens = 0
        UsbMassStorageManager.openFileSystemProvider = { opens += 1; null }

        val folders = DiagnosticExportStore.folders(context)
        assertTrue(folders.none { it.directory?.absolutePath == shell.absolutePath })
        assertTrue(folders.none { it.directory?.absolutePath == sd.absolutePath })
        assertEquals(1, folders.count { it.usbDevice != null })
        assertEquals(0, opens)
    }

    @Test fun isRealMountedStickRejectsTheKnownShells() {
        assertFalse(DiagnosticExportStore.isRealMountedStick(File("/storage/usbotg")))
        assertFalse(DiagnosticExportStore.isRealMountedStick(File("/storage/udisk")))
        assertFalse(DiagnosticExportStore.isRealMountedStick(File("/storage/sdcard1")))
        assertFalse(DiagnosticExportStore.isRealMountedStick(File("/mnt/media_rw/sdcard2")))
        assertTrue(DiagnosticExportStore.isRealMountedStick(File("/storage/usbotg/usbotg-sda1")))
        assertTrue(DiagnosticExportStore.isRealMountedStick(File("/mnt/media_rw/udisk1")))
    }

    @Test fun describeOpenUsesFourHexDigitsAndNoneForNoError() {
        val line = UsbMassStorageManager.describeOpen(
            vendorId = 0x0951,
            productId = 0x1666,
            permission = true,
            facts = UsbMassStorageManager.UsbOpenFacts(partitionCount = 1, fat32 = true, error = null),
        )
        assertEquals("usb open vid=0951 pid=1666 perm=true partitions=1 fat32=true error=none", line)
    }

    @Test fun saveToUsbLogsTheMissAndDoesNotCreateAShell() {
        UsbMassStorageManager.openFileSystemProvider = { null }
        UsbMassStorageManager.hasPermissionProvider = { _, _ -> true }
        val lines = mutableListOf<String>()
        DiagSink.attach { lines.add(it) }
        val shells = listOf(File("/mnt/media_rw/usbotg/DiPlay"), File("/storage/usbotg/DiPlay"))

        val outcome = DiagnosticExportStore.saveToUsb(context, DiagnosticExportStoreTest.blankUsbDevice(), "report.txt", "hello")

        assertFalse(outcome.written)
        assertEquals("USB: /DiPlay/report.txt", outcome.primaryPath)
        assertTrue(shells.none { it.exists() })
        val text = lines.joinToString("\n")
        assertTrue(text, text.contains("usb open vid=0000 pid=0000 perm=true partitions=0 fat32=false error=no filesystem"))
        assertTrue(text, text.contains("usb save written=false bytes=5 path=USB:/DiPlay/report.txt"))
    }

    @Test fun lengthFromLsReadsTheSizeBeforeTheDate() {
        assertEquals(1234L, DiagnosticExportStore.lengthFromLs("-rw-r--r-- 1 root root 1234 2026-11-04 10:17 report.txt"))
        assertEquals(12L, DiagnosticExportStore.lengthFromLs("-rw-r--r-- root root 12 10:17 report.txt"))
        assertEquals(null, DiagnosticExportStore.lengthFromLs("ls: report.txt: No such file"))
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
            DiagnosticExportStore.saveToUsb(context, DiagnosticExportStoreTest.blankUsbDevice(), "report.txt", "hello")
        }.getOrNull()
        if (outcome != null) {
            assertFalse(outcome.written)
            assertFalse(outcome.primaryPath.contains("/mnt/media_rw/usbotg"))
            assertFalse(outcome.primaryPath.contains("/storage/usbotg"))
        }
        assertTrue(shells.none { it.exists() })
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

    companion object {
        internal fun blankUsbDevice(): UsbMassStorageDevice {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, UsbMassStorageDevice::class.java) as UsbMassStorageDevice
        }
    }
}
