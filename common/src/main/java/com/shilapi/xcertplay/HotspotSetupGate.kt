package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.WirelessHotspotMode

/**
 * Whether "the hotspot details are not saved yet" should refuse this connection.
 *
 * Only the car-hotspot mode. That flag is the car-hotspot route's own half-finished state — the driver
 * chose it and has not filled in its name and password — and the same-network route has nothing to do
 * with that pair. The flag used to be read with no mode at all, so a half-finished car hotspot refused
 * a connection that was going to run over the driver's own Wi-Fi, and sent them to a settings page
 * carrying a sentence about the car's hotspot.
 */
internal fun hotspotSetupBlocksConnect(mode: WirelessHotspotMode, setupPending: Boolean): Boolean =
    setupPending && mode == WirelessHotspotMode.MANUAL
