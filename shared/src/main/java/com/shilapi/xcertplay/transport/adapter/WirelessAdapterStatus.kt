package com.shilapi.xcertplay.transport.adapter

/**
 * What the pages know about the USB adapter, with the adapter's own vocabulary left behind.
 *
 * The pages used to read the adapter's state machine directly — its observation, its step enum, the sentence
 * each step maps to. Those names are the adapter's business and live with it; all a page actually does with
 * them is show one line and decide whether a phone may connect, which is what this carries.
 */
data class WirelessAdapterStatus(
    /** The radio is on the bus. */
    val plugged: Boolean,
    /** The radio is advertising under the advertised name, so the iPhone can find it. */
    val named: Boolean,
    /**
     * Startup finished and a phone is paired, or the iAP2 stream is already open. Anything short of that is
     * a step the page names in [sentence].
     */
    val flowEffective: Boolean,
    /** The step in words, for the page to show. Null when there is nothing worth saying. */
    val sentence: String?,
)
