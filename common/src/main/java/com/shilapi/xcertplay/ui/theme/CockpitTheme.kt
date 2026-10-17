package com.shilapi.xcertplay.ui.theme

import android.content.res.Configuration
import com.shilapi.xcertplay.isDarkMode

enum class AppSkin(val key: String) {
    ZEEKR_WHITE("zeekr_white"),
    FLYME_WHITE("flyme_white"),
    NIGHT_DARK("night_dark");

    companion object {
        fun fromKey(key: String?): AppSkin = entries.find { it.key == key } ?: ZEEKR_WHITE
    }
}

enum class ThemeSchedule(val key: String) {
    AUTO_CAR("auto_car"),   // 车机大灯/系统暗黑信号优先
    AUTO_TIME("auto_time"), // 18:00 - 06:00 时间优先
    MANUAL("manual");

    companion object {
        fun fromKey(key: String?): ThemeSchedule = entries.find { it.key == key } ?: AUTO_CAR
    }
}

data class ThemePalette(
    val screenBg: Int,
    val dockBg: Int,
    val cardBg: Int,
    val cardInner: Int,
    val cardBorder: Int,
    val textMain: Int,
    val textMuted: Int,
    val accent: Int,
    val accentSoft: Int,
    val btnMainBg: Int,
    val btnMainFg: Int,
    val statusReady: Int
)

object CockpitThemeManager {
    // 纯整型字面量，不调用 android.graphics.Color，零 Mock 风险
    val ZEEKR_WHITE_PALETTE = ThemePalette(
        screenBg = 0xFFF4F6FA.toInt(),
        dockBg = 0xFFFFFFFF.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        cardInner = 0xFFF8FAFC.toInt(),
        cardBorder = 0x0F000000,
        textMain = 0xFF0F172A.toInt(),
        textMuted = 0xFF64748B.toInt(),
        accent = 0xFFFF5E00.toInt(),
        accentSoft = 0x1FFF5E00.toInt(),
        btnMainBg = 0xFF0F172A.toInt(),
        btnMainFg = 0xFFFFFFFF.toInt(),
        statusReady = 0xFF10B981.toInt()
    )

    val FLYME_WHITE_PALETTE = ThemePalette(
        screenBg = 0xFFF5F8FC.toInt(),
        dockBg = 0xFFFFFFFF.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        cardInner = 0xFFF0F4FA.toInt(),
        cardBorder = 0x142563EB.toInt(),
        textMain = 0xFF1E293B.toInt(),
        textMuted = 0xFF64748B.toInt(),
        accent = 0xFF2563EB.toInt(),
        accentSoft = 0x1F2563EB.toInt(),
        btnMainBg = 0xFF2563EB.toInt(),
        btnMainFg = 0xFFFFFFFF.toInt(),
        statusReady = 0xFF059669.toInt()
    )

    val NIGHT_DARK_PALETTE = ThemePalette(
        screenBg = 0xFF0B0E14.toInt(),
        dockBg = 0xFF11141C.toInt(),
        cardBg = 0xFF141923.toInt(),
        cardInner = 0xFF1A202C.toInt(),
        cardBorder = 0x12FFFFFF,
        textMain = 0xFFF8FAFC.toInt(),
        textMuted = 0xFF94A3B8.toInt(),
        accent = 0xFFFF5E00.toInt(),
        accentSoft = 0x2DFF5E00.toInt(),
        btnMainBg = 0xFFFFFFFF.toInt(),
        btnMainFg = 0xFF000000.toInt(),
        statusReady = 0xFF26D07C.toInt()
    )

    val FLYME_DARK_PALETTE = ThemePalette(
        screenBg = 0xFF0B0E14.toInt(),
        dockBg = 0xFF10141D.toInt(),
        cardBg = 0xFF151A26.toInt(),
        cardInner = 0xFF1C2230.toInt(),
        cardBorder = 0x143B82F6.toInt(),
        textMain = 0xFFF8FAFC.toInt(),
        textMuted = 0xFF94A3B8.toInt(),
        accent = 0xFF3B82F6.toInt(),
        accentSoft = 0x2D3B82F6.toInt(),
        btnMainBg = 0xFF3B82F6.toInt(),
        btnMainFg = 0xFFFFFFFF.toInt(),
        statusReady = 0xFF26D07C.toInt()
    )

    fun isNightHour(hour: Int): Boolean = hour >= 18 || hour < 6

    fun resolvePalette(skin: AppSkin, schedule: ThemeSchedule, uiMode: Int, currentHour: Int): ThemePalette {
        val carDark = isDarkMode(uiMode)
        // The two automatic schedules are different answers to "who decides": the car's own
        // head-unit signal, or the clock. OR-ing them made both follow whichever said night first,
        // which is how "follow the car" turned dark at six in the evening with the car still in day.
        val isDark = when (schedule) {
            ThemeSchedule.AUTO_CAR -> carDark
            ThemeSchedule.AUTO_TIME -> isNightHour(currentHour)
            ThemeSchedule.MANUAL -> skin == AppSkin.NIGHT_DARK
        }
        if (isDark) {
            return if (skin == AppSkin.FLYME_WHITE) FLYME_DARK_PALETTE else NIGHT_DARK_PALETTE
        }
        return when (skin) {
            AppSkin.ZEEKR_WHITE -> ZEEKR_WHITE_PALETTE
            AppSkin.FLYME_WHITE -> FLYME_WHITE_PALETTE
            AppSkin.NIGHT_DARK -> NIGHT_DARK_PALETTE
        }
    }
}
