package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
class DiagnosticVolumeMountTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun isMountedFileSystemRejectsRootfsPaths() {
        val mountsContent = """
            rootfs / rootfs ro,relatime 0 0
            tmpfs /dev tmpfs rw,nosuid,relatime,mode=755 0 0
            devpts /dev/pts devpts rw,relatime,mode=600 0 0
            proc /proc proc rw,relatime 0 0
            sysfs /sys sysfs rw,relatime 0 0
            /dev/block/platform/mtk-msdc.0/by-name/system /system ext4 ro,seclabel,relatime,data=ordered 0 0
            /dev/block/platform/mtk-msdc.0/by-name/userdata /data ext4 rw,seclabel,nosuid,nodev,noatime,discard,noauto_da_alloc,data=ordered 0 0
        """.trimIndent()

        // /storage/usbotg is just an unmounted directory on rootfs
        val isMounted = DiagnosticVolumeProbe.isMountedFileSystem(File("/storage/usbotg"), mountsContent)
        assertFalse("/storage/usbotg must NOT be considered a mounted external volume when missing from /proc/mounts", isMounted)
    }

    @Test
    fun isMountedFileSystemAcceptsRealBlockDeviceMount() {
        val mountsContent = """
            rootfs / rootfs ro,relatime 0 0
            /dev/block/sda1 /mnt/udisk vfat rw,dirsync,nosuid,nodev,noexec,relatime,uid=1000,gid=1015,fmask=0702,dmask=0702,allow_utime=0020,codepage=cp437,iocharset=iso8859-1,shortname=mixed,utf8,errors=remount-ro 0 0
        """.trimIndent()

        val isMounted = DiagnosticVolumeProbe.isMountedFileSystem(File("/mnt/udisk"), mountsContent)
        assertTrue("/mnt/udisk should be recognized as a mounted filesystem", isMounted)
    }

    @Test
    fun parseMountPointsFindsExternalVolumes() {
        val fakeUsb = temp.newFolder("usbotg")
        val mountsContent = """
            rootfs / rootfs ro,relatime 0 0
            /dev/block/vold/8:1 ${fakeUsb.absolutePath} vfat rw,dirsync,nosuid,nodev,noexec,relatime 0 0
        """.trimIndent()
        val mounts = DiagnosticVolumeProbe.parseMountPoints(mountsContent)
        assertTrue(mounts.any { it.absolutePath == fakeUsb.absolutePath })
    }
}
