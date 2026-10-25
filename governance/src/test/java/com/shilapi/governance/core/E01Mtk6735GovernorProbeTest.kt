package com.shilapi.governance.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class E01Mtk6735GovernorProbeTest {

    @Test
    fun probeCommandIsUnderBudget() {
        val cmd = E01Mtk6735Governor.CMD_CHECK_NATP
        assertTrue("Probe command must be under 220 bytes budget", cmd.length <= 220)
        assertEquals("pidof natp", cmd)
    }

    @Test
    fun parseNatpAliveClassifiesOutputCorrectly() {
        assertTrue("PID output means natp is alive", E01Mtk6735Governor.parseNatpAlive("2341\n"))
        assertTrue("Multiple PIDs mean natp is alive", E01Mtk6735Governor.parseNatpAlive("1200 1201"))
        org.junit.Assert.assertFalse("Empty output means natp is dead", E01Mtk6735Governor.parseNatpAlive(""))
        org.junit.Assert.assertFalse("Whitespace output means natp is dead", E01Mtk6735Governor.parseNatpAlive("  \n"))
    }
}
