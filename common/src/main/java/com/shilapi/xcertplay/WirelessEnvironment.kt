package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.WirelessHotspotMode

/** Why the radio is being aligned. Only the automatic reasons honour the failure cooldown. */
enum class WirelessSyncReason {
    APP_START,
    RESUME,
    MODE_SWITCH,
    PRE_CONNECT,
}

/** What the saved mode wants the single radio to be doing. */
enum class WirelessRadioTarget {
    HOTSPOT_ON,
    STATION_ON,
    LEAVE_ALONE,
}

internal fun wirelessRadioTarget(mode: WirelessHotspotMode): WirelessRadioTarget = when (mode) {
    WirelessHotspotMode.MANUAL -> WirelessRadioTarget.HOTSPOT_ON
    WirelessHotspotMode.EXISTING_WIFI -> WirelessRadioTarget.STATION_ON
    else -> WirelessRadioTarget.LEAVE_ALONE
}

internal fun clearsCooldown(reason: WirelessSyncReason): Boolean = when (reason) {
    WirelessSyncReason.MODE_SWITCH,
    WirelessSyncReason.PRE_CONNECT,
    -> true
    WirelessSyncReason.APP_START,
    WirelessSyncReason.RESUME,
    -> false
}

/** Cold start and resume wait out a recent failure. A mode change and Connect do not. */
internal fun cooldownBlocks(reason: WirelessSyncReason, bypassCooldown: Boolean, coolingDown: Boolean): Boolean =
    !bypassCooldown && coolingDown && !clearsCooldown(reason)

/** A live projection owns the radio. Opening the page must not switch it. */
internal fun sessionBlocksRadioChange(sessionActive: Boolean): Boolean = sessionActive

/**
 * The target is already true, so no command should be sent.
 *
 * Hotspot-on demands the hotspot be fully on (13) and station be fully off (1).
 * Station-on demands the hotspot be fully off (11) and station be fully on (3).
 */
internal fun radioAlreadyMatches(
    target: WirelessRadioTarget,
    hotspotFullyOn: Boolean,
    hotspotFullyOff: Boolean,
    stationFullyOn: Boolean,
    stationFullyOff: Boolean,
): Boolean = when (target) {
    WirelessRadioTarget.HOTSPOT_ON -> hotspotFullyOn && stationFullyOff
    WirelessRadioTarget.STATION_ON -> hotspotFullyOff && stationFullyOn
    WirelessRadioTarget.LEAVE_ALONE -> true
}

/** A worker started for [taskGeneration] may issue the next radio command only while it is still the latest. */
internal fun syncRequestStillCurrent(taskGeneration: Int, currentGeneration: Int): Boolean =
    taskGeneration == currentGeneration
