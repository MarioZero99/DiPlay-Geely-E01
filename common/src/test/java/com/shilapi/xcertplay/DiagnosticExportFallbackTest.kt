package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FileProviderPathTestShadow::class])
class DiagnosticExportFallbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val reports get() = File(context.getExternalFilesDir(null)!!, "diagnostic-reports")
    private val privateReports get() = File(context.filesDir, "diagnostic-reports")

    @Before fun cleanReports() {
        reports.deleteRecursively()
        privateReports.deleteRecursively()
        registerReportProvider()
    }

    @Test fun androidNineSavesUtf8WithoutAPickerOrStoragePermission() {
        val report = "DiPlay · تقرير\nUSB: waiting\n"
        val saved = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", report)
        assertFalse(saved.savedInApp)
        val file = reports.listFiles()!!.single()
        assertEquals(report, file.readText(Charsets.UTF_8))
        assertEquals(file.absolutePath, saved.savedPath)
        assertEquals("content", saved.uri.scheme)
        assertEquals("${context.packageName}.diagnostic-reports", saved.uri.authority)
        assertEquals(report, read(saved.uri))
        assertEquals("text/plain", context.contentResolver.getType(saved.uri))
        val info = context.packageManager.resolveContentProvider(saved.uri.authority!!, 0)!!
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
    }

    @Test @Config(sdk = [29]) fun missingDownloadsFallsBackToAReadablePrivateReport() {
        val provider = MissingDownloadsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val saved = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", "report")
        assertTrue(provider.insertAttempted)
        assertFalse(saved.savedInApp)
        assertEquals("report", reports.listFiles()!!.single().readText())
        assertEquals("report", read(saved.uri))
    }

    @Test fun reportAlsoLandsWhereAFileManagerCanReachIt() {
        val card = File(Environment.getExternalStorageDirectory(), "DiPlay/DiPlay-test.txt")
        val downloads = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "DiPlay/DiPlay-test.txt",
        )
        card.parentFile!!.deleteRecursively()
        downloads.parentFile!!.deleteRecursively()
        DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", "report")
        assertEquals("report", card.readText())
        assertEquals("report", downloads.readText())
    }

    @Test fun exportsToUsbPackageDirWhenRootUnwritableWithFsSync() {
        val fakeUsbRoot = File(context.cacheDir, "fake-usbotg-sda1")
        val fakePkg = File(fakeUsbRoot, "Android/data/${context.packageName}/files").apply { mkdirs() }
        // E01 mounts the stick read-only for this package's root. A file of the same name is what
        // makes that true here, and the outcome must still name the package directory, not the root.
        File(fakeUsbRoot, "DiPlay").apply { deleteRecursively(); writeText("blocks the root") }
        val fakeContext = object : ContextWrapper(context) {
            override fun getExternalFilesDirs(type: String?): Array<File?> {
                return arrayOf(context.getExternalFilesDir(null), fakePkg)
            }
        }
        val outcome = DiagnosticExportStore.export(fakeContext, "DiPlay-usb-sync.txt", "usb content with fsync")
        assertEquals(ExportDestination.USB_PACKAGE_DIR, outcome.destination)
        assertTrue(outcome.primaryPath.contains("fake-usbotg-sda1"))
        assertEquals("usb content with fsync", File(outcome.primaryPath).readText())
        // Shareable Uri must be from app-owned provider, never crash on usb path
        assertEquals("${context.packageName}.diagnostic-reports", outcome.shareableUri!!.authority)
        assertTrue(outcome.allSavedPaths.contains(outcome.primaryPath))
    }

    @Test fun fallsBackToDeviceDownloadsWhenNoUsbWritable() {
        val outcome = DiagnosticExportStore.export(context, "DiPlay-fallback-device.txt", "device fallback content")
        assertEquals(ExportDestination.DEVICE_DOWNLOADS, outcome.destination)
        assertTrue(outcome.primaryPath.contains("DiPlay"))
        assertTrue(outcome.probeSummary.contains("未发现外置存储卷"))
        assertTrue(File(outcome.primaryPath).exists())
        assertEquals("device fallback content", File(outcome.primaryPath).readText())
    }

    @Test fun anEarlierShareUriCannotReadALaterExport() {
        val first = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", "first")
        val second = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", "second")
        assertNotEquals(first.uri, second.uri)
        assertEquals("first", read(first.uri))
        assertEquals("second", read(second.uri))
    }

    @Test fun onlyEightExternalExportsAreRetained() {
        var latest: Uri? = null
        repeat(12) { latest = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", "report $it").uri }
        assertEquals(8, reports.listFiles()!!.size)
        assertEquals("report 11", read(latest!!))
    }

    @Test fun providerCannotExposeSessionLogsOrOtherPrivateFiles() {
        DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", "report")
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

    @Test fun unavailableExternalStorageStillSavesPrivately() {
        val unavailableExternal = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File? = null
        }
        val saved = DiagnosticExportStore.saveWithoutPicker(unavailableExternal, "DiPlay-test.txt", "report")
        assertTrue(saved.savedInApp)
        assertNull(saved.savedPath)
        assertEquals("report", read(saved.uri))
        assertEquals("report", privateReports.listFiles()!!.single().readText())
    }

    @Test fun unwritableExternalStorageStillSavesPrivately() {
        reports.writeText("blocks directory creation")
        val saved = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", "report")
        assertTrue(saved.savedInApp)
        assertEquals("report", read(saved.uri))
        assertEquals("blocks directory creation", reports.readText())
    }

    @Test fun unavailableExternalAndPrivateStorageDoesNotReportSuccess() {
        reports.writeText("blocks directory creation")
        privateReports.writeText("blocks directory creation")
        assertThrows(IOException::class.java) {
            DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", "report")
        }
    }

    @Test fun onlyEightPrivateExportsAreRetainedWhenExternalStorageIsUnavailable() {
        reports.writeText("blocks directory creation")
        var latest: Uri? = null
        repeat(12) { latest = DiagnosticExportStore.saveWithoutPicker(context, "DiPlay-test.txt", "report $it").uri }
        assertEquals(8, privateReports.listFiles()!!.size)
        assertEquals("report 11", read(latest!!))
    }

    private fun read(uri: Uri) = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }

    private fun registerReportProvider() {
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        // Robolectric gives each test a new filesDir; refresh FileProvider's static path cache.
        val provider = DiagnosticReportProvider().also { it.attachInfo(context, info) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    private class MissingDownloadsProvider : ContentProvider() {
        var insertAttempted = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? { insertAttempted = true; return null }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
