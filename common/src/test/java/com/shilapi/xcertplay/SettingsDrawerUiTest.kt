package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Where the three-finger swipe lands, and what back does there.
 *
 * The gesture used to open the standalone settings page — a second settings surface, drawn with its
 * own headings, that the cockpit rework had already replaced with the drawer. These tests pin the
 * destination: the home page, with the drawer open, and back closing the drawer rather than the
 * page.
 *
 * Everything is read through [visibleTexts]: a closed drawer is still in the tree as `GONE`, so a
 * walk that ignored visibility would find its labels either way and assert nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class SettingsDrawerUiTest {

    /** Lets the 180ms slide-out finish; the panel is only `GONE` once its animator ends. */
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

    private fun activity(openDrawer: Boolean, page: String = "home"): DiPlayActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", page)
            .putExtra(DiPlayActivity.EXTRA_OPEN_SETTINGS_DRAWER, openDrawer)
        return Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()
    }

    /** Text of everything on screen, skipping subtrees the window is not drawing. */
    private fun visibleTexts(activity: DiPlayActivity): List<String> {
        val found = mutableListOf<String>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is TextView) view.text?.toString()?.takeIf { it.isNotBlank() }?.let { found += it }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(activity.findViewById(android.R.id.content))
        return found
    }

    private fun buttons(activity: DiPlayActivity): List<String> {
        val found = mutableListOf<String>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is Button) view.text?.toString()?.let { found += it }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(activity.findViewById(android.R.id.content))
        return found
    }

    /** The drawer's own words: its header, and the first section in it. */
    private val drawerWords = listOf("System settings", "Appearance")

    @Test
    fun theSwipeOpensTheDrawerOnTheCockpit() {
        val shown = visibleTexts(activity(openDrawer = true))
        drawerWords.forEach { assertTrue("the drawer is open: '$it' is missing", it in shown) }
    }

    /** And not the page the drawer replaced, which is what the gesture used to open. */
    @Test
    fun theSwipeDoesNotOpenTheOldSettingsPage() {
        val shown = visibleTexts(activity(openDrawer = true))
        assertFalse("the standalone settings page is not what the gesture opens", "Your drive, your way." in shown)
    }

    @Test
    fun aPlainHomeIntentLeavesTheDrawerClosed() {
        val shown = visibleTexts(activity(openDrawer = false))
        drawerWords.forEach { assertFalse("'$it' is not on the home page", it in shown) }
        assertTrue("the cockpit is what is drawn", "Connect phone" in buttons(activity(openDrawer = false)))
    }

    /**
     * Back is the drawer's, not the page's: it closes the panel, and the driver stays on the cockpit
     * one press away from the CarPlay picture they swiped away from.
     */
    @Test
    fun backClosesTheDrawerWithoutLeavingThePage() {
        val activity = activity(openDrawer = true)
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        val shown = visibleTexts(activity)
        drawerWords.forEach { assertFalse("'$it' was closed with the drawer", it in shown) }
        assertTrue("the driver is still on the cockpit", "Connect phone" in buttons(activity))
        assertFalse("back did not leave the app", activity.isFinishing)
    }

    /** A second back press, with the drawer already gone, is the page's: it leaves the app. */
    @Test
    fun aSecondBackLeavesTheApp() {
        val activity = activity(openDrawer = true)
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue("the second back left the cockpit", activity.isFinishing)
    }
}
