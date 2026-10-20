package com.shilapi.xcertplay.update

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.shilapi.xcertplay.DiPlayActivity

/**
 * The update card where a driver meets it: the settings page's System tab.
 *
 * What is asserted here is what the page shows before anything has happened -- the card, this app's
 * version and the one button that starts it -- and that drawing it fetched, started and wrote
 * nothing. The states after a press belong to [UpdateCardControllerTest], and the press itself is
 * wired by the card's button calling the controller, so pressing it here would be testing the real
 * check against a network this test does not have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class UpdateCardUiTest {

    private fun settingsPage(): DiPlayActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", "settings")
        return Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()
    }

    private fun texts(activity: DiPlayActivity): List<String> {
        val found = mutableListOf<String>()
        fun walk(view: View) {
            if (view is TextView) view.text?.toString()?.let { found += it }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(activity.findViewById(android.R.id.content))
        return found
    }

    private fun buttons(activity: DiPlayActivity): List<String> {
        val found = mutableListOf<String>()
        fun walk(view: View) {
            if (view is Button) found += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(activity.findViewById(android.R.id.content))
        return found
    }

    @Test
    fun theSystemTabDrawsTheUpdateCardWithTheInstalledVersionAndOneButton() {
        val page = settingsPage()

        val texts = texts(page)

        assertTrue("the system page is the one drawn", texts.contains("System settings"))
        assertTrue("the card is on it", texts.contains("Software update"))
        assertTrue(
            "and says what it will not do on its own",
            texts.any { it.startsWith("Checks the official release only when you press") },
        )
        assertTrue("it names the version that is running", texts.any { it.startsWith("Installed: ") })
        assertEquals(
            "one button, and it is the one that starts it",
            listOf("Check for updates"),
            buttons(page).filter { it == "Check for updates" },
        )
    }

    @Test
    fun drawingThePageFetchesNothingAndOpensNothing() {
        val page = settingsPage()

        assertNull("no installer is opened by drawing the page", shadowOf(page).nextStartedActivity)
        val dir = UpdateStorageCleaner(page).updateDir
        assertTrue("and no package is written", !dir.exists() || dir.list()?.isEmpty() != false)
    }
}
