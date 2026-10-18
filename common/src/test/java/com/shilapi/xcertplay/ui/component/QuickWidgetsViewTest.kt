package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.WirelessHotspotMode
import com.shilapi.xcertplay.ui.theme.CockpitThemeManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The quick column shows the row that belongs to the mode it is in, and only that one.
 *
 * The Wi-Fi row used to be shown for anything that was not the car hotspot — `if (isHotspot) GONE
 * else VISIBLE` — so a mode that joins no network was offered the car's Wi-Fi settings and a
 * "not on any Wi-Fi yet" label under two segments that were both dark. Each row asks about its own
 * segment now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class QuickWidgetsViewTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val palette = CockpitThemeManager.ZEEKR_WHITE_PALETTE

    @Test
    fun `the car hotspot mode offers the hotspot row and not the Wi-Fi one`() {
        val shown = visibleButtons(WirelessHotspotMode.MANUAL)
        assertTrue(shown.contains(hotspotSettings()))
        assertFalse(shown.contains(wifiSettings()))
    }

    @Test
    fun `the existing network mode offers the Wi-Fi row and not the hotspot one`() {
        val shown = visibleButtons(WirelessHotspotMode.EXISTING_WIFI)
        assertTrue(shown.contains(wifiSettings()))
        assertFalse(shown.contains(hotspotSettings()))
    }

    /** Neither segment is lit, so neither row may be: there is nothing here for it to be about. */
    @Test
    fun `the local-only mode offers neither row`() {
        val shown = visibleButtons(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)
        assertFalse(shown.contains(hotspotSettings()))
        assertFalse(shown.contains(wifiSettings()))
    }

    private fun visibleButtons(mode: WirelessHotspotMode): List<String> {
        val view = QuickWidgetsView(
            context,
            palette,
            mode,
            "Geely-5G",
            null,
            "Stream 3",
            onModeSwitch = {},
            onOpenHotspotSettings = {},
            onEditHotspotPass = {},
            onOpenWifiSettings = {},
            onEditWifiPass = {},
            onAudioSelect = {},
            onOpenDrawer = {},
        )
        view.updateState(mode, "Geely-5G", null, "Stream 3")
        val found = mutableListOf<String>()
        collectButtons(view, found)
        return found
    }

    /** Skips whole subtrees the card is not drawing: a row is `GONE`, its buttons are not. */
    private fun collectButtons(view: View, found: MutableList<String>) {
        if (view.visibility != View.VISIBLE) return
        if (view is Button) view.text?.toString()?.let { found += it }
        if (view is ViewGroup) for (index in 0 until view.childCount) collectButtons(view.getChildAt(index), found)
    }

    private fun hotspotSettings() = context.getString(R.string.open_car_hotspot_settings)
    private fun wifiSettings() = context.getString(R.string.open_car_wi_fi_settings)
}
