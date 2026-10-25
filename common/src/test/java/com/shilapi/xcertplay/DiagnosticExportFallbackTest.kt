package com.shilapi.xcertplay

import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FileProviderPathTestShadow::class])
class DiagnosticExportFallbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val privateReports get() = File(context.filesDir, "diagnostic-reports")

    @Before fun cleanReports() {
        privateReports.deleteRecursively()
        registerReportProvider()
    }

    @Test fun saveWritesOnlyTheChosenDirectory() {
        val chosen = File(context.cacheDir, "chosen-stick").apply { mkdirs() }
        val other = File(context.cacheDir, "other-stick").apply { mkdirs() }
        val outcome = DiagnosticExportStore.save(context, chosen, "DiPlay-test.txt", "report body")
        val saved = File(chosen, "DiPlay/DiPlay-test.txt")
        assertEquals(saved.absolutePath, outcome.primaryPath)
        assertTrue(outcome.written)
        assertEquals("report body", saved.readText())
        assertFalse(File(other, "DiPlay/DiPlay-test.txt").exists())
        assertNotNull(outcome.shareableUri)
        assertEquals("report body", read(outcome.shareableUri!!))
    }

    /** The stick did not take the file. The private copy is still there, and the outcome stays a failure. */
    @Test fun aFailedChosenWriteStillKeepsThePrivateCopy() {
        val blocked = File(context.cacheDir, "not-a-dir-for-save").apply { writeText("x") }
        val directory = File(blocked, "stick")
        val outcome = DiagnosticExportStore.save(context, directory, "DiPlay-test.txt", "kept")
        assertFalse(outcome.written)
        assertEquals(File(directory, "DiPlay/DiPlay-test.txt").absolutePath, outcome.primaryPath)
        assertFalse(File(directory, "DiPlay").exists())
        assertEquals("kept", read(outcome.shareableUri!!))
    }

    @Test fun anEarlierShareUriCannotReadALaterExport() {
        val dir = File(context.cacheDir, "share-stick").apply { mkdirs() }
        val first = DiagnosticExportStore.save(context, dir, "DiPlay-test.txt", "first")
        val second = DiagnosticExportStore.save(context, dir, "DiPlay-test.txt", "second")
        assertNotEquals(first.shareableUri, second.shareableUri)
        assertEquals("first", read(first.shareableUri!!))
        assertEquals("second", read(second.shareableUri!!))
    }

    @Test fun onlyEightPrivateExportsAreRetained() {
        val dir = File(context.cacheDir, "retain-stick").apply { mkdirs() }
        var latest: Uri? = null
        repeat(12) { latest = DiagnosticExportStore.save(context, dir, "DiPlay-test.txt", "report $it").shareableUri }
        assertEquals(8, privateReports.listFiles()!!.size)
        assertEquals("report 11", read(latest!!))
    }

    @Test fun providerCannotExposeSessionLogsOrOtherPrivateFiles() {
        val dir = File(context.cacheDir, "provider-stick").apply { mkdirs() }
        DiagnosticExportStore.save(context, dir, "DiPlay-test.txt", "report")
        for (path in listOf("logs/diplay.log", "other.txt")) {
            val file = File(context.filesDir, path).apply { parentFile!!.mkdirs(); writeText("private") }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            }
        }
        val externalFile = File(context.getExternalFilesDir(null), "other.txt").apply { writeText("private") }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", externalFile)
        }
    }

    private fun read(uri: Uri) = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }

    private fun registerReportProvider() {
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        val provider = DiagnosticReportProvider().also { it.attachInfo(context, info) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
}
