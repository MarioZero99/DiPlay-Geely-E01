package com.shilapi.xcertplay.update

import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.security.MessageDigest

/**
 * The folder the update link keeps its downloads in, and what it throws away.
 *
 * The head unit has about 2.8 GB of storage and a whole update is a little over 6 MB, so the budget
 * is not tight -- but nothing here may leak either. A download interrupted by the ignition (the car
 * powers the head unit off, it does not ask) leaves a `.download` fragment behind, and a package that
 * was downloaded for a version that is now installed is a package that is now useless. Both go on the
 * first run, before anything is downloaded on top of them.
 *
 * The version of a file on disk is read with `getPackageArchiveInfo`, because the file name carries
 * it too but the file name is a claim: it is written by whoever made the file, and a half-written APK
 * can carry the name of a complete one. [declares] stands in for what the package manager would
 * answer, so none of this needs a real signed APK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UpdateStorageCleanerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val packageManager = shadowOf(context.packageManager)
    private lateinit var cleaner: UpdateStorageCleaner

    @Before fun setUp() {
        cleaner = UpdateStorageCleaner(context)
        cleaner.updateDir.mkdirs()
    }

    /** The name the release itself uses, so a test cannot pass on a name the downloader never writes. */
    private fun apk(versionCode: Int): File =
        File(cleaner.updateDir, cleaner.apkFileName(versionCode)).apply { writeBytes(ByteArray(versionCode)) }

    /** Says what the package manager answers when asked for this file's manifest. */
    private fun declares(file: File, versionCode: Int) {
        packageManager.setPackageArchiveInfo(file.absolutePath, PackageInfo().apply { this.versionCode = versionCode })
    }

    @Test fun theFolderIsOnePathForEveryoneToAgreeOn() {
        // Written to and read from through the same door: the cleaner and the downloader must not
        // resolve the folder differently, or a download lands where the cleaner does not look.
        val dir = cleaner.updateDir
        assertEquals(dir.absolutePath, dir.canonicalPath)
        assertTrue(dir.isDirectory)
    }

    @Test fun cleanOldUpdatesDeletesTmpDownloadFiles() {
        val fragment = File(cleaner.updateDir, cleaner.apkFileName(45) + UpdateStorageCleaner.PARTIAL_SUFFIX).apply { writeBytes(ByteArray(8)) }
        val untouched = apk(45).apply { declares(this, 45) }

        cleaner.cleanOldUpdates(currentVersionCode = 44)

        assertFalse("a partial download is never resumable and must go", fragment.exists())
        assertTrue("a newer complete package is exactly what the folder is for", untouched.exists())
    }

    @Test fun cleanOldUpdatesDeletesOlderOrEqualVersionApks() {
        val older = apk(43).apply { declares(this, 43) }
        val current = apk(44).apply { declares(this, 44) }
        val newer = apk(45).apply { declares(this, 45) }

        cleaner.cleanOldUpdates(currentVersionCode = 44)

        assertFalse(older.exists())
        assertFalse("reinstalling what is already running buys nothing", current.exists())
        assertTrue(newer.exists())
    }

    @Test fun cleanOldUpdatesKeepsWhatThePackageManagerCannotRead() {
        // "Unreadable" is not the same as "corrupt": a package manager that cannot look inside
        // archives answers null for every package there is. Whether a file is installable is asked
        // where it matters -- [findReadyApk]'s caller and the install press -- and that answer can
        // end in a download. Deleting on this answer would delete the one package the next launch
        // was going to offer.
        val unreadable = apk(45)

        cleaner.cleanOldUpdates(currentVersionCode = 44)

        assertTrue(unreadable.exists())
        assertFalse("and it is not offered as installable", cleaner.isInstallable(unreadable))
    }

    @Test fun isInstallableIsAboutThePackageNotItsName() {
        val readable = apk(45).also { declares(it, 45) }
        val notAPackage = apk(46)

        assertTrue(cleaner.isInstallable(readable))
        assertFalse("a name and a size are not a package", cleaner.isInstallable(notAPackage))
        assertFalse("nor is a file that is not there", cleaner.isInstallable(File(cleaner.updateDir, "gone.apk")))
    }

    @Test fun cleanOldUpdatesKeepsTheFolderItself() {
        cleaner.cleanOldUpdates(currentVersionCode = 44)

        assertTrue("the next download writes here", cleaner.updateDir.isDirectory)
    }

    @Test fun hasAdequateFreeSpaceReturnsTrueWhenSufficient() {
        ShadowStatFs.registerStats(cleaner.updateDir, 100_000, 0, 100_000)
        try {
            assertTrue(cleaner.hasAdequateFreeSpace(requiredBytes = 25L * 1024 * 1024))
        } finally {
            ShadowStatFs.unregisterStats(cleaner.updateDir)
        }
    }

    @Test fun hasAdequateFreeSpaceIsJudgedBeforeTheFolderExists() {
        // The cleaner runs on the first launch, when no download has ever been made and the folder
        // is not there yet. StatFs answers 0 for a path that does not exist, so the answer has to be
        // asked of a folder that does.
        cleaner.updateDir.deleteRecursively()
        ShadowStatFs.registerStats(cleaner.updateDir, 100_000, 0, 100_000)
        try {
            assertTrue(cleaner.hasAdequateFreeSpace(requiredBytes = 25L * 1024 * 1024))
        } finally {
            ShadowStatFs.unregisterStats(cleaner.updateDir)
        }
    }

    @Test fun hasAdequateFreeSpaceReturnsFalseWhenTheCarIsFull() {
        // Ten 4 KB blocks: 40 KB, enough to make a file and nothing like enough to hold an update.
        ShadowStatFs.registerStats(cleaner.updateDir, 10, 0, 10)
        try {
            assertFalse(cleaner.hasAdequateFreeSpace(requiredBytes = 25L * 1024 * 1024))
        } finally {
            ShadowStatFs.unregisterStats(cleaner.updateDir)
        }
    }

    @Test fun findReadyApkReturnsFileWhenShaMatches() {
        val ready = apk(45)
        val sha = sha256Of(ready.readBytes())

        assertEquals(ready, cleaner.findReadyApk(45, sha))
    }

    @Test fun findReadyApkRefusesAFileThatDoesNotMatchItsChecksum() {
        apk(45)

        assertNull(cleaner.findReadyApk(45, "0".repeat(64)))
    }

    @Test fun findReadyApkWithoutAChecksumOnlyNeedsTheFile() {
        val ready = apk(45)

        assertEquals(ready, cleaner.findReadyApk(45, expectedSha256 = null))
        assertNull(cleaner.findReadyApk(46, expectedSha256 = null))
    }

    @Test fun findReadyApkIgnoresAnEmptyFile() {
        File(cleaner.updateDir, cleaner.apkFileName(45)).createNewFile()

        assertNull(cleaner.findReadyApk(45, expectedSha256 = null))
    }

    @Test fun findReadyApkIsNotFooledByACaseInsensitiveChecksum() {
        // The sums file GitHub carries is lower case; an upper-case copy of the same digest is the
        // same digest, and refusing it would send the driver through the download again.
        val ready = apk(45)

        assertNotNull(cleaner.findReadyApk(45, sha256Of(ready.readBytes()).uppercase()))
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
