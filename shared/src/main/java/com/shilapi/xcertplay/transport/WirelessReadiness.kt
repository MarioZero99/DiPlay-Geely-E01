package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.transport.adapter.WirelessAdapterStatus

/** Which Bluetooth radio a wireless connection is supposed to use. The user picks this. */
enum class WirelessBluetoothHop(val key: String) {
    USB_ADAPTER("usb_adapter"),
    CAR("car");

    companion object {
        fun fromKey(key: String?): WirelessBluetoothHop? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How a wireless connection is meant to reach the iPhone. The user picks this.
 *
 * Lives here, beside [WirelessBluetoothHop], because it is the same kind of thing — a choice the
 * driver makes — and because [WirelessReadinessCheck] has to read it to know which preconditions
 * apply. The check is in this module and may not reach into `orchestration`.
 */
enum class WirelessHotspotMode {
    WIFI_P2P,
    LOCAL_ONLY_HOTSPOT,
    MANUAL,

    /**
     * Join a Wi-Fi network that already exists — the car is a station on it, and the iPhone joins the
     * same one. Nothing is created here, so there is nothing to configure: see
     * [com.shilapi.xcertplay.network.ExistingWifiManager].
     */
    EXISTING_WIFI,
}

/**
 * Why a wireless connection cannot start yet, in the terms the page puts in front of the driver.
 *
 * The radio choice is here rather than with the adapter because it is the driver's, not the radio's: a build
 * with no adapter still has the car's own radio to offer, and the page asks this either way.
 */
enum class WirelessGap {
    HOTSPOT_CREDENTIALS,
    HOTSPOT_OFF,
    RADIO_NOT_CHOSEN,
    CAR_BLUETOOTH_OFF,
    PHONE_NOT_CHOSEN,
    DONGLE_NOT_READY,
}

data class WirelessReadinessInput(
    val credentialsSaved: Boolean,
    val hotspotOn: Boolean,
    val hop: WirelessBluetoothHop?,
    val phoneChosen: Boolean,
    val carBluetoothOn: Boolean,
    val adapterPlugged: Boolean,
    /**
     * The adapter's state as the page last saw it, or null when there is nothing to report. The adapter's own
     * vocabulary is deliberately not here: this file decides whether a wireless run may start, and that turns
     * on one fact about the radio — whether its flow is up — not on which step of its bring-up it is on.
     */
    val adapter: WirelessAdapterStatus?,
)

data class WirelessReadiness(
    val gaps: List<WirelessGap>,
    val adapter: WirelessAdapterStatus?,
) {
    val ready: Boolean get() = gaps.isEmpty()
}

object WirelessReadinessCheck {
    fun assess(input: WirelessReadinessInput): WirelessReadiness {
        val gaps = mutableListOf<WirelessGap>()
        if (!input.credentialsSaved) gaps += WirelessGap.HOTSPOT_CREDENTIALS
        if (!input.hotspotOn) gaps += WirelessGap.HOTSPOT_OFF
        when (input.hop) {
            null -> gaps += WirelessGap.RADIO_NOT_CHOSEN
            WirelessBluetoothHop.CAR -> {
                if (!input.carBluetoothOn) gaps += WirelessGap.CAR_BLUETOOTH_OFF
                if (!input.phoneChosen) gaps += WirelessGap.PHONE_NOT_CHOSEN
            }
            WirelessBluetoothHop.USB_ADAPTER -> {
                val status = input.adapter
                if (status == null || !status.flowEffective) gaps += WirelessGap.DONGLE_NOT_READY
                else if (!input.phoneChosen) gaps += WirelessGap.PHONE_NOT_CHOSEN
            }
        }
        val adapter = when {
            input.hop == WirelessBluetoothHop.USB_ADAPTER -> input.adapter
            input.adapterPlugged -> input.adapter
            else -> null
        }
        return WirelessReadiness(gaps, adapter)
    }
}
