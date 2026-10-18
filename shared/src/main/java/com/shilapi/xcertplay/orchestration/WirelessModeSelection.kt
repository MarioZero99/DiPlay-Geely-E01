package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.WirelessHotspotMode
import android.os.Build

/**
 * The wireless hotspot mode a bring-up will actually use on a platform at [sdkInt].
 *
 * Every mode that needs a newer platform than this project's floor falls back to one that works
 * there, and each floor is real: joining a network already in progress needs `ConnectivityManager`'s
 * per-network view (Android 5), and LocalOnlyHotspot arrived in Android 8 — which below it is served
 * by the car hotspot that does exist.
 *
 * This is a function rather than an inline `when` because of how the downgrade used to be written:
 * it covered every mode that was not MANUAL, so on the head unit this fork targets a newly added
 * mode was replaced by MANUAL with nothing said. The stored setting still read back as itself — only
 * the run was different — which is a failure that looks exactly like the mode never having been
 * implemented at all.
 */
internal fun effectiveHotspotMode(configured: WirelessHotspotMode, sdkInt: Int): WirelessHotspotMode =
    when {
        configured == WirelessHotspotMode.EXISTING_WIFI && sdkInt < Build.VERSION_CODES.LOLLIPOP ->
            WirelessHotspotMode.MANUAL
        configured == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT && sdkInt < Build.VERSION_CODES.O ->
            WirelessHotspotMode.MANUAL
        else -> configured
    }
