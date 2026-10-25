package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoStatsTelemetryTest {

    @Test
    fun readingTelemetryDoesNotResetDiagnosticCounters() {
        var now = 1_000_000_000L
        val stats = VideoStats(nanoTime = { now })
        stats.onReceived(1024)
        now = 1_033_000_000L
        stats.onRendered(now)

        val telemetry = stats.readCurrentTelemetry()
        assertNotNull(telemetry)

        // 模拟 5 秒窗口到达，验证 logIfDue 依然能拿到完整的 1 帧统计，未被 telemetry 清除
        now += 5_000_000_000L
        val line = stats.logIfDue()
        assertNotNull("logIfDue 必须照常生成报告", line)
        assertTrue("logIfDue 必须保留统计帧，未被遥测清空: $line", line!!.contains("rx=0.2fps") || line.contains("shown=0.2fps"))
    }

    @Test
    fun telemetryCalculatesFpsFromDeltas() {
        var now = 1_000_000_000L
        val stats = VideoStats(nanoTime = { now })

        // 模拟 1 秒内接收并渲染 30 帧
        for (i in 1..30) {
            now += 33_333_333L
            stats.onReceived(1000)
            stats.onRendered(now)
        }

        val telemetry = stats.readCurrentTelemetry()
        assertEquals(30.0f, telemetry.shownFps, 1.0f)
    }
}
