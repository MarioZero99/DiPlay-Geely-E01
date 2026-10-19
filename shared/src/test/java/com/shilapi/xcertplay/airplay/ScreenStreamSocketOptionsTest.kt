package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class ScreenStreamSocketOptionsTest {
    @Test
    fun videoSocketConfiguresReceiveBufferSizeAndTcpNoDelay() {
        val server = ServerSocket(0)
        val client = Socket("localhost", server.localPort)
        val accepted = server.accept()
        ScreenStream.configureSocket(accepted)
        assertTrue(accepted.tcpNoDelay)
        assertTrue(accepted.receiveBufferSize >= 64 * 1024)
        client.close()
        accepted.close()
        server.close()
    }
}
