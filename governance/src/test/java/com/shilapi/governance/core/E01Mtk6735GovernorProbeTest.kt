package com.shilapi.governance.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class E01Mtk6735GovernorProbeTest {

    @Test
    fun probeCommandIsUnderBudgetAndUsesSystemProperty() {
        val cmd = E01Mtk6735Governor.CMD_CHECK_NATP
        assertTrue("Probe command must be under 220 bytes budget", cmd.length <= 220)
        assertEquals("getprop init.svc.run_natp", cmd)
    }

    @Test
    fun parseNatpAliveClassifiesInitServiceStatusCorrectly() {
        assertEquals(true, E01Mtk6735Governor.parseNatpAlive("running\n"))
        // "stopping" is in teardown phase and must not be reported as alive
        assertEquals(false, E01Mtk6735Governor.parseNatpAlive("stopping\n"))
        assertEquals(false, E01Mtk6735Governor.parseNatpAlive("stopped\n"))
        // Empty, whitespace, or missing property value must be UNKNOWN (null), NOT falsely reported as dead (false)
        assertNull(E01Mtk6735Governor.parseNatpAlive(""))
        assertNull(E01Mtk6735Governor.parseNatpAlive("  \n"))
        assertNull(E01Mtk6735Governor.parseNatpAlive("unknown_state"))
        // Fallback PID or process listing support
        assertEquals(true, E01Mtk6735Governor.parseNatpAlive("2341\n"))
        assertEquals(true, E01Mtk6735Governor.parseNatpAlive("root 2341 1 /system/bin/natp\n"))
    }
}
