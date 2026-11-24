package com.shilapi.xcertplay.instrument.fly

import com.shilapi.xcertplay.DiagSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// API 29 only so Robolectric's cached android-all jar can run `android.util.Log`; this class
// exercises no platform behaviour that the product's API 22 would answer differently.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class FlyTraceTest {

    /** Runs [FlyTrace.capture] on a canned dump and returns the lines it wrote. */
    private fun captureLines(which: String, dump: Pair<Int?, String>): List<String> {
        val lines = mutableListOf<String>()
        DiagSink.reset()
        DiagSink.attach { lines.add(it) }
        FlyTrace.dumpOverrideForTest = { dump }
        try {
            FlyTrace.capture(which)
        } finally {
            FlyTrace.dumpOverrideForTest = null
            DiagSink.reset()
        }
        return lines
    }

    @Test fun captureWritesFiveKeyLinesAndTheRawMatches() {
        val body = """
            11-10 13:30:06.400  1000  1234 D Extra:  @neunavi@  导航状态
            11-10 13:30:06.405  1000  1234 D Extra:  @neunavi@  setMMI_NavigationMod_indicator:[1, 1, 0]
            11-10 13:30:06.410  1000  1234 D Extra:  @neunavi@  road name chatter
        """.trimIndent()

        val lines = captureLines("open", 0 to body)

        assertTrue(lines.any { it.endsWith("fly.extra open 11-10 13:30:06.400  1000  1234 D Extra:  @neunavi@  导航状态") })
        assertTrue(lines.contains("xcertplay-fly: fly.extra 导航状态 hit"))
        assertTrue(lines.contains("xcertplay-fly: fly.extra mmi hit"))
        assertTrue(lines.contains("xcertplay-fly: fly.extra navi-front miss"))
        assertTrue(lines.contains("xcertplay-fly: fly.extra navi-back miss"))
        assertTrue(lines.contains("xcertplay-fly: fly.extra threefinger miss"))
    }

    @Test fun anEmptyDumpStillWritesFiveMisses() {
        val lines = captureLines("left", 0 to "")

        assertEquals(
            listOf(
                "xcertplay-fly: fly.extra 导航状态 miss",
                "xcertplay-fly: fly.extra mmi miss",
                "xcertplay-fly: fly.extra navi-front miss",
                "xcertplay-fly: fly.extra navi-back miss",
                "xcertplay-fly: fly.extra threefinger miss",
            ),
            lines,
        )
    }

    @Test fun aNonZeroExitCodeComesBeforeTheFiveMisses() {
        val lines = captureLines("open", 1 to "")

        assertEquals("xcertplay-fly: fly.extra logcat rc=1 body=", lines.first())
        assertEquals(
            listOf(
                "xcertplay-fly: fly.extra 导航状态 miss",
                "xcertplay-fly: fly.extra mmi miss",
                "xcertplay-fly: fly.extra navi-front miss",
                "xcertplay-fly: fly.extra navi-back miss",
                "xcertplay-fly: fly.extra threefinger miss",
            ),
            lines.drop(1),
        )
    }
    @Test fun commandFitsTheRootBridgeLimit() {
        // Same wrapper as RootShellBridge.wrap. The vendor binder rejects anything over 220 bytes.
        val wrapped = "{ ${FlyTrace.COMMAND}; } 2>&1; rc=\$?; echo __RC:\$rc"
        assertTrue(
            "wrapped command is ${wrapped.toByteArray(Charsets.UTF_8).size} bytes",
            wrapped.toByteArray(Charsets.UTF_8).size <= 220,
        )
    }

    @Test fun selectKeepsTheGateAndDropsTheRest() {
        val body = """
            11-10 13:30:06.400  1000  1234 D Extra:  @neunavi@  导航状态
            11-10 13:30:06.405  1000  1234 D Extra:  @neunavi@  setMMI_NavigationMod_indicator:[1, 1, 0]
            11-10 13:30:06.410  1000  1234 D Extra:  @neunavi@  road name chatter
            11-10 13:30:06.690  1000  1234 D Extra:  @ThreeFingerReceiver@  ThreeFingerReceiver isNavigation = true
            11-10 13:30:06.691  1000  1234 D Extra:  @ThreeFingerReceiver@  Settings.Global.getIn 恢复出厂设置值 = 1,isRadar = false, isEcall = false, isReversing = false
            11-10 13:30:06.692  1000  1234 D Extra:  @ThreeFingerReceiver@  ThreeFingerReceiver left
            11-10 13:30:06.693  1000  1234 D Extra:  @ThreeFingerReceiver@  ThreeFingerReceiver setInfo = [74, 1, 0]
            11-10 13:30:06.710  1000  3000 D MCU_UART: flood
        """.trimIndent()
        assertEquals(
            listOf(
                "11-10 13:30:06.400  1000  1234 D Extra:  @neunavi@  导航状态",
                "11-10 13:30:06.405  1000  1234 D Extra:  @neunavi@  setMMI_NavigationMod_indicator:[1, 1, 0]",
                "11-10 13:30:06.690  1000  1234 D Extra:  @ThreeFingerReceiver@  ThreeFingerReceiver isNavigation = true",
                "11-10 13:30:06.691  1000  1234 D Extra:  @ThreeFingerReceiver@  Settings.Global.getIn 恢复出厂设置值 = 1,isRadar = false, isEcall = false, isReversing = false",
                "11-10 13:30:06.692  1000  1234 D Extra:  @ThreeFingerReceiver@  ThreeFingerReceiver left",
                "11-10 13:30:06.693  1000  1234 D Extra:  @ThreeFingerReceiver@  ThreeFingerReceiver setInfo = [74, 1, 0]",
            ),
            FlyTrace.select(body),
        )
    }

    @Test fun anOldSetInfoDoesNotCountAsThisSwipe() {
        val before = """
            D Extra: ThreeFingerReceiver isNavigation = true
            D Extra: ThreeFingerReceiver left
            D Extra: ThreeFingerReceiver setInfo = [74, 1, 0, 0]
        """.trimIndent()
        assertEquals(PageProof.MISSING, FlyTrace.witness(before, before).proof)
    }

    @Test fun aNewLeftAndSetInfoOpens() {
        val before = "D Extra: ThreeFingerReceiver setInfo = [74, 1, 0, 0]"
        val after = before + """

            D Extra: ThreeFingerReceiver isNavigation = true
            D Extra: ThreeFingerReceiver left
            D Extra: ThreeFingerReceiver setInfo = [74, 1, 0, 0, 0]
        """.trimIndent()
        assertEquals(PageProof.OPENED, FlyTrace.witness(before, after).proof)
    }

    @Test fun navigationFalseIsDenied() {
        val after = "D Extra: ThreeFingerReceiver isNavigation = false"
        assertEquals(PageProof.DENIED, FlyTrace.witness("", after).proof)
    }

    @Test fun navigationTrueWithoutLeftIsDropped() {
        val after = """
            D Extra: ThreeFingerReceiver isNavigation = true
            D Extra: Settings.Global.getIn 恢复出厂设置值 = 1,isRadar = false, isEcall = false, isReversing = true
        """.trimIndent()
        assertEquals(PageProof.DROPPED, FlyTrace.witness("", after).proof)
    }

    @Test fun aNewRightAndSetInfoCloses() {
        val after = """
            D Extra: ThreeFingerReceiver isNavigation = true
            D Extra: ThreeFingerReceiver right
            D Extra: ThreeFingerReceiver setInfo = [74, 2, 0, 0]
        """.trimIndent()
        assertEquals(PageProof.CLOSED, FlyTrace.witness("", after).proof)
    }
}
