// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.ui.theme.ThemePalette

/**
 * The export card: pick a mount, look at its files, then save. Same clothes as [ConfirmSheetView].
 *
 * The caller decides what each row does. A row that is not clickable is only text, so a file name
 * cannot dismiss the card the way a platform list would. Swapping the rows does not rebuild the card.
 */
class ExportFolderSheet(
    context: Context,
    private val palette: ThemePalette,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    data class Choice(
        val title: String,
        val detail: String?,
        val clickable: Boolean,
        val onClick: (() -> Unit)?,
    )

    private val card: LinearLayout
    private val titleView: TextView
    private val subtitleView: TextView
    private val rows: LinearLayout
    private val scroller: ScrollView
    private val buttonRows: LinearLayout
    private var buttons: LinearLayout
    private var primaryButton: Button? = null
    private var closing = false

    init {
        setBackgroundColor(SCRIM)
        isClickable = true
        setOnClickListener { dismiss() }
        alpha = 0f

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { }
            setPadding(dp(30), dp(26), dp(30), dp(24))
            background = GradientDrawable().apply {
                setColor(palette.cardBg)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), palette.cardBorder)
            }
            layoutParams = LayoutParams(dp(CARD_WIDTH_DP), cardHeight(), Gravity.CENTER)
        }
        card.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    setColor(palette.accent)
                    cornerRadius = dp(2).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply { bottomMargin = dp(16) }
            },
        )
        titleView = TextView(context).apply {
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.textMain)
        }
        card.addView(titleView)
        subtitleView = TextView(context).apply {
            textSize = 15f
            setTextColor(palette.textMuted)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        card.addView(subtitleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(rows, LayoutParams(-1, -2))
        }
        card.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })

        buttonRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        buttons = newButtonRow()
        buttonRows.addView(buttons)
        card.addView(buttonRows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        addView(card)

        animate().alpha(1f).setDuration(160).start()
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    /** A redraw detaches the card. Put it back without playing the entrance again. */
    fun settle() {
        alpha = 1f
        card.scaleX = 1f
        card.scaleY = 1f
    }

    fun showLoading(title: String, subtitle: String) {
        titleView.text = title
        subtitleView.text = subtitle
        subtitleView.visibility = VISIBLE
        rows.removeAllViews()
        clearButtons()
        scroller.scrollTo(0, 0)
    }

    fun showChoices(title: String, subtitle: String, choices: List<Choice>, saveLabel: String?, onSave: (() -> Unit)?, closeLabel: String) {
        showLoading(title, subtitle)
        for (choice in choices) {
            rows.addView(row(choice), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        addButton(closeLabel, primary = false) { dismiss() }
        if (saveLabel != null && onSave != null) {
            primaryButton = addButton(saveLabel, primary = true, onClick = onSave)
        }
    }

    fun setSaving(label: String) {
        for (index in 0 until buttonRows.childCount) {
            val row = buttonRows.getChildAt(index) as? LinearLayout ?: continue
            for (child in 0 until row.childCount) row.getChildAt(child).isEnabled = false
        }
        primaryButton?.text = label
    }

    fun showSaved(
        title: String,
        body: String,
        viewLabel: String,
        onView: () -> Unit,
        shareLabel: String?,
        onShare: (() -> Unit)?,
        doneLabel: String,
    ) {
        showMessage(title, body)
        addButton(viewLabel, primary = false, onClick = onView)
        if (shareLabel != null && onShare != null) addButton(shareLabel, primary = false, onClick = onShare)
        addButton(doneLabel, primary = true) { dismiss() }
    }

    /**
     * The chosen place did not take the file. View and share sit on the first row when a private
     * copy exists. Close, an optional other place, and retry sit on the next row so five labels
     * do not clip. Retry is the primary button, so a follow-up save disables it.
     */
    fun showFailed(
        title: String,
        body: String,
        viewLabel: String?,
        onView: (() -> Unit)?,
        shareLabel: String?,
        onShare: (() -> Unit)?,
        alternateLabel: String?,
        onAlternate: (() -> Unit)?,
        retryLabel: String,
        onRetry: () -> Unit,
        closeLabel: String,
    ) {
        showMessage(title, body)
        if (viewLabel != null && onView != null) {
            addButton(viewLabel, primary = false, onClick = onView)
            if (shareLabel != null && onShare != null) addButton(shareLabel, primary = false, onClick = onShare)
            nextButtonRow()
        }
        addButton(closeLabel, primary = false) { dismiss() }
        if (alternateLabel != null && onAlternate != null) addButton(alternateLabel, primary = false, onClick = onAlternate)
        primaryButton = addButton(retryLabel, primary = true, onClick = onRetry)
    }

    fun dismiss() {
        if (closing) return
        closing = true
        onDismiss()
        animate().alpha(0f).setDuration(120).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
        }.start()
    }

    /** The activity is going away. Removing is enough; an end-of-animation callback would outlive it. */
    fun removeImmediately() {
        closing = true
        animate().cancel()
        card.animate().cancel()
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun showMessage(title: String, body: String) {
        titleView.text = title
        subtitleView.visibility = GONE
        rows.removeAllViews()
        scroller.scrollTo(0, 0)
        rows.addView(TextView(context).apply {
            text = body
            textSize = 16f
            setTextColor(palette.textMain)
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        clearButtons()
    }

    private fun newButtonRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }

    private fun nextButtonRow() {
        buttons = newButtonRow()
        buttonRows.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun clearButtons() {
        buttonRows.removeAllViews()
        buttons = newButtonRow()
        buttonRows.addView(buttons)
        primaryButton = null
    }

    private fun row(choice: Choice): View {
        if (!choice.clickable) {
            return TextView(context).apply {
                text = choice.title
                textSize = 16f
                setTextColor(palette.textMuted)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(64)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = pressable(palette.cardInner, dp(12).toFloat(), dp(1), palette.cardBorder)
            isClickable = true
            setOnClickListener { choice.onClick?.invoke() }
            addView(TextView(context).apply {
                text = choice.title
                textSize = 18f
                setTextColor(palette.textMain)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            if (!choice.detail.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = choice.detail
                    textSize = 14f
                    setTextColor(palette.textMuted)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }
        }
    }

    private fun addButton(label: String, primary: Boolean, onClick: () -> Unit): Button {
        val button = Button(context).apply {
            text = label
            isAllCaps = false
            transformationMethod = null
            textSize = 16f
            setTextColor(if (primary) palette.btnMainFg else palette.textMain)
            background = pressable(
                if (primary) palette.btnMainBg else palette.cardInner,
                dp(12).toFloat(),
                dp(1),
                palette.cardBorder,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
            minimumWidth = dp(132)
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { if (isEnabled) onClick() }
        }
        buttons.addView(button, LinearLayout.LayoutParams(-2, dp(52)).apply {
            if (buttons.childCount > 0) marginStart = dp(12)
        })
        return button
    }

    private fun cardHeight(): Int = (resources.displayMetrics.heightPixels * 0.70f).toInt().coerceAtLeast(dp(240))

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val CARD_WIDTH_DP = 640
        const val SCRIM = 0xB3000000.toInt()
    }
}
