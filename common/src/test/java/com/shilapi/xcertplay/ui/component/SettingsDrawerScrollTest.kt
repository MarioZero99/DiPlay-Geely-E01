package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.ui.theme.CockpitThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Where the drawer lands when it is opened on a section, and where it comes back to after a redraw.
 *
 * Both used to be the top of the list. `scrollToText` and `scrollToOffset` posted their work to the
 * main queue, which runs it on the next loop — before the vsync that measures the panel. A
 * `ScrollView` clamps a scroll against its child's height, and that height was still zero, so the
 * offset was thrown away. Opening on "audio routing" from the quick card landed on "Appearance", and
 * changing anything in the lower half of the list came back at the top.
 *
 * These drive the view the way the Activity does — built, opened, asked to scroll, and only then
 * laid out — because the order is the whole bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class SettingsDrawerScrollTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val palette = CockpitThemeManager.ZEEKR_WHITE_PALETTE

    @Test
    fun `opening on a section scrolls to it once the panel has been laid out`() {
        val drawer = drawer()
        drawer.open(animate = false)
        drawer.scrollToText(AUDIO)

        // Nothing has been measured yet: this is the moment the posted runnable used to run in.
        layout(drawer)
        val scroll = scrollIn(drawer)
        scroll.viewTreeObserver.dispatchOnGlobalLayout()

        assertTrue("the drawer has to be long enough to scroll, or this proves nothing", scroll.scrollY > 0)
        assertEquals("the section is not at the top of the panel", dp(12), heading(drawer, AUDIO).top - scroll.scrollY)
    }

    @Test
    fun `a redraw puts the drawer back at the offset it was left at`() {
        val before = drawer()
        before.open(animate = false)
        layout(before)
        scrollIn(before).scrollTo(0, LEFT_AT)
        assertEquals(LEFT_AT, before.scrollOffset())

        // The page rebuilds: a new drawer, opened without a slide, asked for the old offset.
        val rebuilt = drawer()
        assertNotSame(before, rebuilt)
        rebuilt.open(animate = false)
        rebuilt.scrollToOffset(before.scrollOffset())

        layout(rebuilt)
        val scroll = scrollIn(rebuilt)
        scroll.viewTreeObserver.dispatchOnGlobalLayout()
        assertEquals(
            "a setting changed in the lower half threw the driver back to the top",
            LEFT_AT,
            scroll.scrollY,
        )
    }

    /** A drawer that is already on screen scrolls at once: there may be no further layout to wait for. */
    @Test
    fun `a second request on an open drawer does not wait for a layout`() {
        val drawer = drawer()
        drawer.open(animate = false)
        layout(drawer)
        drawer.scrollToText(DISPLAY)

        assertEquals(heading(drawer, DISPLAY).top - dp(12), scrollIn(drawer).scrollY)
    }

    private fun drawer(): SettingsDrawerView {
        fun section(parent: LinearLayout, text: String) {
            parent.addView(
                TextView(context).apply {
                    this.text = text
                    layoutParams = LinearLayout.LayoutParams(-1, SECTION_HEIGHT)
                },
            )
        }
        return SettingsDrawerView(
            context,
            palette,
            buildThemeSection = { section(it, APPEARANCE) },
            buildConnectionSection = { section(it, AUDIO) },
            buildDisplaySection = { section(it, DISPLAY) },
            buildSystemSection = { section(it, SYSTEM) },
            buildExperimentalSection = null,
            onClose = {},
        )
    }

    /** The heading this test asks for, found the same way the drawer finds it. */
    private fun heading(drawer: View, prefix: String): TextView =
        descendants(drawer).filterIsInstance<TextView>().first { it.text?.startsWith(prefix) == true }

    private fun layout(drawer: View) {
        drawer.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        drawer.layout(0, 0, WIDTH, HEIGHT)
    }

    private fun scrollIn(drawer: View): ScrollView = descendants(drawer).filterIsInstance<ScrollView>().first()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val APPEARANCE = "Appearance"
        const val AUDIO = "Audio routing"
        const val DISPLAY = "Display"
        const val SYSTEM = "System"

        /** Each section is taller than the viewport, so every one of them can be scrolled to. */
        const val SECTION_HEIGHT = 400

        const val WIDTH = 800
        const val HEIGHT = 600

        /** Somewhere in the middle of the list, short of the bottom so it is not clamped. */
        const val LEFT_AT = 500
    }
}
