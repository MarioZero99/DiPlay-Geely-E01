package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric because [VideoStats.logIfDue] ends in [android.util.Log]; under plain JUnit the platform
 * call throws "not mocked" before the line under test is ever built.
 *
 * The format string carries twelve placeholders. One missing argument is a
 * `MissingFormatArgumentException` thrown on the decoder thread, which ends the session — so this is a
 * guard, not a display test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoStatsLineTest {

    @Before fun reset() = TouchLatencyProbe.resetForTest()

    @Test fun theMainLineCarriesEveryTouchSegmentExactlyOnce() {
        var now = 1_000_000_000L
        val stats = VideoStats(nanoTime = { now })
        TouchLatencyProbe.onTouchSent(originNs = now, sendDurationNs = 3_000_000L)
        stats.onReceived(1_000)              // 收包样本：0ms
        now = 1_060_000_000L
        stats.onRendered(now)                // 上屏样本：60ms
        TouchLatencyProbe.onQueueDelay(20_000_000L)
        now = 6_100_000_000L                 // 过了一个 5 秒窗口

        val line = stats.logIfDue()
        assertNotNull(line)
        assertTrue(line!!.contains("touch2frame avg=0ms"))
        assertTrue(line.contains("touch2render avg=60ms"))
        assertTrue(line.contains("touchSendMax=3ms"))
        assertTrue(line.contains("touchQueue avg=20ms"))
        assertTrue(line.contains("input avg=-1ms"))   // 没有输入样本时是 -1，不是 0
    }

    @Test fun theSecondStreamNeverEatsTheTouchSamples() {
        var now = 1_000_000_000L
        val cluster = VideoStats(label = " stream=111", nanoTime = { now })
        TouchLatencyProbe.onTouchSent(originNs = now, sendDurationNs = 1_000_000L)
        cluster.onReceived(1_000)
        now = 1_200_000_000L
        cluster.onRendered(now)

        // 样本还在：副屏既没在 onReceived 也没在 onRendered 里取走它。
        // 如果它被吃掉，这里会得到 -1。
        assertEquals(200_000_000L, TouchLatencyProbe.onRendered(now))
    }

    @Test fun concurrentReceivedAndRenderedDoNotDeadlockOrCorrupt() {
        var clock = 10_000_000_000L
        val stats = VideoStats(nanoTime = { clock })
        val threads = (1..4).map {
            Thread {
                repeat(100) {
                    stats.onReceived(100)
                    stats.onRendered(clock)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        clock += 6_000_000_000L
        val line = stats.logIfDue()
        assertNotNull(line)
        assertTrue(line!!.contains("rx="))
    }
}

