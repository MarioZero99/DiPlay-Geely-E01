// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import com.shilapi.xcertplay.transport.WirelessGap
import com.shilapi.xcertplay.transport.WirelessHotspotMode
import kotlin.random.Random

/** Eight hex digits, for an attempt id or an installation id. Not a device identity: it is drawn, and resettable. */
fun newHexId(random: Random = Random.Default): String = "%08x".format(random.nextInt())

/**
 * One attempt to bring CarPlay up, followed from start to a single outcome.
 *
 * This is the object the page and the file both read. [lastStep] and [failedAt] used to be fields of the
 * activity, recomputed on every redraw and gone by the next one; the rung a record says the attempt died
 * on and the rung the screen lit are now the same value by construction, so the two cannot disagree.
 *
 * Main thread only. All four places that drive it run there — the status callback already touches views
 * before it returns — so there is no lock here on purpose: a second thread calling this is a fault at
 * that call site, and a lock would hide it rather than prevent it.
 *
 * Clocks are injected. `SystemClock` and `SharedPreferences` live in the host module, and this file has
 * to be testable on the JVM without either.
 *
 * [AttemptRecord.gaps] is empty on everything this class closes except a refusal, and deliberately so:
 * an attempt that got as far as being started was ready at that moment, because the page refuses to
 * start one that is not. Writing an empty list says "nothing was missing"; writing a list that was
 * computed somewhere else would be a second readiness check, and the point of one check is that there
 * is only one.
 */
class AttemptTracker(
    val id: String,
    private val environment: AttemptEnvironment,
    private val transport: CarPlayTransport,
    private val mode: WirelessHotspotMode?,
    private val hop: WirelessBluetoothHop?,
    private val config: AttemptConfig,
    val generation: Int,
    val retry: Int,
    private val uptime: () -> Long,
    private val wallClock: () -> Long,
) {
    private val startedUptime = uptime()
    private val startedWallClock = wallClock()
    private val steps = ArrayList<AttemptStep>(ConnectionStep.entries.size)

    /** The last rung a non-failed status named: where a failure is reported, as on the ladder. */
    var at: ConnectionStep? = null
        private set

    private var finished: AttemptRecord? = null

    /** The one record this attempt produces, or null while it is still going. */
    val record: AttemptRecord? get() = finished

    val closed: Boolean get() = finished != null

    /**
     * A status the controller reported.
     *
     * Reaching a session closes the attempt as [AttemptResult.OK] here and now — every status after
     * that belongs to the running session, not to the attempt, and would otherwise turn a nine-second
     * connect into a three-hour one on the report.
     */
    fun step(status: CarPlayStatus) {
        if (finished != null) return
        // The rung is recorded before the outcome is decided: the status that means "the session is up"
        // is also the one that names the last rung, and a successful record whose `at` stopped at the
        // data link would read as an attempt that never reached the session.
        val rung = connectionStepOf(status, transport)
        if (rung != null && rung != at) {
            at = rung
            steps += AttemptStep(rung, uptime() - startedUptime)
        }
        if (connectionComplete(status)) finish(AttemptResult.OK, NO_VALUE, null, reset = false, gaps = emptyList())
    }

    /** The controller stopped on a rung. [detail] must already be redacted; this file cannot see the redactor. */
    fun fail(reason: CarPlayFailureReason, detail: String?, reset: Boolean = false) {
        finish(AttemptResult.FAIL, reason.name, detail?.take(DETAIL_LIMIT), reset, emptyList())
    }

    /**
     * Refused before the first rung, by [gap].
     *
     * The record carries that one gap rather than the whole readiness list, so [AttemptRecord.why] and
     * [AttemptRecord.gaps] can never say different things about why this attempt did not start.
     */
    fun block(gap: WirelessGap, detail: String? = null) {
        finish(AttemptResult.BLOCKED, gap.name, detail?.take(DETAIL_LIMIT), reset = false, gaps = listOf(gap))
    }

    /**
     * Started, then went away without an outcome.
     *
     * The settings refusal is one of these: the controller was never built, so there is no rung and no
     * failure reason, but there is a sentence the driver was shown and it belongs in [detail].
     */
    fun abandon(detail: String? = null) {
        finish(AttemptResult.ABANDONED, NO_VALUE, detail?.take(DETAIL_LIMIT), reset = false, gaps = emptyList())
    }

    private fun finish(
        result: AttemptResult,
        why: String,
        detail: String?,
        reset: Boolean,
        gaps: List<WirelessGap>,
    ) {
        if (finished != null) return
        finished = AttemptRecord(
            id = id,
            generation = generation,
            retry = retry,
            startedWallClockMillis = startedWallClock,
            startedUptimeMillis = startedUptime,
            durationMillis = uptime() - startedUptime,
            environment = environment,
            transport = transport,
            mode = mode,
            hop = hop,
            gaps = gaps,
            steps = steps.toList(),
            at = at,
            result = result,
            why = why,
            detail = detail,
            reset = reset,
            config = config,
        )
    }

    private companion object {
        /** Longer than any sentence the controller writes, and short enough that a record stays small. */
        const val DETAIL_LIMIT = 120
    }
}
