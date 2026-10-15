package com.shilapi.xcertplay.carhop

import android.content.Context
import com.shilapi.xcertplay.transport.Iap2BluetoothLink

/**
 * What [CarBluetoothHop] has to do on top of what the connection page asks of it: open the session's
 * Bluetooth leg. Internal because [Iap2BluetoothLink] is, and only the transport ever needs it.
 */
internal interface CarBluetoothTransportHop : CarBluetoothHop {
    /** Opens the iAP2 stream to [address]. Throws [java.io.IOException] on failure. */
    fun link(
        context: Context,
        configuredAddress: String?,
        localAddress: String?,
        onDiagnostic: (String) -> Unit,
    ): Iap2BluetoothLink
}

/**
 * Finds this build's Bluetooth hop, if it has one.
 *
 * The implementation is named here and loaded by name rather than compiled in, so that one source tree
 * serves both: a build that puts the class on the classpath gets the hop, and every other build — the
 * tree as published, CI, a release without it — loads nothing and stays on the AOSP stack. Naming a
 * class that nothing defines is the ordinary case, not an error.
 */
object CarBluetoothHops {
    private const val VENDOR_HOP_CLASS = "com.shilapi.xcertplay.carhop.VendorCarHop"

    @Volatile
    private var resolved = false

    @Volatile
    private var hop: CarBluetoothHop? = null

    @Volatile
    private var overridden = false

    @Volatile
    private var testHop: CarBluetoothHop? = null

    /** This build's hop, or null when it has none. */
    val active: CarBluetoothHop?
        get() = if (overridden) testHop else load()

    /**
     * Puts a hop in place of whatever this build has, for the rest of the process. Passing null says
     * this build has none.
     *
     * For tests, and needed rather than nice, for the reason
     * [com.shilapi.xcertplay.transport.adapter.AdapterRoutes] gives for its own: a build that carries
     * the hop has it on the test classpath, so without this every test that reaches the app's own
     * start-up would pass or fail according to whether the machine that ran it was given
     * `-PvendorHopDir`, and a suite that is green either way is evidence of nothing.
     */
    fun override(hop: CarBluetoothHop?) {
        testHop = hop
        overridden = true
    }

    /** Gives this build its own answer back, after [override]. */
    fun resetOverride() {
        overridden = false
        testHop = null
    }

    /** This build's hop, when it can open the session's Bluetooth leg as well. */
    internal val transport: CarBluetoothTransportHop?
        get() = active as? CarBluetoothTransportHop

    @Synchronized
    private fun load(): CarBluetoothHop? {
        if (resolved) return hop
        hop = try {
            Class.forName(VENDOR_HOP_CLASS).getDeclaredConstructor().newInstance() as CarBluetoothHop
        } catch (_: ClassNotFoundException) {
            null
        }
        resolved = true
        return hop
    }
}
