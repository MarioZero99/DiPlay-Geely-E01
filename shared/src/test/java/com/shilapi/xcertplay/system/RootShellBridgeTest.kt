package com.shilapi.xcertplay.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellBridgeTest {

    @Test
    fun wrapsCommandWithExitCodeMarker() {
        val wrapped = RootShellBridge.wrap("/system/bin/svc wifi enable")
        assertTrue(wrapped.contains("/system/bin/svc wifi enable"))
        assertTrue(wrapped.contains("__RC:"))
        assertTrue(wrapped.contains("rc=\$?"))
    }

    @Test
    fun rejectsUnsafeMultilineCommands() {
        assertFalse(RootShellBridge.execute("line1\nline2"))
        assertFalse(RootShellBridge.execute("line1\rline2"))
    }

    @Test
    fun classifiesOutputCorrectly() {
        assertTrue(RootShellBridge.classifyOutput("some output\n__RC:0\n"))
        assertFalse(RootShellBridge.classifyOutput("some output\n__RC:1\n"))
        assertFalse(RootShellBridge.classifyOutput("some output\n__RC:127\n"))
        assertFalse(RootShellBridge.classifyOutput("malformed without marker"))
    }

    @Test
    fun returnsFalseWhenServiceUnavailable() {
        // In unit test environment, ExtraUtilsService is not present
        assertFalse(RootShellBridge.execute("/system/bin/id"))
    }

    @Test
    fun hotspotServiceCallCommandFormatsExpectedBinderInvocation() {
        val enableCmd = RootShellBridge.hotspotServiceCallCommand(code = 28, enable = true)
        assertEquals("/system/bin/service call wifi 28 i32 0 i32 1", enableCmd)
        assertTrue(RootShellBridge.wrap(enableCmd).toByteArray(Charsets.UTF_8).size <= 220)

        val disableCmd = RootShellBridge.hotspotServiceCallCommand(code = 28, enable = false)
        assertEquals("/system/bin/service call wifi 28 i32 0 i32 0", disableCmd)
        assertTrue(RootShellBridge.wrap(disableCmd).toByteArray(Charsets.UTF_8).size <= 220)
    }

    @Test
    fun wifiServiceCommandFormatsSvcWifiInvocation() {
        assertEquals("/system/bin/svc wifi enable", RootShellBridge.wifiServiceCommand(true))
        assertEquals("/system/bin/svc wifi disable", RootShellBridge.wifiServiceCommand(false))
    }

    @Test
    fun getWifiApTransactionCodeGracefullyHandlesMissingFrameworkClass() {
        RootShellBridge.setWifiApTransactionCodeForTesting(null)
        val code = RootShellBridge.getWifiApTransactionCode()
        // In JVM test without android framework stub, returns null or injected value
        assertEquals(null, code)

        RootShellBridge.setWifiApTransactionCodeForTesting(32)
        assertEquals(32, RootShellBridge.getWifiApTransactionCode())
    }
}
