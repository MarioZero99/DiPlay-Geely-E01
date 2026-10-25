package com.shilapi.xcertplay

import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun foldersKeepTheOnlyListedCopyOfTheStick() {
        val raw = File(context.cacheDir, "mnt/media_rw/usbotg-sda1").apply { mkdirs() }
        File(raw, "song.mp3").writeText("a")
        val folders = DiagnosticExportStore.folders(context, setOf(raw.absolutePath), procMountsFile = null)
        assertTrue(folders.any { it.usb && it.directory.absolutePath == raw.absolutePath })
        assertFalse(File(raw, "DiPlay").exists())
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assertEquals(downloads.absolutePath, folders.last().directory.absolutePath)
        assertFalse(folders.last().usb)
    }

    @Test fun foldersPreferTheStorageCopyWhenBothList() {
        val raw = File(context.cacheDir, "mnt/media_rw/usbotg-sda1").apply { mkdirs() }
        val shown = File(context.cacheDir, "storage/usbotg/usbotg-sda1").apply { mkdirs() }
        val folders = DiagnosticExportStore.folders(
            context,
            setOf(raw.absolutePath, shown.absolutePath),
            procMountsFile = null,
        )
        val sticks = folders.filter { it.directory.name == "usbotg-sda1" }
        assertEquals(shown.absolutePath, sticks.single().directory.absolutePath)
    }

    @Test fun foldersDropAPathTheAppCannotList() {
        val blocked = File(context.cacheDir, "not-a-directory").apply { writeText("x") }
        val folders = DiagnosticExportStore.folders(context, setOf(blocked.absolutePath), procMountsFile = null)
        assertTrue(folders.none { it.directory.absolutePath == blocked.absolutePath })
    }

    /**
     * Robolectric still lists a directory after chmod, so an unlistable stick is a [File] whose
     * [File.list] returns null. That is the car's `/mnt/media_rw` when the app cannot read it.
     */
    @Test fun anUnlistableDirectoryIsStillChosen() {
        val raw = File(context.cacheDir, "mnt/media_rw/locked-stick").apply { mkdirs() }
        val unlistable = UnlistableDir(raw)
        assertNull(unlistable.list())
        val chosen = DiagnosticExportStore.chooseMounts(listOf(unlistable))
        assertEquals(raw.absolutePath, chosen.single().absolutePath)
    }

    /** A listable raw stick stays. An unlistable /storage twin must not replace it. */
    @Test fun aListableRawStickBeatsAnUnlistableTwin() {
        val raw = File(context.cacheDir, "mnt/media_rw/twin-stick").apply { mkdirs() }
        val shown = File(context.cacheDir, "storage/twin-stick").apply { mkdirs() }
        val chosen = DiagnosticExportStore.chooseMounts(listOf(UnlistableDir(shown), raw))
        assertEquals(listOf(raw.absolutePath), chosen.map { it.absolutePath })
    }

    /** When neither copy can be listed, still prefer the path outside `/mnt/media_rw/`. */
    @Test fun anUnlistableStorageCopyBeatsAnUnlistableRawCopy() {
        val raw = File(context.cacheDir, "mnt/media_rw/twin-stick").apply { mkdirs() }
        val shown = File(context.cacheDir, "storage/twin-stick").apply { mkdirs() }
        val chosen = DiagnosticExportStore.chooseMounts(listOf(UnlistableDir(raw), UnlistableDir(shown)))
        assertEquals(listOf(shown.absolutePath), chosen.map { it.absolutePath })
    }

    @Test fun candidatesReturnsTheMountRootNotThePackageFilesDir() {
        val usbRoot = File(context.cacheDir, "real-usb").apply { mkdirs() }
        File(usbRoot, "MyMusic").mkdirs()
        val pkg = File(usbRoot, "Android/data/${context.packageName}/files").apply { mkdirs() }
        val mounts = File(context.cacheDir, "mounts-root").apply {
            writeText("/dev/block/vold/8_1 ${usbRoot.absolutePath} vfat rw 0 0\n")
        }
        val found = DiagnosticVolumeProbe.candidates(context, emptySet(), mounts)
        assertEquals(listOf(usbRoot.absolutePath), found.map { it.absolutePath })
        assertFalse(found.any { it.absolutePath == pkg.absolutePath })
        assertFalse(found.any { it.name == "MyMusic" })
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

    /** [File.list] is not final. Returning null is the unlistable stick this process cannot chmod into. */
    private class UnlistableDir(path: File) : File(path.absolutePath) {
        override fun list(): Array<String>? = null
    }
}
