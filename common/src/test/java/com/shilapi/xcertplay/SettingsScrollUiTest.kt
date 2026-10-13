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
 * The settings page is one long scroll and most of its controls repaint by rebuilding it, so a rebuild
 * that started the page at the top moved the card the driver had just touched off the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class SettingsScrollUiTest {
    @Test fun repaintingAPageComesBackToWhereTheDriverWas() {
        val controller = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent().putExtra("page", "settings"),
        ).setup()
        val activity = controller.get()
        try {
            val first = scrollViewIn(activity)
            layout(first)
            assertTrue(
                "the page has to be long enough to scroll, or this proves nothing",
                first.getChildAt(0).height > OFFSET + HEIGHT,
            )
            first.scrollTo(0, OFFSET)
            assertEquals(OFFSET, first.scrollY)

            activity.onConfigurationChanged(Configuration(activity.resources.configuration))
            shadowOf(Looper.getMainLooper()).idle()

            val repainted = scrollViewIn(activity)
            assertNotSame("the page has to have been rebuilt for this to prove anything", first, repainted)
            layout(repainted)
            repainted.viewTreeObserver.dispatchOnGlobalLayout()
            assertEquals("a repaint threw the page back to the header", OFFSET, repainted.scrollY)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun anotherPageStillStartsAtTheTop() {
        val controller = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent().putExtra("page", "settings"),
        ).setup()
        val activity = controller.get()
        try {
            val first = scrollViewIn(activity)
            layout(first)
            first.scrollTo(0, OFFSET)

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
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val OFFSET = 400
    }
}
