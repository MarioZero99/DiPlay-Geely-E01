// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.theme.ThemePalette

/**
 * The 82dp rail down one edge of the home page: home, audio, settings, the car's own launcher, exit.
 *
 * Five controls and nothing else, because that is all the home page needs from the driver at a
 * stoplight. The launcher control and the exit control are the two ways off the page, and they are
 * deliberately not the same thing: the launcher leaves DiPlay on screen but keeps it running — the
 * session, the socket and the reconnect all survive — while exit ends the app. Every touch target is
 * 56dp tall whatever the icon measures, and the icon is centred
 * in its own frame rather than trusting the drawable's padding. The rail mirrors with
 * right-hand drive by being placed on the other side of the content, which is the Activity's
 * decision — this view only ever draws itself top to bottom.
 */
class SlimDockView(
    context: Context,
    private val palette: ThemePalette,
    private val onHomeClick: () -> Unit,
    private val onAudioClick: () -> Unit,
    private val onSettingsClick: () -> Unit,
    private val onLauncherClick: () -> Unit,
    private val onExitClick: () -> Unit,
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(palette.dockBg)
        setPadding(0, dp(18), 0, dp(18))

        // Top group: where the drive is, and what it sounds like.
        addView(iconButton(R.drawable.ic_carplay, null, onHomeClick, selected = true))
        addView(View(context).apply { layoutParams = LayoutParams(1, dp(16)) })
        addView(iconButton(R.drawable.ic_dp_display, palette.textMuted, onAudioClick))

        // The spacer takes the slack, so the two groups sit at the two ends of the rail.
        addView(View(context), LayoutParams(1, 0, 1f))

        // Bottom group: settings, then the two ways off the page — out to the car's launcher with
        // DiPlay still running, and out of the app. Exit is last and red, and its confirmation is
        // the caller's — see DiPlayActivity's onExitClick.
        addView(iconButton(R.drawable.ic_dp_about, palette.textMuted, onSettingsClick))
        addView(View(context).apply { layoutParams = LayoutParams(1, dp(16)) })
        addView(iconButton(R.drawable.ic_dp_launcher, palette.textMuted, onLauncherClick))
        addView(View(context).apply { layoutParams = LayoutParams(1, dp(16)) })
        addView(iconButton(R.drawable.ic_dp_exit, EXIT_TINT, onExitClick))
    }

    private fun iconButton(resId: Int, tint: Int?, onClick: () -> Unit, selected: Boolean = false): View {
        // 56dp of touch height on a 26dp icon: the target is the driver's, the glyph is the eye's.
        val container = FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(82), dp(56))
            isClickable = true
            isFocusable = true
            contentDescription = null
        }
        if (selected) {
            // The one mark of where the driver is: a 3dp bar down the leading edge.
            container.addView(
                View(context).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(3), dp(22), Gravity.START or Gravity.CENTER_VERTICAL)
                    background = GradientDrawable().apply {
                        setColor(palette.accent)
                        cornerRadius = dp(2).toFloat()
                    }
                },
            )
        }
        container.addView(
            ImageView(context).apply {
                val drawable = AppCompatResources.getDrawable(context, resId)?.mutate()?.let {
                    if (tint == null) {
                        // A photographic mark is left as it is: tinting one paints a block of that
                        // colour, not a tinted icon.
                        it
                    } else {
                        DrawableCompat.wrap(it).apply { DrawableCompat.setTint(this, tint) }
                    }
                }
                setImageDrawable(drawable)
                layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
            },
        )
        container.setOnClickListener { onClick() }
        return container
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** The one colour here that is not the palette's: exit is the one control that must not blend in. */
        const val EXIT_TINT = 0xFFEF4444.toInt()
    }
}
