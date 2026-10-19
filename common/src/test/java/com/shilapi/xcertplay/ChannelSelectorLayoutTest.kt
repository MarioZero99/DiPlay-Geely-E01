package com.shilapi.xcertplay

import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The 21 stream types have to fit inside the card that holds them.
 *
 * They were sized `(screenWidthPixels - 80) / 7` — about 262 px each on this 1920 px head unit, so
 * seven cells plus their margins came to roughly 1890 px inside a card a fraction of that wide.
 * Everything past the second column was clipped and untappable, which is what "the channels are
 * squeezed and cannot be pressed" looked like from the driver's seat. An equal share of the parent
 * cannot overflow the parent, and that is the whole assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class ChannelSelectorLayoutTest {

    @Test
    fun `the selector is 21 cells of equal share, not screen-pixel widths`() {
        // The selector is built by the settings page; the default page is the cockpit, where this
        // list is empty.
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", "settings")
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()

        val cells = activity.channelButtonsForTest()

        assertEquals("21 stream types", 21, cells.size)
        cells.forEachIndexed { index, cell ->
            val params = cell.layoutParams as LinearLayout.LayoutParams
            assertEquals("cell $index is a zero-width share", 0, params.width)
            assertEquals("cell $index takes an equal share", 1f, params.weight, 0.01f)
        }
    }

    /** Three rows of seven, in a column — the shape that lets the shares divide the card's width. */
    @Test
    fun `the cells are laid out in three rows of seven`() {
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", "settings")
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()

        val column = activity.channelButtonsForTest().first().parent as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, column.orientation)
        assertEquals("seven to a row", 7, column.childCount)

        val rows = (column.parent as LinearLayout)
        assertEquals("three rows", 3, rows.childCount)
    }

    /** Every cell is pressable, which is the half of the complaint the widths caused. */
    @Test
    fun `every cell is enabled and carries its stream type`() {
        val intent = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
            .putExtra("page", "settings")
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()

        val cells: List<Button> = activity.channelButtonsForTest()
        assertEquals((0..20).map { it.toString() }, cells.map { it.text?.toString() })
        cells.forEach { cell ->
            assertEquals("cell ${cell.text} is pressable", true, cell.isEnabled)
        }
    }
}
