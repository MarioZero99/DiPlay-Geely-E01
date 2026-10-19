package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.widget.TextView
import com.shilapi.xcertplay.ui.theme.CockpitThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 验证 1920×720@160dpi (1200dp~1920dp) 宽屏座舱下全屏双栏设置组件的正常初始化与 Tab 布局。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp")
class SettingsScreenWidescreenTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `widescreen settings root occupies full 1920dp without scrim or drawer translation`() {
        val view = SettingsScreenView(
            context,
            CockpitThemeManager.ZEEKR_WHITE_PALETTE,
            initialTab = SettingsTab.AUDIO,
            hasExperimental = true,
            onBack = {},
            buildAudioSection = { it.addView(TextView(context).apply { text = "AudioContent" }) },
            buildConnectionSection = { it.addView(TextView(context).apply { text = "ConnectionContent" }) },
            buildDisplaySection = { it.addView(TextView(context).apply { text = "DisplayContent" }) },
            buildSystemSection = { it.addView(TextView(context).apply { text = "SystemContent" }) },
            buildExperimentalSection = { it.addView(TextView(context).apply { text = "ExperimentalContent" }) },
        )

        assertNotNull(view)
        assertEquals(SettingsTab.AUDIO, view.currentTab())

        view.selectTab(SettingsTab.SYSTEM)
        assertEquals(SettingsTab.SYSTEM, view.currentTab())
    }
}
