package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.WirelessHotspotMode

/**
 * Which wireless mode the page draws.
 *
 * The page and the connection have to agree on this: a page drawing the car hotspot while the
 * connection ran as the driver's own network is the shape this exists to remove. So the mode is
 * decided here, once, and both ask this — rather than the page working it out one way and
 * [DiPlayActivity.connect] reading storage another.
 *
 * [pendingSetup] means the driver has chosen the car hotspot and is being asked for its details, and
 * that is the mode the page has to draw, or the buttons that finish that job would not be on screen.
 * Storage is written at the same moment the choice is made, so this only decides the shape of the
 * page, never which route a connection takes behind the driver's back.
 *
 * A mode saved where it was offered can outlive being offered — storage keeps Wi-Fi Direct on
 * Android 10 and later, where the probe may still say the hotspot calls do not answer — so a saved
 * mode that is no longer on offer falls back to the first one that is, keeping a way back.
 */
internal fun displayedHotspotMode(
    pendingSetup: Boolean,
    saved: WirelessHotspotMode,
    offered: List<WirelessHotspotMode>,
): WirelessHotspotMode =
    if (pendingSetup) WirelessHotspotMode.MANUAL else saved.takeIf { it in offered } ?: offered.first()
