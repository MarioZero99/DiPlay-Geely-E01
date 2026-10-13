package com.shilapi.xcertplay.transport.adapter

import android.content.Context
import com.shilapi.xcertplay.transport.AdapterBluetoothHost
import java.io.Closeable

/**
 * The adapter stack, as the pages use it. Everything here is implemented outside this tree; only the shape
 * is here, because the pages hold it.
 *
 * A page-level session rather than a singleton: the connection page and the settings page each bring their
 * own up, and only one of them may hold the USB interface at a time. The settings page releases its own
 * before the connection page takes the interface over.
 */
interface AdapterPage : Closeable {
    /** The last state [refresh] produced. */
    val status: WirelessAdapterStatus

    /**
     * Brings the session up and reports where it stands.
     *
     * [carHotspotOn] is what the page knows of the car's hotspot. The radio stays undiscoverable while that
     * hotspot is known to be off — the iPhone is not asked to find a name it cannot use yet — and a firmware
     * that hides the state reports null, which must not block pairing.
     */
    fun refresh(carHotspotOn: Boolean?): WirelessAdapterStatus

    /** The open stack, for the session to open its iAP2 channel through, or null while it is not up. */
    val host: AdapterBluetoothHost?

    /** True once bring-up has finished, successfully or not, so a waiting page may stop waiting. */
    val bringUpFinished: Boolean

    /** Lets the next refresh turn scan back on. Does not send a command by itself. */
    fun resumeDiscovery()

    /** Blocks until the stick is released. Call this off the UI thread. */
    fun releaseAndWait()
}

/** A build's adapter stack, if it has one. Stateless: pages come from [page]. */
interface AdapterRoute {
    /**
     * Whether a radio is on the bus. Reads the bus, so it costs a USB enumeration and a page that asks on
     * every redraw should hold a [AdapterPage] and read its status instead.
     */
    fun present(context: Context): Boolean

    /**
     * A session for one page.
     *
     * [onStatus] is called on the main thread for every transition, with what the page should show — the
     * connection page repaints from it while it is waiting for the radio, and reads the end of a bring-up
     * from it. [onLine] is called for every transition and diagnostic too, from whichever thread the stack
     * reports on, with the line the adapter would write for it; the connection page puts that in the
     * handshake log. The line is separate from the status because it is the adapter's own wording about its
     * own steps, and a page has no business building it.
     */
    fun page(
        context: Context,
        onStatus: (WirelessAdapterStatus) -> Unit,
        onLine: (String) -> Unit,
    ): AdapterPage

    /**
     * Lines the adapter logged before a session existed, carried into the log of the session that follows.
     * The pairing happens on the connection page, whose log is gone by the time a run starts.
     */
    fun drainDiagnostics(): List<String>
}
