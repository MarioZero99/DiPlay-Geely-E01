package com.shilapi.xcertplay.instrument.fly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlyTraceTest {
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
                "11-10 13:30:06.690  1000  1234 D Extra:  @ThreeFingerReceiver@  ThreeFingerReceiver isNavigation = true",
                "11-10 13:30:06.691  1000  1234 D Extra:  @ThreeFingerReceiver@  Settings.Global.getIn 恢复出厂设置值 = 1,isRadar = false, isEcall = false, isReversing = false",
                "11-10 13:30:06.692  1000  1234 D Extra:  @ThreeFingerReceiver@  ThreeFingerReceiver left",
                "11-10 13:30:06.693  1000  1234 D Extra:  @ThreeFingerReceiver@  ThreeFingerReceiver setInfo = [74, 1, 0]",
            ),
            FlyTrace.select(body),
        )
    }
}
