// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * joined network are read by the Activity once per redraw and handed in, and every tap goes back
 * out to the existing connection-setting path rather than to a second copy of it.
 */
class QuickWidgetsView(
    context: Context,
    private val palette: ThemePalette,
    mode: WirelessHotspotMode,
    hotspotSsid: String,
    joinedWifi: String?,
    audioChannelLabel: String,
    onModeSwitch: (WirelessHotspotMode) -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onEditHotspotPass: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onEditWifiPass: () -> Unit,
    onAudioSelect: () -> Unit,
    onOpenDrawer: () -> Unit,
) : LinearLayout(context) {

    init {
        orientation = VERTICAL

        addView(wirelessCard(mode, hotspotSsid, joinedWifi, onModeSwitch, onOpenHotspotSettings, onEditHotspotPass, onOpenWifiSettings, onEditWifiPass), LayoutParams(-1, 0, 1.15f))
        addView(View(context).apply { layoutParams = LayoutParams(1, dp(14)) })
        addView(audioCard(audioChannelLabel, onAudioSelect), LayoutParams(-1, 0, 1.0f))
        addView(View(context).apply { layoutParams = LayoutParams(1, dp(14)) })
        addView(drawerCard(onOpenDrawer), LayoutParams(-1, dp(64)))
    }

    private fun wirelessCard(
        mode: WirelessHotspotMode,
        hotspotSsid: String,
        joinedWifi: String?,
        onModeSwitch: (WirelessHotspotMode) -> Unit,
        onOpenHotspotSettings: () -> Unit,
        onEditHotspotPass: () -> Unit,
        onOpenWifiSettings: () -> Unit,
        onEditWifiPass: () -> Unit,
    ): View = column().apply {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        background = cardBg()
        setPadding(dp(18), dp(16), dp(18), dp(16))

        addView(TextView(context).apply {
            text = context.getString(R.string.wireless_carplay)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.textMain)
        })

        // The two quick routes, as a two-segment拨块. A saved local-only mode is neither of these,
        // so neither segment lights — the card then offers the way to the other two rather than
        // claiming one of them is in use.
        val isHotspot = mode == WirelessHotspotMode.MANUAL
        val segment = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply {
                setColor(palette.cardInner)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val tabHotspot = segmentTab(context.getString(R.string.built_in_car_hotspot), isHotspot) {
            onModeSwitch(WirelessHotspotMode.MANUAL)
        }
        val tabWifi = segmentTab(context.getString(R.string.same_wifi_network), !isHotspot && mode == WirelessHotspotMode.EXISTING_WIFI) {
            onModeSwitch(WirelessHotspotMode.EXISTING_WIFI)
        }
        segment.addView(tabHotspot, LayoutParams(0, dp(44), 1f))
        segment.addView(tabWifi, LayoutParams(0, dp(44), 1f))
        addView(segment, LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val hotspotRow = row().apply {
            addView(smallButton(context.getString(R.string.open_car_hotspot_settings), onOpenHotspotSettings), LayoutParams(0, dp(46), 1f))
            addView(
                smallButton(context.getString(R.string.edit_saved_hotspot_prefix) + hotspotSsid, onEditHotspotPass),
                LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) },
            )
            visibility = if (isHotspot) View.VISIBLE else View.GONE
        }
        addView(hotspotRow, LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val wifiRow = row().apply {
            addView(smallButton(context.getString(R.string.open_car_wi_fi_settings), onOpenWifiSettings), LayoutParams(0, dp(46), 1f))
            addView(
                smallButton(joinedWifi ?: context.getString(R.string.existing_wifi_not_joined), onEditWifiPass),
                LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) },
            )
            visibility = if (isHotspot) View.GONE else View.VISIBLE
        }
        addView(wifiRow, LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun audioCard(audioChannelLabel: String, onAudioSelect: () -> Unit): View = column().apply {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        background = cardBg()
        setPadding(dp(18), dp(16), dp(18), dp(16))

        val header = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.audio_routing)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.textMain)
        }, LayoutParams(0, -2, 1f))
        header.addView(TextView(context).apply {
            // The channel actually saved, read from storage — never a stand-in value.
            text = audioChannelLabel
            textSize = 12f
            setTextColor(palette.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(header)

        addView(
            smallButton(context.getString(R.string.navigation_stream_type), onAudioSelect),
            LayoutParams(-1, dp(46)).apply { topMargin = dp(12) },
        )
    }

    private fun drawerCard(onOpenDrawer: () -> Unit): View = column().apply {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        background = cardBg()
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

    private fun segmentTab(label: String, active: Boolean, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 12.5f
        setTextColor(if (active) palette.textMain else palette.textMuted)
        background = if (active) {
            GradientDrawable().apply {
                setColor(palette.cardBg)
                cornerRadius = dp(6).toFloat()
            }
        } else null
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(palette.textMain)
        background = GradientDrawable().apply {
            setColor(palette.cardInner)
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), palette.cardBorder)
        }
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
