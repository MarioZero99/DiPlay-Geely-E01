package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", shadows = [FileProviderPathTestShadow::class])
class DiagnosticExportUiTest {
    @Before fun clearPerformanceSamples() = PerfTrace.reset()

    /**
     * The header is where a reader looks for "why is it slow"; a timing block that only appears in a
     * log section would be behind the byte budget's ranking, which puts failures first.
     */
    @Test fun theHeaderCarriesTheTimingBlock() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        PerfTrace.record("ui/tick", 3, warnAboveMs = 8)
        PerfTrace.record("ui/tick", 12, warnAboveMs = 8)
        PerfTrace.mark("startup", "startup/first-draw")
        val header = ReflectionHelpers.callInstanceMethod<String>(
            activity, "buildDiagnosticHeader",
            ReflectionHelpers.ClassParameter.from(Context::class.java, activity.applicationContext),
        )
        assertTrue(header, header.contains("--- Performance"))
        assertTrue(header, header.contains("ui/tick"))
        assertTrue(header, header.contains("p95=12"))
        assertTrue(header, header.contains("startup/first-draw"))
        // The loss counters and the timing block are neighbours: a report that says nothing was lost
        // and then quotes nothing is a different story from one that says why.
        assertTrue(header, header.indexOf("Log losses") < header.indexOf("--- Performance"))
    }

    /** No stick: open phone storage, save, and the card names the file under Download/DiPlay. */
    @Test fun savingPhoneStorageWritesUnderDownloadDiPlay() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        registerReportProvider(activity.applicationContext)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
            clickRow(activity, activity.getString(R.string.export_internal_storage))
            clickRow(activity, activity.getString(R.string.export_save_to_disk))
            val body = awaitText(activity, "/DiPlay/DiPlay-")
            val downloads = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "DiPlay",
            )
            val saved = downloads.listFiles()!!.single { it.name.startsWith("DiPlay-") }
            assertTrue(body.text.contains(saved.absolutePath))
            assertTrue(saved.readText().contains("API ${android.os.Build.VERSION.SDK_INT}"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /**
     * A stick is confirmed by the files on it. Saving from a subfolder still writes the mount root,
     * and tapping a file leaves the card and the save button where they are.
     */
    @Test fun browsingAStickShowsItsFilesAndSavesAtTheMountRoot() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val context = activity.applicationContext
        registerReportProvider(context)
        val stick = File(context.cacheDir, "usb-otg-sda1").apply { deleteRecursively(); mkdirs() }
        File(stick, "readme.txt").writeText("hello")
        File(stick, "Music").mkdirs()
        ReflectionHelpers.getField<MutableSet<String>>(activity, "mountedUsbPaths").add(stick.absolutePath)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
            awaitText(activity, stick.name)
            awaitText(activity, stick.absolutePath)
            clickRow(activity, stick.name)
            awaitText(activity, "readme.txt")
            awaitText(activity, "Music/")
            texts(activity).first { it.text == "readme.txt" }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val save = texts(activity).first { it.text == activity.getString(R.string.export_save_to_disk) }
            assertTrue(save.isEnabled)
            assertTrue(save.isAttachedToWindow)
            clickRow(activity, "Music/")
            awaitText(activity, activity.getString(R.string.export_empty))
            val whileInside = texts(activity).map { it.text.toString() }
            assertTrue(whileInside.any { it == File(stick, "DiPlay").absolutePath })
            assertFalse(whileInside.any { it.contains("/Music/DiPlay") })
            clickRow(activity, activity.getString(R.string.export_save_to_disk))
            val body = awaitText(activity, "/DiPlay/DiPlay-")
            val saved = File(stick, "DiPlay").listFiles()!!.single { it.isFile }
            assertEquals(saved.absolutePath, body.text.lines().first { it.contains("usb-otg-sda1") })
            assertFalse(File(stick, "Music/DiPlay").exists())
            assertNotNull(texts(activity).firstOrNull { it.text == activity.getString(R.string.share) })
        } finally {
            stick.deleteRecursively()
            controller.pause().stop().destroy()
        }
    }

    /**
     * The header is what places a report: which build, which car, which settings. It is also the only
     * place the reason authentication failed can appear — "authentication unavailable" on its own cannot
     * tell a missing asset from a rejected certificate, and the exception itself went to logcat, which a
     * head unit with no adb cannot show anyone.
     */
    @Test fun theHeaderNamesTheReasonAuthenticationFailed() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        ReflectionHelpers.setField(activity, "setupErrorDetail", "IdentityMissing: no offline-mfi assets")
        val header = ReflectionHelpers.callInstanceMethod<String>(
            activity, "buildDiagnosticHeader",
            ReflectionHelpers.ClassParameter.from(Context::class.java, activity.applicationContext),
        )
        assertTrue("the reason must be in the header, not only the symptom", header.contains("IdentityMissing"))
        assertTrue(header.contains("CarPlay setup: authentication unavailable — IdentityMissing: no offline-mfi assets"))
    }

    @Test fun aSetupThatDidNotFailIsReportedAsReady() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        ReflectionHelpers.setField(activity, "setupErrorDetail", null)
        val header = ReflectionHelpers.callInstanceMethod<String>(
            activity, "buildDiagnosticHeader",
            ReflectionHelpers.ClassParameter.from(Context::class.java, activity.applicationContext),
        )
        assertTrue(header.contains("CarPlay setup: ready"))
        assertFalse(header.contains("authentication unavailable"))
    }

    /**
     * The failure this exercises is real: the offline-mfi assets are deliberately not in the tree, so the
     * bootstrap fails here exactly as it does on a build without them.
     */
    @Test fun aFailedBootstrapKeepsTheExceptionForTheHeader() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().get()
        val detail = ReflectionHelpers.getField<String>(activity, "setupErrorDetail")
        assertNotNull("setupError alone cannot say which failure it was", detail)
        assertTrue("the detail must name a class and a message", detail!!.contains(":"))
    }

    private fun registerReportProvider(context: Context) {
        val authority = "${context.packageName}.diagnostic-reports"
        if (context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA) == null) return
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        ShadowContentResolver.registerProviderInternal(authority, DiagnosticReportProvider().apply { attachInfo(context, info) })
    }

    private fun awaitText(activity: DiPlayActivity, snippet: String): TextView {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val hit = texts(activity).firstOrNull { it.text?.contains(snippet) == true }
            if (hit != null) return hit
            Thread.sleep(20)
        }
        error("timed out waiting for $snippet")
    }

    private fun clickRow(activity: DiPlayActivity, snippet: String) {
        val label = awaitText(activity, snippet)
        var current: View = label
        while (!current.isClickable) current = current.parent as View
        current.performClick()
    }

    private fun texts(activity: DiPlayActivity): List<TextView> =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
