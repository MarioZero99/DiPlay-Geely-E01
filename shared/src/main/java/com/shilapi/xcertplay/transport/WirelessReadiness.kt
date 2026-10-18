package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.transport.adapter.WirelessAdapterStatus

/** Which Bluetooth radio a wireless connection is supposed to use. The user picks this. */
enum class WirelessBluetoothHop(val key: String) {
    USB_ADAPTER("usb_adapter"),
    CAR("car");

    companion object {
        fun fromKey(key: String?): WirelessBluetoothHop? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How a wireless connection is meant to reach the iPhone. The user picks this.
 *
 * Lives here, beside [WirelessBluetoothHop], because it is the same kind of thing — a choice the
 * driver makes — and because [WirelessReadinessCheck] has to read it to know which preconditions
 * apply. The check is in this module and may not reach into `orchestration`.
 */
enum class WirelessHotspotMode {
    LOCAL_ONLY_HOTSPOT,
    MANUAL,

    /**
     * Join a Wi-Fi network that already exists — the car is a station on it, and the iPhone joins the
     * same one. Nothing is created here, so there is nothing to configure: see
     * [com.shilapi.xcertplay.network.ExistingWifiManager].
     */
    EXISTING_WIFI,
}

/**
 * Why a wireless connection cannot start yet, in the terms the page puts in front of the driver.
 *
 * The radio choice is here rather than with the adapter because it is the driver's, not the radio's: a build
 * with no adapter still has the car's own radio to offer, and the page asks this either way.
 */
enum class WirelessGap {
    HOTSPOT_CREDENTIALS,
    HOTSPOT_OFF,

    /**
     * The car is running its own hotspot, this mode needs it off, and this build cannot switch it off
     * itself: one radio cannot be an access point and a station at once, and turning it off is then
     * the driver's job.
     *
     * Only reported when [WirelessReadinessInput.hotspotCanBeReleased] is false. Where DiPlay can
     * release the access point — the usual case — the same fact is not a gap, because the connect
     * path does it: see [WirelessReadinessInput.hotspotCanBeReleased].
     */
    HOTSPOT_ON,
    CAR_WIFI_DISCONNECTED,
    RADIO_NOT_CHOSEN,
    CAR_BLUETOOTH_OFF,
    PHONE_NOT_CHOSEN,
    DONGLE_NOT_READY,
}

data class WirelessReadinessInput(
    val credentialsSaved: Boolean,
    /**
     * Whether the car's own hotspot is running, or null where the head unit will not say.
     *
     * Nullable because the two routes want opposite things of this one fact and neither may be told
     * something the head unit never reported. A hotspot mode is missing its access point only when it
     * is *known* to be off; the same-network mode is blocked only when it is *known* to be on. One
     * boolean cannot say both, and collapsing the unknown case into either direction is a route that
     * can never connect on a head unit that keeps its access point to itself.
     */
    val hotspotOn: Boolean?,
    /**
     * Whether this build can switch the car's own hotspot off by itself.
     *
     * The same-network route needs the access point gone, and there are two ways it can go: the
     * connect path releases it ([com.shilapi.xcertplay.network.CarHotspotController.releaseForStationMode])
     * or the driver does it in the car settings. Which of the two applies is a fact about the
     * firmware, not about the route, so it is asked here rather than assumed: a gap that demands the
     * driver turn off a hotspot DiPlay was about to turn off is a gap that stops the very code which
     * would have satisfied it, and a build without the AP API that says nothing leaves the driver
     * waiting for a connection that cannot start.
     *
     * Deliberately has no default: a caller that has not asked the firmware cannot answer it.
     */
    val hotspotCanBeReleased: Boolean,
    val hop: WirelessBluetoothHop?,
    val phoneChosen: Boolean,
    val carBluetoothOn: Boolean,
    val adapterPlugged: Boolean,
    /**
     * The adapter's state as the page last saw it, or null when there is nothing to report. The adapter's own
     * vocabulary is deliberately not here: this file decides whether a wireless run may start, and that turns
     * on one fact about the radio — whether its flow is up — not on which step of its bring-up it is on.
     */
    val adapter: WirelessAdapterStatus?,
    /**
     * Which wireless route is being checked. The two hotspot gaps and [WirelessGap.HOTSPOT_ON] are
     * opposite requirements of the same radio, so which of them apply is decided here rather than by
     * whoever builds this value: a caller that forgot the mode used to disable both.
     */
    val mode: WirelessHotspotMode,
    /**
     * Whether the car is on a Wi-Fi network right now, as a station.
     *
     * Read by [WirelessGap.CAR_WIFI_DISCONNECTED], which only the same-network route produces: the
     * other routes build their own access point or drive the head unit's. Deliberately has no
     * default — a value that answers itself is a gap that can never be reported, which is how this
     * one came to be declared and never produced.
     */
    val carWifiAssociated: Boolean,
)

/**
 * What the page says about a route, in two lists rather than one.
 *
 * [gaps] is everything worth showing: a fact the driver should know about the route as it stands.
 * [blocking] is the subset that stops a connection from starting. They are separate because a fact
 * can be worth saying without being the driver's to fix — the car's own hotspot being on is the case
 * that forced this: the connect path releases it, so refusing over it stopped the very code that
 * would have cleared it, while saying nothing at all lost the only line that told the driver why
 * their Wi-Fi was about to go down.
 *
 * A caller that starts a connection reads [blocking] (or [ready]); a caller that draws reads [gaps].
 */
data class WirelessReadiness(
    val gaps: List<WirelessGap>,
    val blocking: List<WirelessGap>,
    val adapter: WirelessAdapterStatus?,
) {
    val ready: Boolean get() = blocking.isEmpty()
}

object WirelessReadinessCheck {
    fun assess(input: WirelessReadinessInput): WirelessReadiness {
        val gaps = mutableListOf<WirelessGap>()
        val blocking = mutableListOf<WirelessGap>()
        // Every gap is shown; the second argument decides whether it also refuses a connection.
        // Defaulting to "yes" keeps the ordinary preconditions — a missing radio, a phone that was
        // never chosen — reading as one thing rather than two.
        fun report(gap: WirelessGap, blocks: Boolean = true) {
            gaps += gap
            if (blocks) blocking += gap
        }
        // The car's own hotspot, read one way per route: the hotspot mode needs it up and configured,
        // the same-network mode needs it down. The local-only hotspot builds an access point of its own
        // and asks nothing of the car's — deliberately left alone here.
        when (input.mode) {
            WirelessHotspotMode.MANUAL -> {
                if (!input.credentialsSaved) report(WirelessGap.HOTSPOT_CREDENTIALS)
                // Only when it is known to be off: a head unit that will not report its access point
                // must not be told to turn on a hotspot that may well already be running.
                if (input.hotspotOn == false) report(WirelessGap.HOTSPOT_OFF)
            }
            WirelessHotspotMode.EXISTING_WIFI -> {
                // Shown whenever it is known: this route needs the access point gone, and the driver
                // is entitled to see that before their Wi-Fi disappears. It refuses the connection
                // only where DiPlay cannot release it — otherwise the release is the connect path's
                // job, and refusing here would demand of the driver exactly what the code that
                // follows was about to do.
                if (input.hotspotOn == true) {
                    report(WirelessGap.HOTSPOT_ON, blocks = !input.hotspotCanBeReleased)
                }
                // The access point and the station are one radio, so a car whose hotspot is on cannot
                // be associated with anything — and the connect path releases the hotspot before it
                // looks for the network. Blocking on the association as well was a deadlock: the only
                // way to satisfy it was to turn the hotspot off by hand, which is what the release
                // exists to avoid. With no hotspot holding the radio it is a genuine precondition,
                // and only the driver can meet it.
                if (!input.carWifiAssociated) {
                    report(WirelessGap.CAR_WIFI_DISCONNECTED, blocks = input.hotspotOn != true)
                }
            }
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> Unit
        }
        when (input.hop) {
            null -> report(WirelessGap.RADIO_NOT_CHOSEN)
            WirelessBluetoothHop.CAR -> {
                if (!input.carBluetoothOn) report(WirelessGap.CAR_BLUETOOTH_OFF)
                if (!input.phoneChosen) report(WirelessGap.PHONE_NOT_CHOSEN)
            }
            WirelessBluetoothHop.USB_ADAPTER -> {
                val status = input.adapter
                if (status == null || !status.flowEffective) report(WirelessGap.DONGLE_NOT_READY)
                else if (!input.phoneChosen) report(WirelessGap.PHONE_NOT_CHOSEN)
            }
        }
        val adapter = when {
            input.hop == WirelessBluetoothHop.USB_ADAPTER -> input.adapter
            input.adapterPlugged -> input.adapter
            else -> null
        }
        return WirelessReadiness(gaps, blocking, adapter)
    }
}
