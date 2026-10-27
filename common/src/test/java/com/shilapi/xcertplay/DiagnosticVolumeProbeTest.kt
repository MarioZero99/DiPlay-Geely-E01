package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.os.Environment
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

    @Test fun resolveVolumePathUsesStorageWhenItContainsFiles() {
        val raw = File("/mnt/media_rw/usbotg-sda1")
        val storage = temp.newFolder("storage")
        val storageStick = File(storage, "usbotg-sda1").apply { mkdirs() }
        File(storageStick, "test.txt").writeText("hello")

        val resolved = DiagnosticVolumeProbe.resolveVolumePath(raw, storageRoot = storage)
        assertEquals(storageStick.absolutePath, resolved.absolutePath)
    }

    @Test fun resolveVolumePathStaysOnMediaRwWhenStorageIsEmptyDirectory() {
        val raw = File("/mnt/media_rw/usbotg-sda1")
        val storage = temp.newFolder("storage")
        val storageStick = File(storage, "usbotg-sda1").apply { mkdirs() }
        // Directory exists but is completely empty (no files)

        val resolved = DiagnosticVolumeProbe.resolveVolumePath(raw, storageRoot = storage)
        assertEquals(raw.absolutePath, resolved.absolutePath)
    }

    @Test fun resolveVolumePathNeverTransformsIntoGenericUsbotg() {
        val raw = File("/mnt/media_rw/usbotg-sda1")
        val storage = temp.newFolder("storage")
        // Even if generic /storage/usbotg exists with files
        val genericUsbotg = File(storage, "usbotg").apply { mkdirs() }
        File(genericUsbotg, "other.txt").writeText("other")

        val resolved = DiagnosticVolumeProbe.resolveVolumePath(raw, storageRoot = storage)
        assertEquals(raw.absolutePath, resolved.absolutePath)
        assertFalse("Path must never be rewritten to generic /storage/usbotg", resolved.absolutePath.endsWith("/storage/usbotg"))
    }

    @Test fun candidatesKeepsAMountedBroadcastAndDropsTheHeadUnit() {
        val mountedDir = temp.newFolder("mnt-media-rw-usb")
        val primary = Environment.getExternalStorageDirectory()
        val found = DiagnosticVolumeProbe.candidates(
            context,
            setOf(mountedDir.absolutePath, primary.absolutePath, "/mnt/media_rw"),
            procMountsFile = null,
        )
        assertTrue(found.any { it.absolutePath == mountedDir.absolutePath })
        assertFalse(found.any { it.absolutePath == primary.absolutePath })
        assertFalse(found.any { it.absolutePath == "/mnt/media_rw" })
    }

    @Test fun parsesProcMountsAndFiltersNonExternalFs() {
        val fakeMount1 = temp.newFolder("usbotg")
        val fakeMountWithSpace = temp.newFolder("usb disk")
        val fakeMountFuseblk = temp.newFolder("ntfs_disk")
        val mountsContent = """
            rootfs / rootfs ro,relatime 0 0
            tmpfs /dev tmpfs rw,nosuid,relatime,mode=755 0 0
            proc /proc proc rw,relatime 0 0
            sysfs /sys sysfs rw,relatime 0 0
            /dev/block/bootdevice/by-name/system /system ext4 ro,relatime 0 0
            /dev/block/bootdevice/by-name/userdata /data ext4 rw,nosuid,nodev 0 0
            /dev/block/vold/public:8,1 ${fakeMount1.absolutePath} vfat rw,dirsync,nosuid,nodev,noexec 0 0
            /dev/block/vold/public:8,2 ${fakeMountWithSpace.absolutePath.replace(" ", "\\040")} texfat rw 0 0
            /dev/block/vold/public:8,3 ${fakeMountFuseblk.absolutePath} fuseblk rw 0 0
            /data/media /storage/emulated/0 fuse rw,nosuid,nodev 0 0
            /data/media /mnt/shell/emulated/0 fuse rw 0 0
        """.trimIndent()

        val parsed = DiagnosticVolumeProbe.parseMountPoints(mountsContent)
        assertTrue(parsed.any { it.absolutePath == fakeMount1.absolutePath })
        assertTrue(parsed.any { it.absolutePath == fakeMountWithSpace.absolutePath })
        assertTrue(parsed.any { it.absolutePath == fakeMountFuseblk.absolutePath })
        assertFalse(parsed.any { it.absolutePath.startsWith("/storage/emulated") })
        assertFalse(parsed.any { it.absolutePath.startsWith("/mnt/shell") })
        assertFalse(parsed.any { it.absolutePath == "/system" })
        assertFalse(parsed.any { it.absolutePath == "/data" })
        assertFalse(parsed.any { it.absolutePath == "/dev" })
    }

    @Test fun unescapesOctalSequencesInMountPath() {
        assertEquals("/storage/USB DISK", DiagnosticVolumeProbe.unescapeMountPoint("/storage/USB\\040DISK"))
        assertEquals("/mnt/media_rw/USB 1", DiagnosticVolumeProbe.unescapeMountPoint("/mnt/media_rw/USB\\0401"))
        assertEquals("/normal/path", DiagnosticVolumeProbe.unescapeMountPoint("/normal/path"))
    }
}
