package com.shilapi.xcertplay.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Picks the port the AirPlay listener binds.
 *
 * 7000 is the port AirPlay conventionally uses, and on a head unit it is also a port something else can be
 * holding — a DiPlay process that did not exit, or another app. Failing to bind it used to end the whole wireless
 * attempt. The iPhone is told the port in the Bonjour record and in the iAP2 Wi-Fi configuration, so any free
 * port reaches us just as well; only the port number changes.
 */
object AirPlayPorts {
    fun choose(
        requested: Int,
        isFree: (Int) -> Boolean = { port -> AirPlayPorts.isFree(port) },
        freePort: () -> Int = { ephemeralPort() },
    ): Int = if (isFree(requested)) requested else freePort()

    fun isFree(port: Int, address: InetAddress? = null): Boolean = runCatching {
        ServerSocket().use { it.bind(InetSocketAddress(address, port)) }
    }.isSuccess

    private fun ephemeralPort(): Int = ServerSocket(0).use { it.localPort }
}
