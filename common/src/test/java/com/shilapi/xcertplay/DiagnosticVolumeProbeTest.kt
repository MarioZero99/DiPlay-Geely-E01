package com.shilapi.xcertplay

import android.content.ContextWrapper
import org.junit.Assert.*
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
// 加载 runner 时直接报 unsupported SDK，测试一个都跑不到。这里验证的是卷枚举与 errno 格式化的
// 逻辑，API 22 的平台差异由 Task 5 在车机上验，不靠单测假装覆盖。
@Config(sdk = [23], manifest = Config.NONE)
class DiagnosticVolumeProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun emptyVolumesGeneratesClearSummary() {
        val summary = DiagnosticVolumeProbe.probe(context)
        assertTrue(summary.summaryLine.contains("未发现外置存储卷") || summary.summaryLine.contains("No external volume"))
    }

    @Test fun detectsSecondaryVolumeFromExternalFilesDirs() {
        val fakeUsbRoot = temp.newFolder("usbotg-sda1")
        val fakePkgDir = File(fakeUsbRoot, "Android/data/${context.packageName}/files").apply { mkdirs() }

        val contextWithUsb = object : ContextWrapper(context) {
            override fun getExternalFilesDirs(type: String?): Array<File?> {
                return arrayOf(context.getExternalFilesDir(null), fakePkgDir)
            }
        }
        val summary = DiagnosticVolumeProbe.probe(contextWithUsb)
        assertEquals(1, summary.volumes.size)
        val vol = summary.volumes.first()
        assertEquals("usbotg-sda1", vol.name)
        assertTrue(vol.packageWritable)
        assertTrue(vol.rootWritable)
        assertTrue(summary.summaryLine.contains("usbotg-sda1"))
    }

    @Test fun aVolumeRootBlockedByAFileIsReportedAsNotADirectory() {
        val fakeUsbRoot = temp.newFolder("usbotg-sda1")
        val fakePkgDir = File(fakeUsbRoot, "Android/data/${context.packageName}/files").apply { mkdirs() }
        // The probe's own write test must never claim the root is writable when a file of the same
        // name is in the way: mkdirs() fails and the real reason has to reach the screen.
        File(fakeUsbRoot, "DiPlay").writeText("blocks the report directory")

        val contextWithUsb = object : ContextWrapper(context) {
            override fun getExternalFilesDirs(type: String?): Array<File?> {
                return arrayOf(context.getExternalFilesDir(null), fakePkgDir)
            }
        }
        val vol = DiagnosticVolumeProbe.probe(contextWithUsb).volumes.single()
        assertFalse(vol.rootWritable)
        assertTrue(vol.packageWritable)
        assertEquals("ENOTDIR", vol.rootErrno)
    }

    @Test fun formatsPackageDirWritableAndRootEaccesTruthfully() {
        val result = VolumeProbeResult(
            name = "usbotg-sda1",
            rootDir = File("/storage/usbotg/usbotg-sda1"),
            rootWritable = false,
            rootErrno = "EACCES",
            packageDir = File("/storage/usbotg/usbotg-sda1/Android/data/com.shilapi.xcertplay/files"),
            packageWritable = true,
            packageErrno = null,
        )
        val line = DiagnosticVolumeProbe.formatSummary(listOf(result))
        assertTrue(line.contains("usbotg-sda1"))
        assertTrue(line.contains("可写(包目录)"))
        assertTrue(line.contains("EACCES"))
    }

    @Test fun capturesExtraMountPathsFromMountedBroadcast() {
        val mountedDir = temp.newFolder("mnt-media-rw-usb")
        val summary = DiagnosticVolumeProbe.probe(context, setOf(mountedDir.absolutePath))
        assertTrue(summary.volumes.any { it.name == "mnt-media-rw-usb" })
    }

    /**
     * The head unit broadcasts the same mount action for its own storage. Counting that as a stick
     * would make every report "saved to USB drive", which is the false claim this path exists to stop.
     */
    @Test fun theHeadUnitsOwnStorageIsNeverCountedAsAStick() {
        val primary = android.os.Environment.getExternalStorageDirectory()
        val summary = DiagnosticVolumeProbe.probe(context, setOf(primary.absolutePath))
        assertFalse(summary.volumes.any { it.rootDir?.absolutePath == primary.absolutePath })
    }

    @Test fun parsesProcMountsAndFiltersNonExternalFs() {
        val fakeMount1 = temp.newFolder("usbotg")
        val fakeMount2 = temp.newFolder("emulated")
        val mountsContent = """
            rootfs / rootfs ro,relatime 0 0
            tmpfs /dev tmpfs rw,nosuid,relatime,mode=755 0 0
            proc /proc proc rw,relatime 0 0
            sysfs /sys sysfs rw,relatime 0 0
            /dev/block/bootdevice/by-name/system /system ext4 ro,relatime 0 0
            /dev/block/bootdevice/by-name/userdata /data ext4 rw,nosuid,nodev 0 0
            /dev/block/vold/public:8,1 ${fakeMount1.absolutePath} vfat rw,dirsync,nosuid,nodev,noexec 0 0
            /data/media ${fakeMount2.absolutePath} fuse rw,nosuid,nodev 0 0
        """.trimIndent()

        val parsed = DiagnosticVolumeProbe.parseMountPoints(mountsContent)
        assertTrue(parsed.any { it.absolutePath == fakeMount1.absolutePath })
        assertFalse(parsed.any { it.absolutePath == "/system" })
        assertFalse(parsed.any { it.absolutePath == "/data" })
        assertFalse(parsed.any { it.absolutePath == "/dev" })
    }

    @Test fun probeTreatsMountPointItselfAsDiscreteVolumeWithoutSplittingSubdirectories() {
        val usbRoot = temp.newFolder("real-usb")
        val subDir = File(usbRoot, "MyMusic").apply { mkdirs() }
        val pkgDir = File(usbRoot, "Android/data/${context.packageName}/files").apply { mkdirs() }

        val mountsFile = temp.newFile("mounts")
        mountsFile.writeText("/dev/block/vold/8_1 ${usbRoot.absolutePath} vfat rw 0 0\n")

        val summary = DiagnosticVolumeProbe.probe(context, procMountsFile = mountsFile)
        assertEquals(1, summary.volumes.size)
        val vol = summary.volumes.first()
        assertEquals(usbRoot.absolutePath, vol.rootDir?.absolutePath)
        assertEquals(pkgDir.absolutePath, vol.packageDir?.absolutePath)
        assertFalse("Subdirectory must not become its own volume", summary.volumes.any { it.rootDir?.absolutePath == subDir.absolutePath })
    }
}
