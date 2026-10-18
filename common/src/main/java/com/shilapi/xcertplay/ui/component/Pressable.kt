// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui.component

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build

/**
 * A flat fill that answers a press.
 *
 * The cockpit's controls were all drawn with a plain `GradientDrawable`, and a plain
 * `GradientDrawable` has one state: a press on a head unit changed nothing on screen. There is no
 * haptic motor in a car to confirm the touch either, so the driver had no way to tell a press that
 * landed from one that missed without looking away from the road. The settings pages have had a
 * ripple since they were written; this is that, in one place, for the views the cockpit builds.
 *
 * The fill is the same rounded rectangle the caller would have drawn itself, so nothing about the
 * shape changes — only what happens under a finger.
 */
internal fun pressable(
    color: Int,
    cornerRadiusPx: Float,
    strokeWidthPx: Int = 0,
    strokeColor: Int = 0,
    pressedColor: Int = shade(color),
): Drawable {
    val content = shape(color, cornerRadiusPx, strokeWidthPx, strokeColor)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        // The mask is the fill itself: without one the ripple is a rectangle and spills past the
        // rounded corners of everything this is used on.
        return RippleDrawable(
            ColorStateList.valueOf(withAlpha(pressedColor, RIPPLE_ALPHA)),
            content,
            shape(color, cornerRadiusPx, strokeWidthPx, strokeColor),
        )
    }
    // Before 21 there is no ripple, so the pressed state is the fill moved one step: still a change
    // under the finger, which is the whole point.
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(pressedColor, cornerRadiusPx, strokeWidthPx, strokeColor))
        addState(intArrayOf(), content)
    }
}

/**
 * The same thing for a fill the caller keeps and recolours later — the stage's main button is the
 * one: it changes colour with the session phase, and rebuilding its background every second would
 * be an allocation per tick on a head unit with 26 MB free.
 */
internal fun pressable(normal: GradientDrawable, pressed: GradientDrawable): Drawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

/** A fill of one colour, at one corner radius, with an optional hairline. */
internal fun shape(color: Int, cornerRadiusPx: Float, strokeWidthPx: Int = 0, strokeColor: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = cornerRadiusPx
        if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
    }

/**
 * The colour a press paints: the fill moved away from the page it sits on, so a press reads as
 * "down" on a light skin and on a dark one without either being a special case.
 */
internal fun shade(color: Int, ratio: Float = 0.12f): Int {
    val light = luminance(color) > 0.5f
    return blend(color, if (light) Color.BLACK else Color.WHITE, ratio)
}

/** [tint]'s colour at [alpha], for a wash over whatever is behind it rather than a fill of its own. */
internal fun wash(tint: Int, alpha: Int = 0x24): Int = withAlpha(tint, alpha)

internal fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

private fun luminance(color: Int): Float =
    0.299f * Color.red(color) / 255f + 0.587f * Color.green(color) / 255f + 0.114f * Color.blue(color) / 255f

private fun blend(color: Int, towards: Int, ratio: Float): Int {
    fun channel(from: Int, to: Int) = (from + (to - from) * ratio).toInt().coerceIn(0, 255)
    return Color.argb(
        channel(Color.alpha(color), Color.alpha(towards)),
        channel(Color.red(color), Color.red(towards)),
        channel(Color.green(color), Color.green(towards)),
        channel(Color.blue(color), Color.blue(towards)),
    )
}

/** A quarter-strength ripple: visible under a finger, invisible once it is lifted. */
private const val RIPPLE_ALPHA = 0x40
