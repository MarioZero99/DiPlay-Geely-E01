package com.shilapi.governance.core

import com.shilapi.governance.platform.E01PrivilegedBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class E01Mtk6735GovernorTest {

    @Test
    fun verifiesSuppressionCommandLengthAndSafety() {
        val rawCmd = E01Mtk6735Governor.CMD_SUPPRESS
        val wrapped = E01PrivilegedBridge.wrap(rawCmd)
        val rawBytes = rawCmd.toByteArray(Charsets.UTF_8).size
        val wrappedBytes = wrapped.toByteArray(Charsets.UTF_8).size

        assertEquals("setprop ctl.stop run_natp; pm disable-user --user 0 com.ecarx.xiaoka", rawCmd)
        assertEquals(68, rawBytes)
        assertEquals(114, wrappedBytes)
        assertTrue("Wrapped suppression command must fit within 220-byte limit", wrappedBytes <= 220)
    }

    @Test
    fun verifiesRestorationCommandLengthAndSafety() {
        val rawCmd = E01Mtk6735Governor.CMD_RESTORE
        val wrapped = E01PrivilegedBridge.wrap(rawCmd)
        val rawBytes = rawCmd.toByteArray(Charsets.UTF_8).size
        val wrappedBytes = wrapped.toByteArray(Charsets.UTF_8).size

        assertEquals("pm enable com.ecarx.xiaoka; setprop ctl.start run_natp", rawCmd)
        assertEquals(54, rawBytes)
        assertEquals(100, wrappedBytes)
        assertTrue("Wrapped restoration command must fit within 220-byte limit", wrappedBytes <= 220)
    }

    @Test
    fun verifiesSelfHealCommandLengthAndSafety() {
        val rawCmd = E01Mtk6735Governor.CMD_SELF_HEAL
        val wrapped = E01PrivilegedBridge.wrap(rawCmd)
        val rawBytes = rawCmd.toByteArray(Charsets.UTF_8).size
        val wrappedBytes = wrapped.toByteArray(Charsets.UTF_8).size

        assertEquals("pm enable com.ecarx.xiaoka", rawCmd)
        assertEquals(26, rawBytes)
        assertEquals(72, wrappedBytes)
        assertTrue("Wrapped self-heal command must fit within 220-byte limit", wrappedBytes <= 220)
    }
}
