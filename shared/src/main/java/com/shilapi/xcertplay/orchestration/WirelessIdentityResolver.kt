package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2BluetoothLink
import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import java.io.IOException

/**
 * Resolves the Bluetooth radio identity for iAP2 identification, ensuring strict compliance
 * with Apple Bluetooth Accessories Spec §18.4 (EIR LocalName == IdentificationInformation.Name).
 */
internal object WirelessIdentityResolver {
    /**
     * Resolves the Bluetooth broadcast name matching the EIR emitted by the active RFCOMM link.
     *
     * @param link The active iAP2 Bluetooth link selected for this wireless run.
     * @param hopType The wireless hop type configured (USB_ADAPTER vs CAR).
     * @param fallbackCarHopName An optional name queried from [com.shilapi.xcertplay.carhop.CarBluetoothHop]
     *        when the link is a legacy car hop that does not implement [Iap2BluetoothLink.localName].
     * @return Non-empty broadcast name.
     * @throws IOException with a precise diagnostic message when the name cannot be determined.
     */
    fun resolveRadioName(
        link: Iap2BluetoothLink,
        hopType: WirelessBluetoothHop,
        fallbackCarHopName: String? = null,
    ): String {
        val candidate = link.localName?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackCarHopName?.trim()?.takeIf { it.isNotEmpty() }

        if (!candidate.isNullOrBlank()) {
            return candidate
        }

        throw missingNameException(hopType)
    }

    /**
     * Why a run cannot name itself, for the caller that has to ask before the rest of the bring-up.
     *
     * Exposed rather than copied: the same condition reached from two places — once as a pre-flight
     * check before the hotspot, once at the moment identification is built — must read as one fault,
     * or the log ends up with two accounts of the same missing name.
     */
    fun missingNameException(hopType: WirelessBluetoothHop): IOException = when (hopType) {
        WirelessBluetoothHop.USB_ADAPTER -> IOException(
            "The USB Bluetooth adapter did not report its broadcast name. " +
                "Apple §18.4 requires EIR LocalName to match IdentificationInformation."
        )
        WirelessBluetoothHop.CAR -> IOException(
            "The head unit would not say what its Bluetooth is called, and the name the phone " +
                "is given has to be that one (§18.4). Wireless CarPlay cannot start."
        )
    }
}
