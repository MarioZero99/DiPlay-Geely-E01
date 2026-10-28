package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.DisconnectOrigin
import com.shilapi.xcertplay.transport.FirstFaultRecorder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketException

class CarPlayVpnServiceDiagnosticTest {
    @Test
    fun socketTimeoutMapsToTcpTimeout() {
        val recorder = FirstFaultRecorder()
        CarPlayVpnService.classifySocketFault(SocketException("Connection timed out"), recorder, 1000L)
        assertEquals(DisconnectOrigin.TCP_TIMEOUT, recorder.origin)
    }

    @Test
    fun socketResetMapsToTcpReset() {
        val recorder = FirstFaultRecorder()
        CarPlayVpnService.classifySocketFault(SocketException("Connection reset by peer"), recorder, 1000L)
        assertEquals(DisconnectOrigin.TCP_PEER_RESET, recorder.origin)
    }
}
