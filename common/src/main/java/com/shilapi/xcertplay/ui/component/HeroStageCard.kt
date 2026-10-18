// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.SessionPhase
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.theme.ThemePalette

/**
 * The connection stage: 58% of the home page's width, and the only place a session is started.
 *
 * The main control keeps one job per phase — connect while idle, open the projection while live,
 * nothing while an attempt is in flight — and everything that would qualify that (a hotspot that is
 * off, an iPhone that is not paired) is said on the subtitle and in the notes under the emblem
 * instead of being pressed into the button. [updateState] repaints in place: the once-a-second
 * status tick must not rebuild this card, on a head unit with 26 MB free.
 *
 * The notes under the emblem are the old home page's readiness card, kept whole — the connection
 * methods this head unit can run, the adapter's own step, the car-hotspot reminder and a failed
 * bootstrap all survive here rather than being dropped for the wider layout. Its live-session line
 * does not: it told the driver to disconnect on the home page in order to change a setting, which is
 * advice for a settings page, not for the page the Disconnect button is on.
 */
class HeroStageCard(
    context: Context,
    private val palette: ThemePalette,
    sessionPhase: SessionPhase,
    pairedPhoneName: String?,
    private val hasUsbHost: Boolean,
    setupError: String?,
    supportedConnectionView: TextView?,
    adapterStatusView: TextView?,
    hotspotOffNote: String?,
    onConnectWireless: () -> Unit,
    onConnectUsb: () -> Unit,
    onOpenProjection: () -> Unit,
    onDisconnect: () -> Unit,
    private val onOpenSettings: () -> Unit,
) : LinearLayout(context) {

    private val pill: TextView
    private val title: TextView
    private val sub: TextView
    private val emblem: ImageView
    private val mainBtn: Button
    private val disconnectBtn: Button
    private var usbBtn: Button? = null
    private var pulseAnim: android.animation.ObjectAnimator? = null

    /**
     * The main button's two fills, kept as fields because [updateState] recolours them once a
     * second: the attempt in flight paints it in `accentSoft`, and rebuilding the background for
     * that would be two drawables allocated per tick.
     */
    private val mainBtnFill: GradientDrawable
    private val mainBtnPressedFill: GradientDrawable

    /** The gap lines and the way to the controls that close them, redrawn only when they change. */
    private val gapsBox: LinearLayout
    private var shownGaps: List<String>? = null
    private var shownBlocked = false

    private var currentPhase: SessionPhase = sessionPhase

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(palette.cardBg)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), palette.cardBorder)
        }
        setPadding(dp(28), dp(26), dp(28), dp(26))

        pill = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(palette.accentSoft)
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        addView(pill, LayoutParams(-2, -2))

        title = TextView(context).apply {
            text = context.getString(R.string.wireless_carplay)
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.textMain)
            setPadding(0, dp(14), 0, 0)
        }
        addView(title)

        // The self-healing line: why a connection cannot start right now, or the phone it is for.
        sub = TextView(context).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        }
        addView(sub)

        val emblemBox = FrameLayout(context).apply {
            layoutParams = LayoutParams(-1, 0, 1f)
        }
        emblem = ImageView(context).apply {
            // The app's own mark, drawn as it is: a photographic PNG tinted to one colour is a
            // rectangle of that colour, which is what tinting it produced.
            setImageResource(R.drawable.ic_carplay)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER)
        }
        emblemBox.addView(emblem)
        addView(emblemBox)

        val notes = column().apply { setPadding(0, dp(10), 0, 0) }
        gapsBox = column()
        notes.addView(gapsBox, LayoutParams(-1, -2))
        supportedConnectionView?.let { notes.addView(it, LayoutParams(-1, -2)) }
        adapterStatusView?.let { notes.addView(it, LayoutParams(-1, -2)) }
        hotspotOffNote?.let {
            notes.addView(label(it, 15, palette.accent), LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        setupError?.let {
            notes.addView(label(it, 15, palette.accent), LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        addView(notes, LayoutParams(-1, -2))

        val buttonRow = row().apply { setPadding(0, dp(14), 0, 0) }
        mainBtnFill = shape(palette.btnMainBg, dp(10).toFloat())
        mainBtnPressedFill = shape(shade(palette.btnMainBg), dp(10).toFloat())
        mainBtn = Button(context).apply {
            textSize = 15f
            isAllCaps = false
            setTextColor(palette.btnMainFg)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = pressable(mainBtnFill, mainBtnPressedFill)
            setOnClickListener {
                when (currentPhase) {
                    SessionPhase.Live -> onOpenProjection()
                    SessionPhase.Idle -> onConnectWireless()
                    SessionPhase.Connecting -> Unit
                }
            }
        }
        buttonRow.addView(mainBtn, LayoutParams(0, dp(52), 1f))

        disconnectBtn = Button(context).apply {
            text = context.getString(R.string.disconnect)
            textSize = 14f
            isAllCaps = false
            setTextColor(palette.textMuted)
            background = pressable(palette.cardInner, dp(10).toFloat(), dp(1), palette.cardBorder)
            setOnClickListener { onDisconnect() }
            visibility = View.GONE
        }
        buttonRow.addView(disconnectBtn, LayoutParams(-2, dp(52)).apply { marginStart = dp(12) })

        if (hasUsbHost) {
            val uBtn = Button(context).apply {
                text = context.getString(R.string.connect_with_usb)
                textSize = 13.5f
                isAllCaps = false
                setTextColor(palette.textMain)
                background = pressable(palette.cardInner, dp(10).toFloat(), dp(1), palette.cardBorder)
                setOnClickListener { onConnectUsb() }
                visibility = View.GONE
            }
            usbBtn = uBtn
            buttonRow.addView(uBtn, LayoutParams(-2, dp(52)).apply { marginStart = dp(12) })
        }
        addView(buttonRow, LayoutParams(-1, -2))

        updateState(sessionPhase, pairedPhoneName, null, false)
    }

    /** In-place repaint: the tick calls this every second, so nothing here may rebuild the card. */
    fun updateState(phase: SessionPhase, pairedPhone: String?, readinessGaps: List<String>?, isBlocked: Boolean) {
        currentPhase = phase

        pill.text = when (phase) {
            SessionPhase.Live -> "● " + context.getString(R.string.connection_headline_connected)
            SessionPhase.Connecting -> "● " + context.getString(R.string.connection_headline_connecting)
            SessionPhase.Idle -> "● " + context.getString(R.string.ready_when_you_are)
        }
        pill.setTextColor(if (phase == SessionPhase.Connecting) palette.accent else palette.statusReady)

        // A blocked or unready route leads with its own reason; a ready one names the phone.
        sub.visibility = View.VISIBLE
        if (isBlocked && !readinessGaps.isNullOrEmpty()) {
            sub.text = "● " + readinessGaps.first()
            sub.setTextColor(palette.accent)
        } else if (pairedPhone != null) {
            sub.text = "${context.getString(R.string.paired_device)}: $pairedPhone"
            sub.setTextColor(palette.textMuted)
        } else if (phase == SessionPhase.Idle) {
            // The pill already says the page is ready; saying it again underneath was the same
            // sentence twice. What the idle page can usefully add is what is still missing: a phone.
            sub.text = context.getString(R.string.pair_iphone_to_connect)
            sub.setTextColor(palette.textMuted)
        } else {
            sub.text = ""
            sub.visibility = View.GONE
        }

        paintGaps(readinessGaps, isBlocked)

        // The pulse and the static alpha are exclusive: the animator drives this property every
        // frame, so writing 0.65 underneath it was a value that could only ever be overwritten —
        // and would freeze at the wrong brightness the moment the animator was stopped.
        if (phase == SessionPhase.Connecting) {
            startConnectingPulse()
        } else {
            stopConnectingPulse()
            emblem.alpha = if (phase == SessionPhase.Live) 1.0f else 0.65f
        }

        // The card says what the session is doing and what the button will do next; it does not say
        // "disconnect on the home page first", because this *is* the home page — that line belongs
        // under the settings that a running session locks, and lives there (see DiPlayActivity's
        // liveSessionNote). It was copied here with the readiness card and read as nonsense: on the
        // stage there is nothing to change and the Disconnect button is right beside it.
        mainBtn.text = when (phase) {
            SessionPhase.Live -> context.getString(R.string.open_carplay)
            SessionPhase.Connecting -> context.getString(R.string.connecting_to_your_iphone)
            SessionPhase.Idle -> context.getString(R.string.connect_phone)
        }
        mainBtn.isEnabled = phase != SessionPhase.Connecting
        // The fill and the label move together: the attempt in flight paints the button in
        // `accentSoft`, and leaving the label in `btnMainFg` drew white on a 12% peach — a button
        // that said nothing at all in the two light skins, exactly when it was saying the most.
        val connecting = phase == SessionPhase.Connecting
        val fill = if (connecting) palette.accentSoft else palette.btnMainBg
        mainBtnFill.setColor(fill)
        mainBtnPressedFill.setColor(shade(fill))
        mainBtn.setTextColor(if (connecting) palette.accent else palette.btnMainFg)

        disconnectBtn.visibility = if (phase == SessionPhase.Live) View.VISIBLE else View.GONE
        usbBtn?.visibility = if (phase == SessionPhase.Idle && hasUsbHost) View.VISIBLE else View.GONE
    }

    /**
     * The gap lines, and the way to the controls that close them.
     *
     * Rebuilt only when the list itself changes, not on every tick: the readiness is read once per
     * redraw and a per-second rebuild of six TextViews is the GC churn the stage exists to avoid.
     *
     * The first gap is not repeated here. [updateState] already prints it under the title as the
     * subtitle — the card leads with the one thing standing in the way — and printing the same red
     * sentence twice on one card read as a rendering fault rather than as emphasis. What is left for
     * this list is the rest of them, and the way to the controls.
     */
    private fun paintGaps(readinessGaps: List<String>?, isBlocked: Boolean) {
        val gaps = if (isBlocked) readinessGaps.orEmpty().drop(1) else emptyList()
        if (gaps == shownGaps && isBlocked == shownBlocked) return
        shownGaps = gaps
        shownBlocked = isBlocked
        gapsBox.removeAllViews()
        if (gaps.isEmpty() && !isBlocked) {
            gapsBox.visibility = View.GONE
            return
        }
        gapsBox.visibility = View.VISIBLE
        gaps.forEach { gap ->
            gapsBox.addView(label("● $gap", 15, palette.accent), LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        gapsBox.addView(
            Button(context).apply {
                text = context.getString(R.string.open_settings)
                textSize = 13f
                isAllCaps = false
                setTextColor(palette.accent)
                background = pressable(palette.cardInner, dp(8).toFloat(), dp(1), palette.cardBorder)
                setOnClickListener { onOpenSettings() }
            },
            LayoutParams(-2, dp(44)).apply { topMargin = dp(8) },
        )
    }

    private fun startConnectingPulse() {
        if (pulseAnim == null) {
            pulseAnim = android.animation.ObjectAnimator.ofFloat(emblem, "alpha", 0.45f, 1.0f).apply {
                duration = 800
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
            }
        }
        if (pulseAnim?.isRunning == false) pulseAnim?.start()
    }

    private fun stopConnectingPulse() {
        pulseAnim?.cancel()
        pulseAnim = null
    }

    /**
     * The pulse is an `ObjectAnimator` on this view's own property, and nothing about detaching a
     * target stops an animator: Choreographer keeps calling it every frame, and the animator keeps
     * the card — and through it the Activity — reachable. The home page is rebuilt on every
     * settings change, so an attempt in flight during a redraw used to leave one card and one
     * running animation behind for good.
     */
    override fun onDetachedFromWindow() {
        stopConnectingPulse()
        super.onDetachedFromWindow()
    }

    /**
     * Hidden is as good as gone for an animation nobody can see: the projection Activity covering
     * the page, the car's own launcher, or the screen going dark all leave this view attached, so
     * detach alone would not have stopped it. Coming back re-arms it if the attempt is still up —
     * [updateState] runs on the next tick either way.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) {
            stopConnectingPulse()
        } else if (currentPhase == SessionPhase.Connecting) {
            startConnectingPulse()
        }
    }

    private fun label(value: String, size: Int, color: Int) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        setLineSpacing(dp(3).toFloat(), 1f)
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
