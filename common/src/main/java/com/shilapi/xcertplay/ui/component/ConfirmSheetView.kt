// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.ui.theme.ThemePalette

/**
 * A question the page asks in its own clothes: scrim, one card, two answers.
 *
 * This replaces the platform dialog the exit control used to raise. A framework AlertDialog draws the
 * framework's title bar, the framework's button order and the framework's grey, none of which follow
 * the skin — on a white head unit it read as a system warning rather than as part of the app, and on
 * the night skin it was the one light-grey rectangle on the screen. Everything here comes from the
 * palette, so the question looks like the thing it is asking about.
 *
 * The caller owns the answer: [onConfirm] runs on the confirm press, [onDismiss] on the scrim, on the
 * cancel press and on back. A sheet is added to whatever hosts the page and removes itself once it has
 * animated out, so nothing has to be kept around to hold it.
 */
class ConfirmSheetView(
    context: Context,
    private val palette: ThemePalette,
    title: String,
    body: String?,
    confirmLabel: String,
    cancelLabel: String,
    private val onConfirm: () -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    private val card: LinearLayout
    private var closing = false

    init {
        setBackgroundColor(SCRIM)
        isClickable = true
        // The scrim is the cancel: a press outside the card answers no.
        setOnClickListener { dismiss() }
        alpha = 0f

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(30), dp(26), dp(30), dp(24))
            background = GradientDrawable().apply {
                setColor(palette.cardBg)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), palette.cardBorder)
            }
            layoutParams = LayoutParams(dp(CARD_WIDTH_DP), -2, Gravity.CENTER)
        }

        // A 3dp rule in the accent colour: the card states what it is about before it says it.
        card.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    setColor(palette.accent)
                    cornerRadius = dp(2).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply { bottomMargin = dp(16) }
            },
        )

        card.addView(
            TextView(context).apply {
                text = title
                textSize = 24f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(palette.textMain)
            },
        )

        if (!body.isNullOrBlank()) {
            card.addView(
                TextView(context).apply {
                    text = body
                    textSize = 16f
                    setTextColor(palette.textMuted)
                    setLineSpacing(dp(4).toFloat(), 1f)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
            )
        }

        val answers = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        answers.addView(answer(cancelLabel, primary = false) { dismiss() })
        answers.addView(answer(confirmLabel, primary = true) {
            if (closing) return@answer
            closing = true
            onConfirm()
        }, LinearLayout.LayoutParams(dp(ANSWER_WIDTH_DP), dp(52)).apply { marginStart = dp(12) })
        card.addView(answers, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        addView(card)

        // 160ms in: long enough to read as a layer arriving, short enough that a finger already
        // moving towards an answer does not wait for it.
        animate().alpha(1f).setDuration(160).start()
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun answer(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(if (primary) palette.btnMainFg else palette.textMain)
            background = pressable(
                if (primary) palette.btnMainBg else palette.cardInner,
                dp(12).toFloat(),
                dp(1),
                palette.cardBorder,
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                stateListAnimator = null
            }
            layoutParams = LinearLayout.LayoutParams(dp(ANSWER_WIDTH_DP), dp(52))
            setOnClickListener { onClick() }
        }

    /** Answers no, and takes the sheet away. Safe to call when it is already leaving. */
    fun dismiss() {
        if (closing) return
        closing = true
        onDismiss()
        animate().alpha(0f).setDuration(120).withEndAction {
            (parent as? android.view.ViewGroup)?.removeView(this)
        }.start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Wide enough for a sentence at 16sp to stay on two lines, narrow enough to read as a card. */
        const val CARD_WIDTH_DP = 560

        /** Two answers of the same width: neither is the default by being bigger. */
        const val ANSWER_WIDTH_DP = 132

        /** Black at 70%: the page behind stays legible, which is the point of a modal question. */
        const val SCRIM = 0xB3000000.toInt()
    }
}
