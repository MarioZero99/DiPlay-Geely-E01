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
// Robolectric 4.17 最低只支持 SDK 23：它的 DefaultSdkProvider 里注册的是 23..37，写 22 会在
// 加载 runner 时直接报 unsupported SDK，测试一个都跑不到。这里验证的是卷枚举，API 22 的平台差异
// 在车机上验，不靠单测假装覆盖。
@Config(sdk = [23], manifest = Config.NONE)
class DiagnosticVolumeProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun candidatesUsesVolumeRootNotThePackageFilesDir() {
        val usbRoot = temp.newFolder("usbotg-sda1")
        val pkg = File(usbRoot, "Android/data/${context.packageName}/files").apply { mkdirs() }
        val music = File(usbRoot, "MyMusic").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) {
            override fun getExternalFilesDirs(type: String?): Array<File?> =
                arrayOf(context.getExternalFilesDir(null), pkg)
        }
        val found = DiagnosticVolumeProbe.candidates(wrapped, emptySet(), procMountsFile = null)
        assertTrue(found.any { it.absolutePath == usbRoot.absolutePath })
        assertFalse(found.any { it.absolutePath == pkg.absolutePath })
        assertFalse(found.any { it.absolutePath == music.absolutePath })
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

    @Test fun filtersOutLinuxHostMountsLikeHomeAndBoot() {
        val fakeBoot = temp.newFolder("boot")
        val fakeHome = temp.newFolder("home")
        val mountsContent = """
            /dev/root / ext4 rw,relatime 0 0
            /dev/sda1 ${fakeBoot.absolutePath} ext4 rw,relatime 0 0
            /dev/sdb1 ${fakeHome.absolutePath} ext4 rw,relatime 0 0
        """.trimIndent()
        val parsed = DiagnosticVolumeProbe.parseMountPoints(mountsContent)
        assertTrue("Linux host mounts must be ignored", parsed.isEmpty())
    }
}
