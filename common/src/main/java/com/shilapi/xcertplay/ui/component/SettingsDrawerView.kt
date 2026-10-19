// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.theme.ThemePalette

/**
 * Every setting the app has, on a panel that slides over the home page from the trailing edge.
 *
 * The panel is built once and kept GONE in the tree: opening and closing it is a visibility change
 * with a 200ms translation, not a rebuild — the head unit this runs on has between 26 and 33 MB
 * free, and the settings cards behind it are the heaviest views in the app. The sections
 * themselves are the Activity's existing card builders, handed in as lambdas, so the drawer holds
 * the same controls the full settings page does rather than a second copy of them.
 *
 * A scrim covers the whole screen: tapping outside the panel closes the drawer, and the steering
 * wheel's back key is wired to the same close by the Activity.
 */
class SettingsDrawerView(
    context: Context,
    private val palette: ThemePalette,
    private val buildThemeSection: (LinearLayout) -> Unit,
    private val buildConnectionSection: (LinearLayout) -> Unit,
    private val buildDisplaySection: (LinearLayout) -> Unit,
    private val buildSystemSection: (LinearLayout) -> Unit,
    private val buildExperimentalSection: ((LinearLayout) -> Unit)?,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    private val panel: LinearLayout
    private val scroll: ScrollView
    private val content: LinearLayout

    init {
        setBackgroundColor(SCRIM)
        setOnClickListener { onClose() }

        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.cardBg)
                cornerRadii = floatArrayOf(
                    dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f, dp(18).toFloat(), dp(18).toFloat(),
                )
            }
            isClickable = true
            isFocusable = true
            setPadding(dp(24), dp(20), dp(24), dp(20))
            layoutParams = LayoutParams(panelWidthPx(), -1).apply { gravity = Gravity.END }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.system_settings)
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(palette.textMain)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        header.addView(
            Button(context).apply {
                text = CLOSE_GLYPH
                textSize = 18f
                isAllCaps = false
                setTextColor(palette.textMuted)
                // A glyph says nothing to a screen reader, and "multiplication sign" is worse than
                // nothing: the control needs the word it is standing in for.
                contentDescription = context.getString(R.string.close_settings)
                background = pressable(0, dp(10).toFloat(), pressedColor = wash(palette.textMain))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setOnClickListener { onClose() }
            },
        )
        panel.addView(header)

        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            clipToPadding = false
        }
        scroll.addView(content)
        panel.addView(scroll)

        buildThemeSection(content)
        buildConnectionSection(content)
        buildDisplaySection(content)
        buildSystemSection(content)
        buildExperimentalSection?.invoke(content)

        addView(panel)
        visibility = GONE
    }

    /**
     * 200ms decelerating slide-in on a hardware layer, handed back when the animation ends.
     *
     * [animate] false is for a redraw that has to put an already-open drawer back where it was:
     * the page rebuilds around it, and slamming it shut — or sliding it in again — would be the
     * flicker the whole in-place design exists to avoid. A slide that is still running is cancelled
     * first, including its end action: that action puts the panel back to `GONE`, and a reopen that
     * raced a close used to end with an invisible drawer the Activity still believed was open.
     */
    fun open(animate: Boolean = true) {
        settleRunningAnimation()
        visibility = VISIBLE
        if (!animate) {
            // A redraw that puts an already-open drawer back: the caller restores the offset right
            // after this returns, and a scroll-to-top before it would throw that offset away first.
            panel.translationX = 0f
            alpha = 1f
            panel.setLayerType(LAYER_TYPE_NONE, null)
            return
        }
        // Every fresh open starts at the top: the panel keeps its scroll position between visits, and
        // landing mid-page because that is where it was closed reads as the drawer opening on the
        // wrong section. A caller that wants a section asks for it afterwards with [scrollToText].
        scroll.scrollTo(0, 0)
        panel.translationX = panel.width.takeIf { it > 0 }?.toFloat() ?: panelWidthPx().toFloat()
        alpha = 0f
        panel.setLayerType(LAYER_TYPE_HARDWARE, null)
        animate().alpha(1f).setDuration(200).start()
        panel.animate()
            .translationX(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { panel.setLayerType(LAYER_TYPE_NONE, null) }
            .start()
    }

    /** 180ms accelerating slide-out, a beat quicker than the way in so leaving feels lighter. */
    fun close(animate: Boolean = true, onClosed: () -> Unit = {}) {
        settleRunningAnimation()
        if (!animate) {
            // The layer has to go back here too: a close that does not animate still hides a panel
            // that may have been handed a hardware layer by the slide-in, and a hidden view tree
            // left holding one keeps its GPU buffer on a head unit with 26 MB free.
            panel.setLayerType(LAYER_TYPE_NONE, null)
            visibility = GONE
            panel.translationX = 0f
            alpha = 1f
            onClosed()
            return
        }
        panel.setLayerType(LAYER_TYPE_HARDWARE, null)
        animate().alpha(0f).setDuration(180).start()
        panel.animate()
            .translationX(panel.width.takeIf { it > 0 }?.toFloat() ?: panelWidthPx().toFloat())
            .setDuration(180)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                panel.setLayerType(LAYER_TYPE_NONE, null)
                visibility = GONE
                // Left at its off-screen position would make the next open start from there and
                // animate from 520 to 520 — a drawer that appears without sliding.
                panel.translationX = 0f
                alpha = 1f
                onClosed()
            }
            .start()
    }

    /**
     * The scroll position, for a redraw that has to put the panel back where the driver left it.
     *
     * A settings change inside the drawer rebuilds the page under it, and every rebuild is a new
     * panel: without this, changing anything in the lower half of the list threw the driver back to
     * the appearance section at the top, which reads as the drawer having been reopened.
     */
    fun scrollOffset(): Int = scroll.scrollY

    fun scrollToOffset(offset: Int) {
        scrollWhenLaidOut { scroll.scrollTo(0, offset) }
    }

    /**
     * Runs [action] once the panel has a height to clamp against.
     *
     * `scroll.post { }` is not enough, and that is what this used to do: the drawer is normally
     * opened the moment it is built, and a runnable posted to the main queue runs on the next loop —
     * before the vsync that measures the view. `ScrollView.scrollTo` clamps against the child's
     * height, which is still zero, so the offset was thrown away and the panel stayed at the top.
     * That is why opening on "audio routing" landed on "Appearance", and why a setting changed in
     * the lower half of the list came back at the top.
     *
     * A panel that is already laid out — a second press on a section while the drawer is open — is
     * scrolled straight away, because waiting for a layout that may never come would do nothing.
     */
    private fun scrollWhenLaidOut(action: () -> Unit) {
        val content = scroll.getChildAt(0)
        if (content != null && content.height > 0) {
            action()
            return
        }
        val observer = scroll.viewTreeObserver
        observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                observer.removeOnGlobalLayoutListener(this)
                action()
            }
        })
    }

    /**
     * Stops whatever the two animators are doing, end actions included, so the next open or close
     * starts from a settled panel rather than from the middle of the previous move.
     */
    private fun settleRunningAnimation() {
        panel.animate().cancel()
        animate().cancel()
    }

    /**
     * Brings the first card whose heading starts with [prefix] into view.
     *
     * Offsets are only meaningful after layout, so this waits for it — see [scrollWhenLaidOut].
     */
    fun scrollToText(prefix: String) {
        val target = firstTextStartingWith(content, prefix) ?: return
        scrollWhenLaidOut {
            var offset = 0
            var walker: View? = target
            while (walker != null && walker !== scroll) {
                offset += walker.top
                walker = walker.parent as? View
            }
            scroll.scrollTo(0, (offset - dp(12)).coerceAtLeast(0))
        }
    }

    private fun firstTextStartingWith(root: View, prefix: String): TextView? {
        if (root is TextView && root.text?.startsWith(prefix) == true) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                firstTextStartingWith(root.getChildAt(index), prefix)?.let { return it }
            }
        }
        return null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /**
     * How wide the panel opens.
     *
     * It was a flat 520dp, which is about a quarter of this head unit: the E01 is 1920×720 at
     * 160dpi, so its usable width is 1920dp and the drawer covered 27% of it while the settings
     * inside kept their desktop-sized cards. On a screen that wide the panel can afford close to
     * half; the stage behind it still shows, which is what makes it a drawer rather than a page.
     */
    internal fun computePanelWidthDp(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return if (widthDp >= 1000) {
            (widthDp * 0.42).toInt().coerceIn(680, 800)
        } else {
            // Narrow: never wider than the screen minus its margins, and not smaller than 320dp —
            // a 320dp Robolectric default lands here, and so would a phone-sized head unit.
            PANEL_WIDTH_DP.coerceAtMost((widthDp - 40).coerceAtLeast(320))
        }
    }

    private fun panelWidthPx(): Int = dp(computePanelWidthDp())

    companion object {
        /** The floor for narrow screens; widescreen units are sized by [computePanelWidthDp]. */
        const val PANEL_WIDTH_DP = 800

        private const val SCRIM = 0x55000000

        /** A glyph, not copy: the close control has to read at a glance from the driver's seat. */
        private const val CLOSE_GLYPH = "✕"
    }
}
