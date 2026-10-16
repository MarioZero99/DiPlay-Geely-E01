package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.network.WirelessStartupPolicy

/**
 * "The phone was told where to find us and no AirPlay TCP ever came back."
 *
 * This is the only question allowed to wake the address-family flip, and it is deliberately not the
 * iAP2 control loop's own deadline. That deadline answers a different question about a different
 * interval: it is five minutes on an ordinary run and twenty-four hours when location reporting is on,
 * and it closes when the *control conversation* goes quiet — a phone that opens the AirPlay port and
 * then fails somewhere later would be counted by it too, and its address family blamed for a fault
 * that has nothing to do with reachability. Thirty seconds after CarPlay StartSession with nothing on
 * the AirPlay port is the one shape that says the address we published is not one the phone can use.
 *
 * Order-insensitive on purpose. A phone that found us through Bonjour may open the socket before the
 * 0x4301 goes out, so an accept is remembered whether or not the arming has happened yet, and the arm
 * that follows declines to schedule anything. Either order settles the same question.
 */
internal class FirstTcpWatchdog(
    /** Runs [body] after [Long] milliseconds; the caller decides which clock and which thread. */
    private val schedule: (Long, () -> Unit) -> Unit,
    private val onTimeout: () -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = WirelessStartupPolicy.FIRST_TCP_MILLIS,
    private val log: (String) -> Unit = {},
) {
    private var startNanos: Long? = null
    private var acceptNanos: Long? = null

    /** 0x4301 has gone out: from here the phone knows the address and the port. */
    @Synchronized fun startSessionSent() {
        if (startNanos != null) return
        startNanos = nowNanos()
        if (acceptNanos != null) return
        schedule(timeoutMillis) { expire() }
    }

    /** A peer opened the AirPlay port. Whatever goes wrong from here is not a question of reachability. */
    @Synchronized fun accepted(): Boolean {
        if (acceptNanos == null) {
            acceptNanos = nowNanos()
            log("wireless first AirPlay TCP accepted")
        }
        return true
    }

    private fun expire() {
        synchronized(this) {
            if (acceptNanos != null || startNanos == null) return
        }
        log(
            "wireless no AirPlay TCP within $timeoutMillis ms of CarPlay StartSession " +
                "startedAtNs=$startNanos",
        )
        onTimeout()
    }
}
