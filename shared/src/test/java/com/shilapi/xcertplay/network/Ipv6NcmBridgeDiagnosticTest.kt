package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.DisconnectOrigin
import com.shilapi.xcertplay.transport.FirstFaultRecorder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class Ipv6NcmBridgeDiagnosticTest {
    @Test
    fun tunClosedMapsToTunClosedOrigin() {
        val recorder = FirstFaultRecorder()
        Ipv6NcmBridge.classifyAndRecordFault(
            source = "tun-read",
            error = IOException("NCM IPv6 tunnel closed"),
            recorder = recorder,
            nowMillis = 1000L,
        )
        assertEquals(DisconnectOrigin.TUN_CLOSED, recorder.origin)
    }

    @Test
    fun tunIoErrorMapsToTunIoErrorOrigin() {
        val recorder = FirstFaultRecorder()
        Ipv6NcmBridge.classifyAndRecordFault(
            source = "tun-write",
            error = IOException("EBADF (Bad file descriptor)"),
            recorder = recorder,
            nowMillis = 1000L,
        )
        assertEquals(DisconnectOrigin.TUN_IO_ERROR, recorder.origin)
    }
}
