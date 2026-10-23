package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.WirelessHotspotMode

/**
 * Whether opening the app should try to switch the car's own hotspot on.
 *
 * Only the built-in car hotspot is switched. The other two modes bring their own access point up when
 * a session starts ([com.shilapi.xcertplay.orchestration.CarPlayController] does it in
 * `startWirelessHotspot`), so waking one at app start would either do nothing or start an access point
 * the driver is not using yet.
 *
 * Turning that hotspot on spends station mode. The switch is the driver asking for it; with the
 * switch off, opening the app leaves the radio alone.
 */
internal fun shouldWakeCarHotspotOnStartup(autoEnabled: Boolean, mode: WirelessHotspotMode): Boolean =
    autoEnabled && mode == WirelessHotspotMode.MANUAL
