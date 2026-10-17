package com.shilapi.xcertplay.ui.theme

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitThemeTest {
    @Test
    fun testNightTimeHours() {
        assertTrue(CockpitThemeManager.isNightHour(18))
        assertTrue(CockpitThemeManager.isNightHour(23))
        assertTrue(CockpitThemeManager.isNightHour(0))
        assertTrue(CockpitThemeManager.isNightHour(5))
        assertFalse(CockpitThemeManager.isNightHour(6))
        assertFalse(CockpitThemeManager.isNightHour(12))
        assertFalse(CockpitThemeManager.isNightHour(17))
    }

    @Test
    fun testCarHeadlightUiModeTakesPrecedence() {
        // 车机大灯触发的 UI_MODE_NIGHT_YES 拥有最高日夜优先级
        val palette = CockpitThemeManager.resolvePalette(
            skin = AppSkin.ZEEKR_WHITE,
            schedule = ThemeSchedule.AUTO_CAR,
            uiMode = Configuration.UI_MODE_NIGHT_YES,
            currentHour = 12 // 即使中午 12 点，车机进夜/进隧道也要变暗黑
        )
        assertEquals(0xFF0B0E14.toInt(), palette.screenBg)
    }

    @Test
    fun testTimeFallbackWhenCarUiModeUndefined() {
        val paletteNight = CockpitThemeManager.resolvePalette(
            skin = AppSkin.ZEEKR_WHITE,
            schedule = ThemeSchedule.AUTO_TIME,
            uiMode = Configuration.UI_MODE_NIGHT_NO,
            currentHour = 20
        )
        assertEquals(0xFF0B0E14.toInt(), paletteNight.screenBg)

        val paletteDay = CockpitThemeManager.resolvePalette(
            skin = AppSkin.ZEEKR_WHITE,
            schedule = ThemeSchedule.AUTO_TIME,
            uiMode = Configuration.UI_MODE_NIGHT_NO,
            currentHour = 14
        )
        assertEquals(0xFFF4F6FA.toInt(), paletteDay.screenBg)
        assertEquals(0xFFFF5E00.toInt(), paletteDay.accent) // 极氪橙
    }
}
