package com.shilapi.xcertplay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionFlowTest {
    @Test
    fun testConnectGateRespectsSessionIdle() {
        var idle = true
        val flow = InteractionFlow(isSessionIdle = { idle })

        assertTrue("空闲状态下允许发起连接", flow.canTriggerConnect())

        idle = false
        assertFalse("非空闲状态（Connecting或Live）严禁触发连接", flow.canTriggerConnect())
    }
}
