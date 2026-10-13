package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The wireless page is one long scroll and most of its controls repaint by rebuilding it, so a rebuild
 * that started the page at the top moved the card the driver had just touched off the screen.
 *
 * It used to be the settings page. That page is a hub of four entries now, short enough that there is
 * nothing to scroll and the offsets below would prove nothing, so the check moved to a page that is
 * still long: the same code either way, since the offset is kept per page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class SettingsScrollUiTest {
    @Test fun repaintingAPageComesBackToWhereTheDriverWas() {
        val controller = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent().putExtra("page", "wireless"),
        ).setup()
        val activity = controller.get()
        try {
            val first = scrollViewIn(activity)
            layout(first)
            val offset = deepestOffset(first)
            assertTrue(
                "the page has to be long enough to scroll, or this proves nothing: " +
                    "content ${first.getChildAt(0).height}px in a ${HEIGHT}px viewport",
                offset > 0,
            )
            first.scrollTo(0, offset)
            assertEquals(offset, first.scrollY)

            activity.onConfigurationChanged(Configuration(activity.resources.configuration))
            shadowOf(Looper.getMainLooper()).idle()

            val repainted = scrollViewIn(activity)
            assertNotSame("the page has to have been rebuilt for this to prove anything", first, repainted)
            layout(repainted)
            repainted.viewTreeObserver.dispatchOnGlobalLayout()
            assertEquals("a repaint threw the page back to the header", offset, repainted.scrollY)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun anotherPageStillStartsAtTheTop() {
        val controller = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent().putExtra("page", "wireless"),
        ).setup()
        val activity = controller.get()
        try {
            val first = scrollViewIn(activity)
            layout(first)
            val offset = deepestOffset(first)
            assertTrue("the page has to be long enough to scroll", offset > 0)
            first.scrollTo(0, offset)

            ReflectionHelpers.callInstanceMethod<Unit>(
                activity, "onNewIntent",
                ReflectionHelpers.ClassParameter.from(Intent::class.java, Intent().putExtra("page", "about")),
            )
            shadowOf(Looper.getMainLooper()).idle()

            val next = scrollViewIn(activity)
            assertNotSame(first, next)
            layout(next)
            next.viewTreeObserver.dispatchOnGlobalLayout()
            assertEquals("a different page carried the old offset over", 0, next.scrollY)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** How far this page can be scrolled: the content that does not fit in the viewport. */
    private fun deepestOffset(scroll: ScrollView): Int =
        (scroll.getChildAt(0).height - HEIGHT).coerceAtLeast(0)

    private fun layout(scroll: ScrollView) {
        val width = View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        scroll.measure(width, height)
        scroll.layout(0, 0, WIDTH, HEIGHT)
    }

    private fun scrollViewIn(activity: Activity): ScrollView =
        descendants(activity.window.decorView).filterIsInstance<ScrollView>().first()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private companion object {
        // The head unit's own screen. The viewport has to be smaller than a page or every page measures
        // to exactly the viewport height — that is what fillViewport does — and this would prove nothing.
        const val WIDTH = 1920
        const val HEIGHT = 720
    }
}
