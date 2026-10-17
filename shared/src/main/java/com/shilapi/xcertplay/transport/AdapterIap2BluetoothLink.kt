package com.shilapi.xcertplay.transport

import java.io.IOException

/**
 * The RFCOMM hop through the Actions USB adapter, for head units whose own Bluetooth stack cannot carry it.
 *
 * Borrows the adapter's HCI link; owns none of it. Closing the byte stream ends the RFCOMM channel and the iAP2
 * session, but the USB interface and the HCI event loop stay up — reconnecting should not need a new pairing —
 * so nothing here is [java.io.Closeable]. The interface is released by whoever claimed it, which is the
 * connection page.
 */
internal class AdapterIap2BluetoothLink(
    private val host: AdapterBluetoothHost,
    private val configuredAddress: String? = null,
    private val fallbackName: String? = null,
) : Iap2BluetoothLink {
    override val localAddress: String?
        get() = host.localAddress

    override val localName: String?
        get() = host.localName ?: fallbackName

    override fun target(): PairedTarget? {
        val paired = host.pairedTarget() ?: return null
        val selected = configuredAddress ?: return paired
        if (paired.address.equals(selected, ignoreCase = true)) return paired
        throw IOException("The selected iPhone is no longer paired. Choose it again in DiPlay.")
    }

    override fun open(): BlockingDuplexByteStream = host.openStream()

    override fun idleAfterSession() = host.idleAfterSession()
}
