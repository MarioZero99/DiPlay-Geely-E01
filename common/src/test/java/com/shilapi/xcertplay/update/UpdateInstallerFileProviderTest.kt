package com.shilapi.xcertplay.update

import android.content.Intent
import android.content.pm.ProviderInfo
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The URI the installer is handed, resolved through the FileProvider the app really declares.
 *
 * This class keeps the app's manifest, which [UpdateInstallerTest] drops: the FileProvider answers a
 * URI only for a root its `diagnostic_report_paths.xml` maps, and a test that runs without the
 * manifest would be asking a different provider than the one the car has. The point of the class is
 * that the update folder is among those roots -- the next test down proves the URI resolves, and
 * without the path added for it the first call would throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UpdateInstallerFileProviderTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var apk: File

    @Before fun setUp() {
        // `FileProvider` keeps its parsed roots in a static map keyed by authority, and the test
        // environment gives each test method its own storage folder. Left in place, the first
        // method's roots would be the ones the second one resolves against. The car has one storage
        // folder for the life of the process, so nothing is lost by clearing it here.
        val cache: MutableMap<*, *>? = FieldReflection.readStatic(androidx.core.content.FileProvider::class.java, "sCache")
        cache?.clear()
        val cleaner = UpdateStorageCleaner(context)
        cleaner.updateDir.mkdirs()
        apk = File(cleaner.updateDir, cleaner.apkFileName(45)).apply { writeBytes(ByteArray(4096)) }
    }

    @After fun tearDown() {
        UpdateStorageCleaner(context).updateDir.deleteRecursively()
    }

    @Test fun createInstallIntentSetsCorrectActionAndFlags() {
        val intent = UpdateInstaller.createInstallIntent(context, apk)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue("the page is an activity, but the intent is not promised that", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue("the installer must be able to read a URI it was handed", intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun createInstallIntentUsesContentUri() {
        val intent = UpdateInstaller.createInstallIntent(context, apk)

        val uri = intent.data
        assertNotNull(uri)
        assertEquals("content", uri!!.scheme)
        assertEquals("${context.packageName}.diagnostic-reports", uri.authority)
        assertTrue("the URI must name the package that was downloaded", uri.path!!.endsWith("/${apk.name}"))
    }

    @Test fun createInstallIntentPointsAtTheFileTheSystemWillRead() {
        // The URI is resolved through the same FileProvider the app declares, and read back to the
        // file on disk here: a URI that named a different file would install a different file.
        val intent = UpdateInstaller.createInstallIntent(context, apk)
        val app = context
        val provider = com.shilapi.xcertplay.DiagnosticReportProvider().apply {
            attachInfo(app, ProviderInfo().apply {
                authority = "${app.packageName}.diagnostic-reports"
                grantUriPermissions = true
            })
        }

        val cursor = provider.query(intent.data!!, arrayOf("_display_name"), null, null, null)
        assertNotNull(cursor)
        cursor!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(apk.name, it.getString(0))
        }
    }

    @Test fun installStartsTheSystemInstaller() {
        val result = UpdateInstaller.install(context, apk)

        assertTrue(result.isSuccess)
        val started = shadowOf(context).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("application/vnd.android.package-archive", started.type)
    }

    @Test fun installReportsAReasonWhenThePackageIsGone() {
        // The cleaner or the system's cache reclaim can take the file between the button and the
        // press. The page is told rather than being left with a URI that points at nothing.
        apk.delete()

        assertTrue(UpdateInstaller.install(context, apk).isFailure)
    }
}

/** Reads a private static field of a framework-adjacent class. */
private object FieldReflection {
    fun readStatic(type: Class<*>, name: String): MutableMap<*, *>? = try {
        val field = type.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        field.get(null) as? MutableMap<*, *>
    } catch (e: ReflectiveOperationException) {
        null
    }
}
