// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.theme.ThemePalette

enum class SettingsTab(val key: String) {
    AUDIO("audio"),
    CONNECTION("connection"),
    DISPLAY("display"),
    SYSTEM("system"),
    EXPERIMENTAL("experimental");

    companion object {
        fun fromKey(key: String?): SettingsTab =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: AUDIO
    }
}

/**
 * 1920×720 车机宽屏全屏双栏设置组件：左侧分类 Tab 导航，右侧全屏平铺内容面板。
 */
class SettingsScreenView(
    context: Context,
    private val palette: ThemePalette,
    initialTab: SettingsTab = SettingsTab.AUDIO,
    hasExperimental: Boolean = false,
    private val onBack: () -> Unit,
    buildAudioSection: (LinearLayout) -> Unit,
    buildConnectionSection: (LinearLayout) -> Unit,
    buildDisplaySection: (LinearLayout) -> Unit,
    buildSystemSection: (LinearLayout) -> Unit,
    buildExperimentalSection: ((LinearLayout) -> Unit)? = null,
) : FrameLayout(context) {

    private var activeTab: SettingsTab = initialTab
    private val tabButtons = mutableMapOf<SettingsTab, TextView>()
    private val contentContainers = mutableMapOf<SettingsTab, View>()

    init {
        setBackgroundColor(palette.screenBg)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(-1, -1)
        }

        // 1. 左侧 Slim Rail 导航区 (固定宽度 180dp)
        val rail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.dockBg)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            layoutParams = LinearLayout.LayoutParams(dp(180), -1)
        }

        // 返回按钮
        val backBtn = Button(context).apply {
            tag = "settings_back_btn"
            text = context.getString(R.string.back)
            textSize = 17f
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.textMain)
            gravity = Gravity.CENTER
            background = pressable(wash(palette.textMain), dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(20) }
            setOnClickListener { onBack() }
        }
        rail.addView(backBtn)

        // Tab 项列表
        val tabDefinitions = mutableListOf(
            SettingsTab.AUDIO to context.getString(R.string.audio_routing),
            SettingsTab.CONNECTION to context.getString(R.string.connection),
            SettingsTab.DISPLAY to context.getString(R.string.display_and_performance),
            SettingsTab.SYSTEM to context.getString(R.string.system_settings),
        )
        if (hasExperimental && buildExperimentalSection != null) {
            tabDefinitions.add(SettingsTab.EXPERIMENTAL to context.getString(R.string.experimental_features))
        }

        for ((tab, labelText) in tabDefinitions) {
            val tabBtn = TextView(context).apply {
                text = labelText
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(16), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) }
                setOnClickListener { selectTab(tab) }
            }
            tabButtons[tab] = tabBtn
            rail.addView(tabBtn)
        }

        root.addView(rail)

        // 2. 右侧全屏卡片容器区
        val contentStage = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        fun wrapSection(builder: (LinearLayout) -> Unit): View {
            val scroll = ScrollView(context).apply {
                layoutParams = LayoutParams(-1, -1)
                clipToPadding = false
                isFillViewport = true
            }
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(24))
            }
            builder(container)
            scroll.addView(container)
            return scroll
        }

        contentContainers[SettingsTab.AUDIO] = wrapSection(buildAudioSection).also { contentStage.addView(it) }
        contentContainers[SettingsTab.CONNECTION] = wrapSection(buildConnectionSection).also { contentStage.addView(it) }
        contentContainers[SettingsTab.DISPLAY] = wrapSection(buildDisplaySection).also { contentStage.addView(it) }
        contentContainers[SettingsTab.SYSTEM] = wrapSection(buildSystemSection).also { contentStage.addView(it) }
        if (hasExperimental && buildExperimentalSection != null) {
            contentContainers[SettingsTab.EXPERIMENTAL] = wrapSection(buildExperimentalSection).also { contentStage.addView(it) }
        }

        root.addView(contentStage)
        addView(root)

        selectTab(initialTab)
    }

    fun currentTab(): SettingsTab = activeTab

    fun selectTab(tab: SettingsTab) {
        val target = if (tab in tabButtons) tab else SettingsTab.AUDIO
        activeTab = target
        for ((t, btn) in tabButtons) {
            val isSelected = (t == target)
            if (isSelected) {
                btn.setTextColor(palette.accent)
                btn.background = GradientDrawable().apply {
                    setColor(wash(palette.accent, 0.18f))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), wash(palette.accent, 0.4f))
                }
            } else {
                btn.setTextColor(palette.textMuted)
                btn.background = null
            }
        }
        for ((t, container) in contentContainers) {
            container.visibility = if (t == target) View.VISIBLE else View.GONE
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun wash(color: Int, fraction: Float = 0.12f): Int {
        val alpha = (Color.alpha(color) * fraction).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun pressable(pressedColor: Int, radius: Float) = StateListDrawable().apply {
        val normal = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = radius }
        val pressed = GradientDrawable().apply { setColor(pressedColor); cornerRadius = radius }
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }
}
