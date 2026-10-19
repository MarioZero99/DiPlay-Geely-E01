package com.shilapi.xcertplay.ui.component

import android.content.Context
import com.shilapi.xcertplay.ui.theme.CockpitThemeManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The drawer has to be wide enough to be worth pulling out on this screen.
 *
 * The E01 is 1920×720 at 160dpi: density 1.0, so the usable width really is 1920dp. A fixed 520dp
 * panel covered 27% of it and the settings inside kept wrapping at desktop widths — the complaint
 * was that the panel could be pulled out further. The width is a share of the screen now, with a
 * floor and a ceiling so that it cannot swallow the stage it slides over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsDrawerWidthTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun drawer() = SettingsDrawerView(
        context,
        CockpitThemeManager.ZEEKR_WHITE_PALETTE,
        {}, {}, {}, {}, null, {},
    )

    @Test
    @Config(sdk = [28], qualifiers = "w1920dp-h720dp")
    fun `the panel takes close to half of a 1920dp cockpit`() {
        val width = drawer().computePanelWidthDp()

        assertTrue("1920dp 宽屏上抽屉应是 680~800dp，实际 $width", width in 680..800)
        assertTrue("仍要给左侧主界面留出空间", width < 1920 / 2)
    }

    @Test
    @Config(sdk = [28], qualifiers = "w1920dp-h720dp")
    fun `the panel is wider than it used to be`() {
        assertTrue("旧的 520dp 是这次要修的那个数", drawer().computePanelWidthDp() > 520)
    }

    /** A narrow unit keeps a drawer that fits inside it, rather than one the screen clips. */
    @Test
    @Config(sdk = [28], qualifiers = "w450dp-h800dp")
    fun `a narrow head unit gets a panel that fits on the screen`() {
        val width = drawer().computePanelWidthDp()

        assertTrue("450dp 屏上不能开 800dp 的抽屉，实际 $width", width <= 450 - 40 + 1)
        assertTrue("再窄也要能放下一列设置，实际 $width", width >= 320)
    }
}
