package com.shilapi.xcertplay

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The test tone must not outlive the page that started it.
 *
 * Tapping a stream type starts a 4.36s clip through a native `AudioTrack` and queues a callback to
 * release it 4.5s later. Leaving the app inside those 4.5 seconds destroyed the Activity first: the
 * release callback sat on a Handler that would never run again, and the track stayed held. That is
 * four and a half seconds of a native buffer per tap, on a head unit with 26 MB free.
 *
 * The two assertions before `destroy()` are the point of the test: without them the nulls after it
 * would also be green on a machine where the tone never started, and this would be a test that
 * passes because nothing happened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en")
class DiPlayLifecycleAudioTest {

    @Test
    fun `destroying the page releases the test tone and its callback`() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()

        activity.playTestToneForTest(1)

        assertNotNull("测试音得先真的建起来，否则清理断言等于空转", activity.testToneTrackForTest())
        assertNotNull("释放回调也得真的排上", activity.testToneStopForTest())

        controller.destroy()

        assertNull("onDestroy 必须把 Track 释放掉", activity.testToneTrackForTest())
        assertNull("onDestroy 必须把释放回调取消掉", activity.testToneStopForTest())
    }
}
