package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmUsbBridgeDiagnosticTest {
    @Test
    fun failSessionRegistersFaultOriginAndIncludesLivenessSnapshot() {
        val recorder = FirstFaultRecorder()
        val stats = WiredLivenessStats()
        stats.noteInbound(500, nowMillis = 1000L)
        stats.noteOutbound(100, nowMillis = 1100L)

        val diag = NcmUsbBridge.formatFailureSummary(
            message = "NCM write failed 3 times while the data path was live",
            origin = DisconnectOrigin.NCM_WRITE_EXHAUSTED,
            consecutiveWriteFailures = 3,
            queuedFrames = 12,
            queuedBytes = 4096,
            stats = stats,
            nowMillis = 1500L,
            recorder = recorder,
        )

        assertEquals(DisconnectOrigin.NCM_WRITE_EXHAUSTED, recorder.origin)
        assertTrue("Diag must contain origin", diag.contains("origin=NCM_WRITE_EXHAUSTED"))
        assertTrue("Diag must contain rx gap", diag.contains("gap 500ms"))
        assertTrue("Diag must contain tx gap", diag.contains("gap 400ms"))
        assertTrue("Diag must contain write failures", diag.contains("outFailures=3"))
    }
}
