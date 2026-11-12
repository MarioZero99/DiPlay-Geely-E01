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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /** 地点卡点一下就是保存：写到卷根 /DiPlay，不再进入子目录。 */
    @Test fun tappingAStickSavesAtTheMountRoot() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val context = activity.applicationContext
        registerReportProvider(context)
        val stick = File(context.cacheDir, "usb-otg-sda1").apply { deleteRecursively(); mkdirs() }
        File(stick, "readme.txt").writeText("hello")
        File(stick, "Music").mkdirs()
        DiagnosticVolumeProbe.volumeListProvider = {
            arrayOf(DiagnosticExportStoreTest.TestVolume(stick.absolutePath, description = stick.name))
        }
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
            awaitText(activity, stick.name)
            awaitText(activity, stick.absolutePath)
            clickRow(activity, stick.name)
            val body = awaitText(activity, "/DiPlay/DiPlay-")
            val saved = File(stick, "DiPlay").listFiles()!!.single { it.isFile }
            assertEquals(saved.absolutePath, body.text.lines().first { it.contains("usb-otg-sda1") })
            assertTrue(texts(activity).none { it.text == "readme.txt" })
            assertFalse(File(stick, "Music/DiPlay").exists())
            assertNotNull(texts(activity).firstOrNull { it.text == activity.getString(R.string.share) })
        } finally {
            DiagnosticVolumeProbe.volumeListProvider = null
            stick.deleteRecursively()
            controller.pause().stop().destroy()
        }
    }

    @Test fun aLibaumsStickIsListedWithoutOpeningAndSaveTriesOnce() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        registerReportProvider(activity.applicationContext)
        var opens = 0
        UsbMassStorageManager.deviceListProvider = { listOf(DiagnosticExportStoreTest.blankUsbDevice()) }
        UsbMassStorageManager.hasPermissionProvider = { _, _ -> true }
        UsbMassStorageManager.openFileSystemProvider = { opens += 1; null }
        DiagnosticVolumeProbe.volumeListProvider = { emptyArray<Any>() }
        DiagnosticVolumeProbe.volumePathsProvider = { emptyArray() }
        DiagnosticVolumeProbe.procMountsProvider = { "" }
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
            awaitText(activity, "U 盘 (USB 存储)")
            awaitText(activity, activity.getString(R.string.export_usb_direct_detail))
            assertEquals(0, opens)
            clickRow(activity, "U 盘 (USB 存储)")
            awaitText(activity, activity.getString(R.string.export_failed_kept))
            assertEquals(1, opens)
            assertTrue(texts(activity).none { it.text?.contains("readme.txt") == true })
        } finally {
            UsbMassStorageManager.deviceListProvider = null
            UsbMassStorageManager.hasPermissionProvider = null
            UsbMassStorageManager.openFileSystemProvider = null
            UsbMassStorageManager.close()
            DiagnosticVolumeProbe.volumeListProvider = null
            DiagnosticVolumeProbe.volumePathsProvider = null
            DiagnosticVolumeProbe.procMountsProvider = null
            DiagSink.reset()
            controller.pause().stop().destroy()
        }
    }

    /**
     * A save from the place list has no primary button to relabel, so the card itself must say it is
     * saving. "Reading…" is the folder-list sentence; leaving it up makes a started save look ignored.
     */
    @Test fun aTappedStickShowsProgressWhileTheWriteRuns() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        registerReportProvider(activity.applicationContext)
        val gate = CountDownLatch(1)
        UsbMassStorageManager.deviceListProvider = { listOf(DiagnosticExportStoreTest.blankUsbDevice()) }
        UsbMassStorageManager.hasPermissionProvider = { _, _ -> true }
        UsbMassStorageManager.openFileSystemProvider = { gate.await(5, TimeUnit.SECONDS); null }
        DiagnosticVolumeProbe.volumeListProvider = { emptyArray<Any>() }
        DiagnosticVolumeProbe.volumePathsProvider = { emptyArray() }
        DiagnosticVolumeProbe.procMountsProvider = { "" }
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
            awaitText(activity, activity.getString(R.string.export_usb_direct_detail))
            clickRow(activity, "U 盘 (USB 存储)")
            shadowOf(Looper.getMainLooper()).idle()
            val during = texts(activity).mapNotNull { it.text?.toString() }
            assertTrue(during.toString(), during.contains(activity.getString(R.string.saving_report)))
            assertFalse(during.toString(), during.contains(activity.getString(R.string.export_loading)))
            assertFalse(during.contains(activity.getString(R.string.export_usb_direct_detail)))
            gate.countDown()
            awaitText(activity, activity.getString(R.string.export_failed_kept))
        } finally {
            gate.countDown()
            UsbMassStorageManager.deviceListProvider = null
            UsbMassStorageManager.hasPermissionProvider = null
            UsbMassStorageManager.openFileSystemProvider = null
            UsbMassStorageManager.close()
            DiagnosticVolumeProbe.volumeListProvider = null
            DiagnosticVolumeProbe.volumePathsProvider = null
            DiagnosticVolumeProbe.procMountsProvider = null
            DiagSink.reset()
            controller.pause().stop().destroy()
        }
    }

    /** No grant and no UsbDevice used to leave the card on "Reading…" because the callback never ran. */
    @Test fun aStickWithoutPermissionLeavesTheReadingState() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        registerReportProvider(activity.applicationContext)
        UsbMassStorageManager.deviceListProvider = { listOf(DiagnosticExportStoreTest.blankUsbDevice()) }
        UsbMassStorageManager.hasPermissionProvider = { _, _ -> false }
        DiagnosticVolumeProbe.volumeListProvider = { emptyArray<Any>() }
        DiagnosticVolumeProbe.volumePathsProvider = { emptyArray() }
        DiagnosticVolumeProbe.procMountsProvider = { "" }
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
            awaitText(activity, "U 盘 (USB 存储)")
            clickRow(activity, "U 盘 (USB 存储)")
            awaitText(activity, "未获得 USB 设备访问权限")
            val shown = texts(activity).mapNotNull { it.text?.toString() }
            assertFalse(shown.toString(), shown.any { it.contains(activity.getString(R.string.export_loading)) })
            assertNotNull(texts(activity).firstOrNull { it.text == activity.getString(R.string.retry) })
        } finally {
            UsbMassStorageManager.deviceListProvider = null
            UsbMassStorageManager.hasPermissionProvider = null
            UsbMassStorageManager.close()
            DiagnosticVolumeProbe.volumeListProvider = null
            DiagnosticVolumeProbe.volumePathsProvider = null
            DiagnosticVolumeProbe.procMountsProvider = null
            DiagSink.reset()
            controller.pause().stop().destroy()
        }
    }

    /** A stick that cannot be created stays a failure, and the card offers phone storage plus the private copy. */
    @Test fun aFailedStickKeepsTheReportAndOffersPhoneStorage() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        registerReportProvider(activity.applicationContext)
        val blocked = File(activity.cacheDir, "not-a-dir-ui").apply { writeText("x") }
        val stick = File(blocked, "stick")
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
            awaitText(activity, activity.getString(R.string.export_internal_storage))
            ReflectionHelpers.callInstanceMethod<Unit>(
                activity,
                "exportHere",
                ReflectionHelpers.ClassParameter.from(File::class.java, stick),
            )
            awaitText(activity, activity.getString(R.string.export_save_to_phone))
            awaitText(activity, activity.getString(R.string.export_failed_kept))
            assertNotNull(texts(activity).firstOrNull { it.text == activity.getString(R.string.share) })
            assertNotNull(texts(activity).firstOrNull { it.text == activity.getString(R.string.view_diagnostic_report) })
            assertTrue(texts(activity).none { it.text == activity.getString(R.string.diagnostic_report_saved) })
            assertFalse(File(stick, "DiPlay").exists())
            val kept = File(activity.filesDir, "diagnostic-reports").listFiles()!!
            assertTrue(kept.any { it.readText().contains("API ${android.os.Build.VERSION.SDK_INT}") })
        } finally {
            blocked.delete()
            controller.pause().stop().destroy()
        }
    }

    /** 报告只带当前这一场；当天更早的归档留在盘上，但不进报告。 */
    @Test fun theSavedFileHoldsOnlyTheCurrentRun() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        registerReportProvider(activity.applicationContext)
        val logs = File(activity.filesDir, "logs").apply { mkdirs() }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        File(logs, "diplay-$today.log").writeText("HEAD-KEPT-LINE\n")
        File(logs, "diplay.log").writeText("password=hunter2\nNEWER-LINE marker\n")
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
            clickRow(activity, activity.getString(R.string.export_internal_storage))
            awaitText(activity, "/DiPlay/DiPlay-")
            val saved = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "DiPlay",
            ).listFiles()!!.single { it.name.startsWith("DiPlay-") && it.readText().contains("NEWER-LINE marker") }
            val text = saved.readText()
            assertTrue(text.contains("NEWER-LINE marker"))
            assertFalse(text.contains("HEAD-KEPT-LINE"))
            assertFalse(text.contains("--- diplay-$today.log ---"))
            assertTrue(text.contains(DiagnosticRedactor.WITHHELD))
            assertFalse(text.contains("hunter2"))
        } finally {
            logs.deleteRecursively()
            controller.pause().stop().destroy()
        }
    }

    /** diplay.log 刚轮转成 0 字节时退回当天最新一份非空归档。 */
    @Test fun aReportWithAnEmptyCurrentLogFallsBackToTheLatestArchiveToday() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val logs = File(activity.filesDir, "logs").apply { mkdirs() }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        File(logs, "diplay-$today.log").writeText("ARCHIVE-LINE\n")
        File(logs, "diplay.log").writeText("")
        try {
            val report = ReflectionHelpers.callInstanceMethod<String>(
                activity, "buildCompleteReport",
                ReflectionHelpers.ClassParameter.from(Context::class.java, activity.applicationContext),
            )
            assertTrue(report.contains("ARCHIVE-LINE"))
            assertTrue(report.contains("--- diplay-$today.log ---"))
        } finally {
            logs.deleteRecursively()
            controller.pause().stop().destroy()
        }
    }

    /** 跨零点轮转：归档按开场那天命名，今天没有任何归档时仍要取到刚写出的那一份。 */
    @Test fun aReportAfterMidnightRotationStillFindsTheArchive() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val logs = File(activity.filesDir, "logs").apply { mkdirs() }
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000))
        File(logs, "diplay-$yesterday.log").writeText("MIDNIGHT-SESSION-LINE\n")
        File(logs, "diplay.log").writeText("")
        try {
            val report = ReflectionHelpers.callInstanceMethod<String>(
                activity, "buildCompleteReport",
                ReflectionHelpers.ClassParameter.from(Context::class.java, activity.applicationContext),
            )
            assertTrue(report.contains("MIDNIGHT-SESSION-LINE"))
            assertTrue(report.contains("--- diplay-$yesterday.log ---"))
        } finally {
            logs.deleteRecursively()
            controller.pause().stop().destroy()
        }
    }

    /** The header is where a reader looks for "why is it slow"; a timing block that only appears in a
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

    /** 保存后车轮上要能回答"慢在哪"：每个导出阶段都必须有自己的 PerfTrace series。 */
    @Test fun eachExportStageIsTimed() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        registerReportProvider(activity.applicationContext)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        try {
            // exportHere 没有卡片就直接返回，所以先打开地点卡。
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showExportBrowser")
            awaitText(activity, activity.getString(R.string.export_internal_storage))
            ReflectionHelpers.callInstanceMethod<Unit>(
                activity, "exportHere",
                ReflectionHelpers.ClassParameter.from(File::class.java, downloads),
            )
            awaitText(activity, "/DiPlay/DiPlay-")
            val summary = PerfTrace.summary()
            for (name in listOf("export/report", "export/redact", "export/folder-write", "export/backup")) {
                assertTrue(summary, summary.contains(name))
            }
        } finally {
            controller.pause().stop().destroy()
        }
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
