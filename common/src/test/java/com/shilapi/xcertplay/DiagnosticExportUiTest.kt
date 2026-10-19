package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
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

    /**
     * One exit, and the dialog says what actually happened: no stick here, so it must not claim one,
     * and the probe's own evidence line has to appear underneath the sentence.
     */
    @Test fun oneButtonExportReportsTheRealDestinationAndTheProbeLine() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val context = activity.applicationContext
        registerReportProvider(context)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "exportDiagnostics")
            val saved = awaitDialog()
            val texts = descendants(saved.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            // Robolectric has no USB volume: the title and the body must both refuse to claim one.
            assertTrue(texts.any { it.contains("No writable USB drive detected") })
            assertTrue(texts.any { it.contains("Download/DiPlay/DiPlay-") })
            // The evidence line comes from the probe, not from the copy.
            assertTrue(texts.any { it.contains("未发现外置存储卷") || it.contains("No external volume") })
            saved.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val viewer = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(descendants(viewer.window!!.decorView).filterIsInstance<TextView>()
                .any { it.isTextSelectable && it.text.contains("Android 9 / API 28") })
            viewer.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** The other branch: a stick that took the bytes must be reported as the place they went. */
    @Test fun aReportOnAStickSaysItWentToTheStickAndNamesTheFile() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val context = activity.applicationContext
        registerReportProvider(context)
        val stick = File(context.cacheDir, "usb-otg-sda1").apply { deleteRecursively(); mkdirs() }
        ReflectionHelpers.getField<MutableSet<String>>(activity, "mountedUsbPaths").add(stick.absolutePath)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "exportDiagnostics")
            val saved = awaitDialog()
            assertEquals("Diagnostic report saved", shadowOf(saved).title.toString())
            val body = descendants(saved.window!!.decorView).filterIsInstance<TextView>()
                .first { it.text.contains("Report was saved to USB drive") }
            val path = body.text.lines().first { it.contains("usb-otg-sda1") }
            assertTrue("the file must exist where the dialog says it is", File(path).isFile)
            assertTrue(File(path).readText().contains("Android 9 / API 28"))
            saved.dismiss()
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

    private fun awaitDialog(): AlertDialog {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (ShadowAlertDialog.getLatestAlertDialog() == null && System.nanoTime() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
