// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/**
 * The bar the three-finger swipe opens over a live CarPlay picture.
 *
 * Four controls and nothing else — end the session, pick the navigation stream, flip day/night,
 * leave the app — because everything a driver needs mid-projection has to be reachable without
 * leaving the picture. It replaces a jump to [com.shilapi.xcertplay.DiPlayActivity] and its 520dp
 * settings drawer: that route unmounted the video surface for a settings tree the driver was not
 * asking for, and on this head unit the drawer plus a running decoder is the memory profile the
 * cockpit rework exists to avoid.
 *
 * One view, built once and kept `GONE` with the page: showing it is a visibility change, not a
 * build. The channel strip is the only thing created on demand, and only the first time it is
 * opened. A press anywhere outside the bar dismisses it, as does [AUTO_HIDE_MILLIS] of no presses —
 * or [CHANNEL_WINDOW_MILLIS] while the channel strip is open, which is longer because a choice takes
 * longer but still bounded, since the bar covers the picture while it is up.
 *
 * Colours are the host page's own dark set rather than a theme palette: this is drawn on top of
 * video, and a light chip on a bright map is the one thing that has to stay readable.
 */
class QuickHudView(
    context: Context,
    night: Boolean,
    audioStream: Int,
    private val onDisconnect: () -> Unit,
    private val onAudioSelected: (Int) -> Unit,
    private val onToggleNight: () -> Unit,
    private val onExit: () -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    private val disconnectButton: TextView
    private val audioButton: TextView
    private val nightButton: TextView
    private val exitButton: TextView
    private val channelStrip: HorizontalScrollView
    private val channelRow: LinearLayout
    private val channels = mutableListOf<TextView>()

    private var isNight = night
    private var audioStream = audioStream
    private var exitArmed = false

    private val disarmExit = Runnable {
        exitArmed = false
        paintExit()
    }
    private val autoHide = Runnable { onDismiss() }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        // The scrim is the dismiss: a press outside the bar answers "not now".
        setOnClickListener { onDismiss() }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
        }

        // Built before the bar so the audio control can capture them; the strip itself stays empty
        // until it is first opened.
        channelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(BAR_BG, 12)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true
        }
        channelStrip = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(channelRow)
        }

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(BAR_BG, 14)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
        }

        disconnectButton = action(context.getString(R.string.disconnect)) {
            scheduleAutoHide()
            onDisconnect()
        }
        bar.addView(disconnectButton, barParams(first = true))

        audioButton = action(context.getString(R.string.audio_routing)) {
            if (channelStrip.visibility == View.VISIBLE) {
                channelStrip.visibility = View.GONE
                scheduleAutoHide()
            } else {
                openChannelStrip()
            }
        }
        bar.addView(audioButton, barParams())

        nightButton = action(context.getString(R.string.hud_day)) {
            isNight = !isNight
            paintNight()
            scheduleAutoHide()
            onToggleNight()
        }
        bar.addView(nightButton, barParams())

        exitButton = action(context.getString(R.string.close_app)) {
            if (exitArmed) {
                cancelTimers()
                onExit()
            } else {
                // Leaving the app from a bar over the picture is one press away from a mis-tap;
                // a second press within the arm window is what makes it a decision.
                exitArmed = true
                paintExit()
                removeCallbacks(disarmExit)
                postDelayed(disarmExit, EXIT_ARM_MILLIS)
                scheduleAutoHide()
            }
        }
        bar.addView(exitButton, barParams())

        column.addView(bar)
        column.addView(
            channelStrip,
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) },
        )
        addView(
            column,
            LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) },
        )

        paintAudio()
        paintNight()
        paintExit()
        visibility = View.GONE
    }

    /**
     * Shows the bar and arms its idle timer. Labels are repainted from the values the Activity hands
     * in through [setNight] and [setAudioStream] just before this — nothing is stored twice.
     */
    fun open() {
        exitArmed = false
        paintExit()
        channelStrip.visibility = View.GONE
        visibility = View.VISIBLE
        scheduleAutoHide()
    }

    /** Hides the bar and drops every pending timer; safe to call when it is already hidden. */
    fun dismissNow() {
        cancelTimers()
        channelStrip.visibility = View.GONE
        exitArmed = false
        visibility = View.GONE
    }

    fun setNight(night: Boolean) {
        isNight = night
        paintNight()
    }

    fun setAudioStream(stream: Int) {
        audioStream = stream
        paintAudio()
        if (channels.isNotEmpty()) {
            for (index in channels.indices) paintChannel(index, index == audioStream)
        }
    }

    override fun onDetachedFromWindow() {
        cancelTimers()
        super.onDetachedFromWindow()
    }

    private fun openChannelStrip() {
        if (channels.isEmpty()) buildChannels()
        channelStrip.visibility = View.VISIBLE
        // A choice takes longer than the bar's own window, so the strip gets a longer one. It is
        // still a window, not "until dismissed": the bar covers the whole picture, and a bar left
        // waiting for a choice nobody is making is a screen the driver cannot use.
        scheduleAutoHide(CHANNEL_WINDOW_MILLIS)
    }

    private fun buildChannels() {
        for (stream in CHANNEL_RANGE) {
            val chip = TextView(context).apply {
                text = stream.toString()
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    audioStream = stream
                    paintAudio()
                    for (index in channels.indices) paintChannel(index, index == stream)
                    channelStrip.visibility = View.GONE
                    scheduleAutoHide()
                    onAudioSelected(stream)
                }
            }
            channelRow.addView(
                chip,
                LinearLayout.LayoutParams(dp(46), dp(42)).apply {
                    if (stream != CHANNEL_RANGE.first) marginStart = dp(4)
                },
            )
            channels.add(chip)
        }
        for (index in channels.indices) paintChannel(index, index == audioStream)
    }

    private fun paintChannel(index: Int, selected: Boolean) {
        val chip = channels.getOrNull(index) ?: return
        chip.setTextColor(if (selected) ON_ACCENT else TEXT_MUTED)
        chip.background = rounded(if (selected) ACCENT else CHIP_BG, 9)
    }

    private fun paintAudio() {
        audioButton.text = "${context.getString(R.string.audio_routing)} · $audioStream"
    }

    private fun paintNight() {
        nightButton.text = context.getString(if (isNight) R.string.hud_night else R.string.hud_day)
    }

    private fun paintExit() {
        exitButton.text = context.getString(if (exitArmed) R.string.exit_application else R.string.close_app)
        exitButton.setTextColor(if (exitArmed) ON_ACCENT else TEXT_MAIN)
        exitButton.background = rounded(if (exitArmed) DANGER else CHIP_BG, 11)
    }

    private fun scheduleAutoHide(timeout: Long = AUTO_HIDE_MILLIS) {
        removeCallbacks(autoHide)
        postDelayed(autoHide, timeout)
    }

    private fun cancelTimers() {
        removeCallbacks(autoHide)
        removeCallbacks(disarmExit)
    }

    private fun action(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(TEXT_MAIN)
        background = rounded(CHIP_BG, 11)
        setPadding(dp(16), dp(11), dp(16), dp(11))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun barParams(first: Boolean = false) = LinearLayout.LayoutParams(-2, -2).apply {
        if (!first) marginStart = dp(8)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }


    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Black-blue at 90%: the map underneath stays visible, the text stays legible. */
        val BAR_BG = 0xE60C111B.toInt()
        val CHIP_BG = 0x26FFFFFF

        val TEXT_MAIN = 0xFFF1F5FC.toInt()
        val TEXT_MUTED = 0xFFA8B6CA.toInt()

        /** The host page's accent, and the dark ink that reads on it. */
        val ACCENT = 0xFF7FCD9A.toInt()
        val ON_ACCENT = 0xFF0C111B.toInt()

        /** Leaving the app is the one control that must not blend into the others. */
        val DANGER = 0xFFE86A6A.toInt()

        const val AUTO_HIDE_MILLIS = 6_000L
        const val CHANNEL_WINDOW_MILLIS = 20_000L
        const val EXIT_ARM_MILLIS = 3_000L

        /** The stream numbers the settings page offers; the same range, without the grid. */
        val CHANNEL_RANGE = 0..20
    }
}
