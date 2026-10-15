package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * Whether opening the app should try to switch the car's own hotspot on.
 *
 * Only the built-in car hotspot is switched. The other two modes bring their own access point up when
 * a session starts ([com.shilapi.xcertplay.orchestration.CarPlayController] does it in
 * `startWirelessHotspot`), so waking one at app start would either do nothing or start an access point
 * the driver is not using yet.
 *
 * The driver's Wi-Fi is never spent on this: see
 * [com.shilapi.xcertplay.network.CarHotspotController.ensureEnabled]'s `spendDriverWifi`.
 */
internal fun shouldWakeCarHotspotOnStartup(autoEnabled: Boolean, mode: WirelessHotspotMode): Boolean =
    autoEnabled && mode == WirelessHotspotMode.MANUAL
