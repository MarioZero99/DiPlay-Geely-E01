package com.shilapi.xcertplay.ui.component

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.ui.theme.CockpitThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsScreenViewTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `default initial tab is selected and onBack callback triggers`() {
        var backPressed = false
        val view = SettingsScreenView(
            context,
            CockpitThemeManager.ZEEKR_WHITE_PALETTE,
            initialTab = SettingsTab.AUDIO,
            hasExperimental = false,
            onBack = { backPressed = true },
            buildAudioSection = { it.addView(TextView(context).apply { text = "AudioContent" }) },
            buildConnectionSection = { it.addView(TextView(context).apply { text = "ConnectionContent" }) },
            buildDisplaySection = { it.addView(TextView(context).apply { text = "DisplayContent" }) },
            buildSystemSection = { it.addView(TextView(context).apply { text = "SystemContent" }) },
        )

        assertEquals(SettingsTab.AUDIO, view.currentTab())
        val backBtn = view.findViewWithTag<View>("settings_back_btn")
        assertNotNull(backBtn)
        backBtn.performClick()
        assertTrue(backPressed)
    }

    @Test
    fun `switching tab changes active content visibility without recreation`() {
        val view = SettingsScreenView(
            context,
            CockpitThemeManager.ZEEKR_WHITE_PALETTE,
            initialTab = SettingsTab.AUDIO,
            hasExperimental = true,
            onBack = {},
            buildAudioSection = { it.addView(TextView(context).apply { text = "Audio" }) },
            buildConnectionSection = { it.addView(TextView(context).apply { text = "Conn" }) },
            buildDisplaySection = { it.addView(TextView(context).apply { text = "Disp" }) },
            buildGovernanceSection = { it.addView(TextView(context).apply { text = "Gov" }) },
            buildSystemSection = { it.addView(TextView(context).apply { text = "Sys" }) },
            buildExperimentalSection = { it.addView(TextView(context).apply { text = "Exp" }) },
        )

        view.selectTab(SettingsTab.CONNECTION)
        assertEquals(SettingsTab.CONNECTION, view.currentTab())

        view.selectTab(SettingsTab.SYSTEM)
        val gov = textViews(view).first { it.text == "Gov" }
        assertEquals(View.GONE, panel(gov).visibility)

        view.selectTab(SettingsTab.GOVERNANCE)
        assertEquals(SettingsTab.GOVERNANCE, view.currentTab())
        assertEquals(View.VISIBLE, panel(gov).visibility)

        view.selectTab(SettingsTab.EXPERIMENTAL)
        assertEquals(SettingsTab.EXPERIMENTAL, view.currentTab())
    }

    private fun panel(label: View): View {
        var current = label
        while (current.parent is View && current.parent !is android.widget.ScrollView) {
            current = current.parent as View
        }
        return current.parent as? View ?: current
    }

    private fun textViews(root: View): List<TextView> {
        val found = mutableListOf<TextView>()
        if (root is TextView) found += root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) found += textViews(root.getChildAt(index))
        }
        return found
    }
}
