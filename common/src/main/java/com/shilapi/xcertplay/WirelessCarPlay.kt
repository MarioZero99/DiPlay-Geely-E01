package com.shilapi.xcertplay

/**
 * Wireless CarPlay stays available on every head unit, including the Geely E01 (FS11GQJ).
 *
 * Which of those paths this head unit can run is [DeviceConnectionSupport]. The E01 keeps the
 * wireless entry, which is the car hotspot or the network the car is already on.
 */
object WirelessCarPlay {
    val uiOffered: Boolean
        get() = true
}
