package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.component.ConfirmSheetView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Leaving the app is a press, a question and a press — on every page, not only on the cockpit.
 *
 * The header's close control sits a thumb's width from "Back" on the settings, display, system and
 * about pages, and it used to end the session and the process on the first touch. The cockpit's own
 * exit control has asked first since it was drawn; these pin the same answer to the header.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class ExitConfirmUiTest {

    private fun activity(page: String): DiPlayActivity =
        Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", page)).setup().get()

    @Test
    fun `the close control on a settings page asks before it ends the app`() {
        val activity = activity("settings")
        closeButton(activity).performClick()

        assertFalse("the first press ended the app", activity.isFinishing)
        assertTrue("the question is drawn", sheets(activity).isNotEmpty())
    }

    @Test
    fun `the question can be answered no`() {
        val activity = activity("settings")
        closeButton(activity).performClick()

        val cancel = sheets(activity).first().let { sheet ->
            descendants(sheet).filterIsInstance<Button>()
                .first { it.text?.toString() == activity.getString(R.string.cancel) }
        }
        cancel.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        assertTrue("the sheet is gone", sheets(activity).isEmpty())
        assertFalse("and the app is still here", activity.isFinishing)
    }

    private fun closeButton(activity: DiPlayActivity): Button =
        descendants(activity.findViewById(android.R.id.content))
            .filterIsInstance<Button>()
            .first { it.text?.toString() == activity.getString(R.string.close_app) }

    private fun sheets(activity: DiPlayActivity): List<ConfirmSheetView> =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<ConfirmSheetView>().toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
