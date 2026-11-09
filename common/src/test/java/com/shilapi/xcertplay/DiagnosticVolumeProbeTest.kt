package com.shilapi.xcertplay

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
class DiagnosticVolumeProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun tearDown() {
        DiagnosticVolumeProbe.volumeListProvider = null
        DiagnosticVolumeProbe.volumePathsProvider = null
        DiagnosticVolumeProbe.volumeStateProvider = null
        DiagnosticVolumeProbe.procMountsProvider = null
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

    @Test fun resolveVolumePathUsesStorageWhenItContainsFiles() {
        val raw = File("/mnt/media_rw/usbotg-sda1")
        val storage = temp.newFolder("storage")
        val storageStick = File(storage, "usbotg-sda1").apply { mkdirs() }
        File(storageStick, "test.txt").writeText("hello")

        val resolved = DiagnosticVolumeProbe.resolveVolumePath(raw, storageRoot = storage)
        assertEquals(storageStick.absolutePath, resolved.absolutePath)
    }

    @Test fun resolveVolumePathKeepsMediaRwWhenStorageDirectoryIsEmpty() {
        val raw = File("/mnt/media_rw/usbotg-sda1")
        val storage = temp.newFolder("storage")
        File(storage, "usbotg-sda1").mkdirs()

        val resolved = DiagnosticVolumeProbe.resolveVolumePath(raw, storageRoot = storage)
        assertEquals(raw.absolutePath, resolved.absolutePath)
    }

    @Test fun candidatesUsesUsbotgSdaPartitionAndDropsEmptySdcardSlots() {
        val storage = temp.newFolder("storage-root")
        val partition = File(storage, "usbotg/usbotg-sda1").apply { mkdirs() }
        File(partition, "song.mp3").writeText("a")
        val sdcard = File(storage, "sdcard1").apply { mkdirs() }
        DiagnosticVolumeProbe.volumeListProvider = { arrayOf(TestVolume(sdcard.absolutePath)) }
        DiagnosticVolumeProbe.volumePathsProvider = { emptyArray() }
        DiagnosticVolumeProbe.procMountsProvider = { "" }

        val found = DiagnosticVolumeProbe.candidates(
            context,
            storageRoot = storage,
            mediaRw = temp.newFolder("media-rw-empty"),
        )
        assertEquals(listOf(partition.absolutePath), found.map { it.absolutePath })
    }

    @Test fun candidatesIgnoresDirectoriesThatAreNotMounted() {
        DiagnosticVolumeProbe.volumeListProvider = { emptyArray<Any>() }
        DiagnosticVolumeProbe.volumePathsProvider = { emptyArray() }
        DiagnosticVolumeProbe.procMountsProvider = { "" }

        val found = DiagnosticVolumeProbe.candidates(
            context,
            storageRoot = temp.newFolder("storage-empty"),
            mediaRw = temp.newFolder("media-empty"),
        )
        assertTrue(found.none { it.name == "sdcard1" || it.name == "sdcard2" || it.name == "usbotg" })
    }

    @Test fun resolveVolumePathStaysOnMediaRwWhenStorageDirectoryDoesNotExist() {
        val raw = File("/mnt/media_rw/usbotg-sda1")
        val storage = temp.newFolder("storage")
        // Directory does not exist under storage

        val resolved = DiagnosticVolumeProbe.resolveVolumePath(raw, storageRoot = storage)
        assertEquals(raw.absolutePath, resolved.absolutePath)
    }

    @Test fun resolveVolumePathDoesNotUseAnUnmountedTwin() {
        val raw = File("/mnt/media_rw/usbotg")
        val storage = temp.newFolder("storage-real")
        val twin = File(storage, "usbotg").apply { mkdirs() }
        File(twin, "leftover.txt").writeText("x")

        val resolved = DiagnosticVolumeProbe.resolveVolumePath(
            raw,
            storageRoot = storage,
            mountedPaths = setOf(raw.absolutePath),
        )
        assertEquals(raw.absolutePath, resolved.absolutePath)
    }

    @Test fun resolveVolumePathNeverTransformsIntoGenericUsbotg() {
        val raw = File("/mnt/media_rw/usbotg-sda1")
        val storage = temp.newFolder("storage")
        val genericUsbotg = File(storage, "usbotg").apply { mkdirs() }
        File(genericUsbotg, "other.txt").writeText("other")

        val resolved = DiagnosticVolumeProbe.resolveVolumePath(raw, storageRoot = storage)
        assertEquals(raw.absolutePath, resolved.absolutePath)
        assertFalse("Path must never be rewritten to generic /storage/usbotg", resolved.absolutePath.endsWith("/storage/usbotg"))
    }

    @Test fun candidatesFiltersVolumesByRemovableMountedAndNonPrimary() {
        val validUsb = temp.newFolder("valid-usb")
        val unmountedUsb = temp.newFolder("unmounted-usb")
        val nonRemovable = temp.newFolder("non-removable")
        val primaryVol = temp.newFolder("primary-vol")

        val volumes = arrayOf(
            TestVolume(path = validUsb.absolutePath, removable = true, state = "mounted", primary = false),
            TestVolume(path = unmountedUsb.absolutePath, removable = true, state = "unmounted", primary = false),
            TestVolume(path = nonRemovable.absolutePath, removable = false, state = "mounted", primary = false),
            TestVolume(path = primaryVol.absolutePath, removable = true, state = "mounted", primary = true),
        )

        DiagnosticVolumeProbe.volumeListProvider = { volumes }

        val found = DiagnosticVolumeProbe.candidates(
            context,
            storageRoot = temp.newFolder("storage-empty"),
            mediaRw = temp.newFolder("media-empty"),
        )
        assertEquals(listOf(validUsb.absolutePath), found.map { it.absolutePath })
    }

    @Test fun candidatesFallbacksToProcMountsWhenStorageManagerEmpty() {
        val rawUsb = temp.newFolder("usbotg-sda1")
        DiagnosticVolumeProbe.volumeListProvider = { emptyArray<Any>() }
        DiagnosticVolumeProbe.volumePathsProvider = { emptyArray() }
        DiagnosticVolumeProbe.procMountsProvider = {
            "/dev/block/vold/8:1 ${rawUsb.absolutePath} vfat rw,dirsync,nosuid,nodev,noexec,relatime 0 0\n"
        }

        val found = DiagnosticVolumeProbe.candidates(
            context,
            storageRoot = temp.newFolder("storage-empty"),
            mediaRw = temp.newFolder("media-empty"),
        )
        assertEquals(listOf(rawUsb.absolutePath), found.map { it.absolutePath })
    }
}
