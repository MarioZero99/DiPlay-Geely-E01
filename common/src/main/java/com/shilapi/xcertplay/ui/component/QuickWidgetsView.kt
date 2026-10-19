// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.WirelessHotspotMode
import com.shilapi.xcertplay.ui.theme.ThemePalette

/**
 * The 42% column beside the stage: how the phone reaches the car, what the navigation audio rides,
 * and the way to everything else.
 *
 * Three cards, weighted 1.15 : 1.0 : fixed, because the wireless card is the one with the拨块 and
 * two live rows under it. Nothing here holds state of its own: the mode, the hotspot name and the
 * joined network are read by the Activity once per tick and handed in through [updateState], which
 * repaints the labels and the two rows in place — these are the only controls on the page whose
 * inputs can change without a tap, when the car joins or leaves a network on its own.
 */
class QuickWidgetsView(
    context: Context,
    private val palette: ThemePalette,
    mode: WirelessHotspotMode,
    hotspotSsid: String,
    joinedWifi: String?,
    audioChannelLabel: String,
    private val onModeSwitch: (WirelessHotspotMode) -> Unit,
    private val onOpenHotspotSettings: () -> Unit,
    private val onEditHotspotPass: () -> Unit,
    private val onOpenWifiSettings: () -> Unit,
    private val onEditWifiPass: () -> Unit,
    private val onAudioSelect: () -> Unit,
    private val onOpenDrawer: () -> Unit,
    /**
     * Whether the same-network route is in this package. It is experimental and only a local build
     * carries it; without it the card is the car hotspot and nothing else, rather than two segments
     * where one can only refuse.
     */
    private val sameWifiOffered: Boolean = true,
) : LinearLayout(context) {

    private val hotspotTab: Button
    private val wifiTab: Button
    private val hotspotRow: LinearLayout
    private val wifiRow: LinearLayout
    private val hotspotEdit: Button
    private val wifiEdit: Button
    private val audioValue: TextView

    /**
     * The mode the segment and the two rows are drawn for. The taps read this rather than the mode
     * the card was built with: after a repaint the built-in value is a redraw behind, and pressing
     * the segment that is already in use must stay a press that changes nothing.
     */
    private var currentMode: WirelessHotspotMode = mode

    /** What [updateState] was last given, so a tick that changes nothing touches no view. */
    private var shownState: WidgetState? = null

    private data class WidgetState(
        val mode: WirelessHotspotMode,
        val hotspotSsid: String,
        val joinedWifi: String?,
        val audioChannelLabel: String,
    )

    init {
        orientation = VERTICAL

        val wireless = column().apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        wireless.addView(TextView(context).apply {
            text = context.getString(R.string.wireless_carplay)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.textMain)
        })

        // The two quick routes, as a two-segment拨块. A saved local-only mode is neither of these,
        // so neither segment lights and neither row is shown: the card then offers the way to the
        // two it can drive rather than claiming one of them is in use. In the official package the
        // same-network route is not in the build, so its segment and row are not added at all — the
        // car hotspot is the whole card.
        val segment = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply {
                setColor(palette.cardInner)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        hotspotTab = tab(context.getString(R.string.built_in_car_hotspot)) {
            // Pressing the mode that is already in use is not a change: it wrote storage, rebuilt the
            // page and — for the car hotspot — put the page back into setup mode, all for a press
            // that moved nothing. Only a different segment switches.
            if (currentMode != WirelessHotspotMode.MANUAL) onModeSwitch(WirelessHotspotMode.MANUAL)
        }
        wifiTab = tab(context.getString(R.string.same_wifi_network)) {
            if (currentMode != WirelessHotspotMode.EXISTING_WIFI) onModeSwitch(WirelessHotspotMode.EXISTING_WIFI)
        }
        segment.addView(hotspotTab, LayoutParams(0, dp(44), 1f))
        if (sameWifiOffered) segment.addView(wifiTab, LayoutParams(0, dp(44), 1f))
        wireless.addView(segment, LayoutParams(-1, -2).apply { topMargin = dp(10) })

        hotspotEdit = smallButton("") { onEditHotspotPass() }
        hotspotRow = row().apply {
            addView(
                smallButton(context.getString(R.string.open_car_hotspot_settings), onOpenHotspotSettings),
                LayoutParams(0, dp(46), 1f),
            )
            addView(hotspotEdit, LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
        }
        wireless.addView(hotspotRow, LayoutParams(-1, -2).apply { topMargin = dp(10) })

        wifiEdit = smallButton("") { onEditWifiPass() }
        wifiRow = row().apply {
            addView(
                smallButton(context.getString(R.string.open_car_wi_fi_settings), onOpenWifiSettings),
                LayoutParams(0, dp(46), 1f),
            )
            addView(wifiEdit, LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
        }
        if (sameWifiOffered) wireless.addView(wifiRow, LayoutParams(-1, -2).apply { topMargin = dp(10) })

        addView(wireless, LayoutParams(-1, 0, 1.15f))
        addView(View(context).apply { layoutParams = LayoutParams(1, dp(14)) })

        val audio = column().apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val audioHeader = row().apply { gravity = Gravity.CENTER_VERTICAL }
        audioHeader.addView(
            TextView(context).apply {
                text = context.getString(R.string.audio_routing)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(palette.textMain)
            },
            LayoutParams(0, -2, 1f),
        )
        // The channel actually saved, read from storage — never a stand-in value.
        audioValue = TextView(context).apply {
            textSize = 12f
            setTextColor(palette.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        audioHeader.addView(audioValue)
        audio.addView(audioHeader)
        audio.addView(
            smallButton(context.getString(R.string.navigation_stream_type), onAudioSelect),
            LayoutParams(-1, dp(46)).apply { topMargin = dp(12) },
        )
        addView(audio, LayoutParams(-1, 0, 1.0f))

        addView(View(context).apply { layoutParams = LayoutParams(1, dp(14)) })
        addView(drawerCard(onOpenDrawer), LayoutParams(-1, dp(64)))

        updateState(mode, hotspotSsid, joinedWifi, audioChannelLabel)
    }

    /**
     * In-place repaint for the once-a-second tick.
     *
     * The car can join or leave a Wi-Fi network on its own, and the saved hotspot name can change in
     * the car's settings while this page is up; until this existed the only path that reached these
     * cards was a full rebuild, so they kept showing the network they were built with. Equal state
     * returns before the first view is touched, which is the case on almost every tick.
     */
    fun updateState(
        mode: WirelessHotspotMode,
        hotspotSsid: String,
        joinedWifi: String?,
        audioChannelLabel: String,
    ) {
        val state = WidgetState(mode, hotspotSsid, joinedWifi, audioChannelLabel)
        if (state == shownState) return
        shownState = state
        currentMode = mode

        val isHotspot = mode == WirelessHotspotMode.MANUAL
        val isExistingWifi = sameWifiOffered && mode == WirelessHotspotMode.EXISTING_WIFI
        paintTab(hotspotTab, isHotspot)
        paintTab(wifiTab, isExistingWifi)

        // Each row belongs to its own segment, so each row asks about its own segment. The Wi-Fi row
        // used to be shown for anything that was not the car hotspot, which put "open the car's
        // Wi-Fi settings" and a joined-network label on the card in a mode that joins nothing.
        hotspotRow.visibility = if (isHotspot) View.VISIBLE else View.GONE
        wifiRow.visibility = if (isExistingWifi) View.VISIBLE else View.GONE
        hotspotEdit.text = context.getString(R.string.edit_saved_hotspot_prefix) + hotspotSsid
        wifiEdit.text = joinedWifi ?: context.getString(R.string.existing_wifi_not_joined)
        audioValue.text = audioChannelLabel
    }

    private fun paintTab(tab: Button, active: Boolean) {
        tab.setTextColor(if (active) palette.textMain else palette.textMuted)
        tab.background = if (active) {
            pressable(palette.cardBg, dp(6).toFloat())
        } else {
            // No fill of its own: the wash is the press, over the segment's own colour.
            pressable(0, dp(6).toFloat(), pressedColor = wash(palette.textMain))
        }
    }

    private fun drawerCard(onOpenDrawer: () -> Unit): View = column().apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(palette.cardBg, dp(14).toFloat(), dp(1), palette.cardBorder)
        setPadding(dp(18), 0, dp(18), 0)
        isClickable = true
        isFocusable = true

        addView(TextView(context).apply {
            text = context.getString(R.string.settings)
            textSize = 13.5f
            setTextColor(palette.textMuted)
        }, LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = context.getString(R.string.open_settings)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.accent)
        })
        setOnClickListener { onOpenDrawer() }
    }

    private fun tab(label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 12.5f
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(palette.textMain)
        background = pressable(palette.cardInner, dp(6).toFloat(), dp(1), palette.cardBorder)
        setOnClickListener { onClick() }
    }

    private fun cardBg() = GradientDrawable().apply {
        setColor(palette.cardBg)
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), palette.cardBorder)
    }

    private fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
