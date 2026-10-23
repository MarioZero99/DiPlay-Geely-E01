package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.WirelessHotspotMode

/** Why the radio is being aligned. Only the automatic reasons honour the failure cooldown. */
enum class WirelessSyncReason {
    APP_START,
    RESUME,
    MODE_SWITCH,
    AUTO_HOTSPOT_ON,
    PRE_CONNECT,
}

/** What the saved mode wants the single radio to be doing. */
enum class WirelessRadioTarget {
    HOTSPOT_ON,
    STATION_ON,
    LEAVE_ALONE,
}

internal fun wirelessRadioTarget(mode: WirelessHotspotMode, autoHotspot: Boolean): WirelessRadioTarget = when {
    mode == WirelessHotspotMode.MANUAL && autoHotspot -> WirelessRadioTarget.HOTSPOT_ON
    mode == WirelessHotspotMode.EXISTING_WIFI -> WirelessRadioTarget.STATION_ON
    else -> WirelessRadioTarget.LEAVE_ALONE
}

internal fun clearsCooldown(reason: WirelessSyncReason): Boolean = when (reason) {
    WirelessSyncReason.MODE_SWITCH,
    WirelessSyncReason.AUTO_HOTSPOT_ON,
    WirelessSyncReason.PRE_CONNECT,
    -> true
    WirelessSyncReason.APP_START,
    WirelessSyncReason.RESUME,
    -> false
}

/** Cold start and resume wait out a recent failure. A mode change, the auto switch, and Connect do not. */
internal fun cooldownBlocks(reason: WirelessSyncReason, bypassCooldown: Boolean, coolingDown: Boolean): Boolean =
    !bypassCooldown && coolingDown && !clearsCooldown(reason)

/** A live projection owns the radio. Opening the page must not switch it. */
internal fun sessionBlocksRadioChange(sessionActive: Boolean): Boolean = sessionActive

/**
 * The target is already true, so no command should be sent.
 *
 * Hotspot-on does not also demand that station be off: on this radio the hotspot being up means
 * station is already down. Station-on demands the hotspot be fully off, not merely "not enabled".
 */
internal fun radioAlreadyMatches(
    target: WirelessRadioTarget,
    hotspotFullyOn: Boolean,
    hotspotFullyOff: Boolean,
    stationOn: Boolean,
): Boolean = when (target) {
    WirelessRadioTarget.HOTSPOT_ON -> hotspotFullyOn
    WirelessRadioTarget.STATION_ON -> hotspotFullyOff && stationOn
    WirelessRadioTarget.LEAVE_ALONE -> true
}

/** A worker started for [taskGeneration] may issue the next radio command only while it is still the latest. */
internal fun syncRequestStillCurrent(taskGeneration: Int, currentGeneration: Int): Boolean =
    taskGeneration == currentGeneration
