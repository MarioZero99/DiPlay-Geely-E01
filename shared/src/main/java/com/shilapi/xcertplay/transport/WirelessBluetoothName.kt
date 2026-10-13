package com.shilapi.xcertplay.transport

/**
 * What the accessory calls itself over Bluetooth, and the only place that name is written.
 *
 * Apple's specification requires the name carried in the iAP2 identification exchange to be the name the
 * radio broadcasts, so the iPhone knows one accessory rather than two; the wireless page shows the same
 * name for the same reason. The adapter stack reads it from here rather than keeping its own copy, which
 * is what makes the two agree by construction instead of by habit.
 */
object WirelessBluetoothName {
    const val ADVERTISED = "Xingrui CarPlay"
}
