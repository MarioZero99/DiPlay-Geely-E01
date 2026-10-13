package com.shilapi.xcertplay.transport

import java.io.Closeable

/**
 * The adapter hop's Bluetooth host, whichever stack is doing the protocol work underneath.
 *
 * The implementation is the adapter stack's, and lives outside this tree; only the contract is here,
 * because it is what the rest of the app holds. Two are known. The one the app runs keeps just the USB
 * plumbing below the HCI packet boundary and the byte stream above it, and lets BTstack own L2CAP, SDP,
 * RFCOMM and pairing. The other was written here instead, one protocol detail at a time, and is kept for
 * its tests, which describe the same hop from the other side; nothing starts it for a real connection —
 * it never carried this hop past its own SDP step, so falling back to it would spend a trip on a path
 * already known to stop short.
 *
 * The contract is the same either way. Whoever plugged the adapter in owns the USB interface and closes it;
 * an implementation here owns everything above it and closes only that.
 */
interface AdapterBluetoothHost : Closeable {
    /** The adapter's own address, once the stack has read it. */
    val localAddress: String?

    /**
     * Brings the stack up over the adapter and names it. [discoverable] false leaves inquiry and page scan
     * off, so the iPhone does not see the name until the car hotspot is known to be on.
     *
     * False means it will never work, not that it is not ready yet.
     */
    fun start(discoverable: Boolean = true): Boolean

    /**
     * Turns inquiry and page scan on or off. Repeating the same request does not send another command:
     * the connection page asks every couple of seconds.
     */
    fun setDiscoverable(enabled: Boolean): Boolean

    /** After Wi-Fi has the session: drop the adapter's ACL and stop it being found. */
    fun idleAfterSession()

    /** Lets the next refresh turn scan back on. Does not send a command by itself. */
    fun resumeDiscovery()

    /** The iPhone to open the iAP2 channel to, or null while nothing has paired yet. */
    fun pairedTarget(): PairedTarget?

    /**
     * Connects, secures, finds the iAP2 service and opens its RFCOMM channel.
     *
     * The returned stream borrows the link; closing it ends the channel and the session but leaves the
     * adapter paired and advertised, so reconnecting does not need a new pairing.
     */
    fun openStream(timeoutMillis: Long = DEFAULT_OPEN_TIMEOUT_MILLIS): BlockingDuplexByteStream

    companion object {
        /** How long a caller waits for a connection, an SDP answer and an RFCOMM channel between them. */
        const val DEFAULT_OPEN_TIMEOUT_MILLIS = 15_000L
    }
}
