package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.transport.adapter.AdapterRoutes

/** The RFCOMM hop to the iPhone, however this head unit can reach it. */
internal interface Iap2BluetoothLink {
    /** This head unit's own Bluetooth address; null when no adapter can supply one. */
    val localAddress: String?

    /** The broadcast name of the radio carrying this iAP2 link, matching its EIR (§18.4). */
    val localName: String?
        get() = null

    /** The iPhone to open the iAP2 channel to; null means nothing is paired yet. */
    fun target(): PairedTarget?

    /** Connects and returns the iAP2 byte stream. Throws [java.io.IOException] on failure. */
    fun open(): BlockingDuplexByteStream

    /**
     * Drops this hop's radio once Wi-Fi has the session.
     * The car's own Bluetooth does nothing here; only the USB adapter has a second radio to quiet.
     */
    fun idleAfterSession() {}
}

/** The iAP2 service record every CarPlay accessory publishes over RFCOMM. */
internal const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"

/** Picks the hop a wireless run uses. The caller passes the radio the user chose. */
internal object Iap2BluetoothLinks {
    /**
     * [WirelessBluetoothHop.USB_ADAPTER] stays on the adapter even when nothing is paired yet. With
     * [externalRouteEnabled] off, the call fails instead of using the car radio. An adapter that is not
     * running also fails here: there is no silent fallback. [WirelessBluetoothHop.CAR] does not look at
     * the adapter.
     *
     * The route switch is a parameter rather than a direct read of [AdapterRoutes] so that both
     * sides of it are reachable from a test.
     */
    fun chooseForWireless(
        hop: WirelessBluetoothHop,
        host: AdapterBluetoothHost?,
        configuredAddress: String?,
        onDiagnostic: (String) -> Unit = {},
        externalRouteEnabled: Boolean = AdapterRoutes.available,
        fallbackAdapterName: String? = null,
        car: () -> Iap2BluetoothLink,
    ): Iap2BluetoothLink {
        if (hop == WirelessBluetoothHop.USB_ADAPTER) {
            if (!externalRouteEnabled) {
                throw java.io.IOException("External Bluetooth route is off")
            }
            val adapter = host ?: throw java.io.IOException(
                "External Bluetooth was selected, but the adapter is not running",
            )
            val paired = adapter.pairedTarget()?.address ?: "unpaired"
            onDiagnostic("wireless Bluetooth hop=usb-adapter address=$paired")
            return AdapterIap2BluetoothLink(adapter, configuredAddress, fallbackAdapterName)
        }
        onDiagnostic("wireless Bluetooth hop=car chosen")
        return car()
    }
}

