package com.shilapi.xcertplay

/**
 * One clock for a group of facts that are only true together.
 *
 * The home page asks for the joined Wi-Fi name while the page is being built, then asks whether
 * wireless can start. Those used to share a timestamp that the name refreshed on its own, so the
 * readiness read believed the snapshot was fresh while hotspot and Bluetooth were still the empty
 * values left by the redraw. For the next two seconds a hotspot that was off did not block, and a
 * cold start said Bluetooth was off. One read fills every field, and the clock moves only then.
 */
internal class NetworkStatusSnapshot<T>(
    private val ttlMillis: Long,
    private val now: () -> Long,
    private val read: () -> T,
) {
    private var filled = false
    private var atElapsed = 0L
    private var value: T? = null

    fun invalidate() {
        filled = false
    }

    fun get(): T {
        val cached = value
        val t = now()
        if (filled && cached != null && t - atElapsed < ttlMillis) return cached
        val fresh = read()
        value = fresh
        filled = true
        atElapsed = t
        return fresh
    }
}

/** The radio facts the home page and the connect button both read. */
internal data class NetworkStatus(
    val joinedWifiName: String?,
    val hotspotOn: Boolean?,
    val hotspotCanBeReleased: Boolean,
    val carBluetoothOn: Boolean,
)
