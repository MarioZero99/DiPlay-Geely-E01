// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import com.shilapi.xcertplay.transport.WirelessGap
import com.shilapi.xcertplay.transport.WirelessHotspotMode

/**
 * How an attempt to bring CarPlay up ended.
 *
 * Four outcomes, and each one is closed at a different moment — that is the whole reason this is an
 * enum rather than a boolean: "it failed" and "it never started" were both being read as a log with no
 * ending, and a report cannot count what it cannot tell apart.
 */
enum class AttemptResult {
    /**
     * The session came up. Closed at the first status that says so, not when the session ends: an
     * attempt that took nine seconds and then ran for three hours took nine seconds.
     */
    OK,

    /** Stopped on a rung, with a [CarPlayFailureReason] to name why. */
    FAIL,

    /** Refused before the first rung; the reason is a [WirelessGap], because nothing else was read yet. */
    BLOCKED,

    /** Started and never reached an outcome: the page went away, or the settings were refused. */
    ABANDONED,
}

/** Written for every record where a field has no value of its own. One token, so `jq` can filter on it. */
const val NO_VALUE = "-"

/**
 * One rung, and how long after the attempt started it was first reported.
 *
 * Elapsed-since-start rather than since-the-previous-rung: a rung that took four seconds is the thing a
 * reader is looking for, and differences can be taken from a monotone series but not the other way
 * round when a rung is never reported at all.
 */
data class AttemptStep(val step: ConnectionStep, val millis: Long)

/**
 * The settings the attempt was started with.
 *
 * Numbers and one encoding, nothing derived: this block exists to separate "it broke" from "it broke
 * at 60 fps", which is the first question anybody asks about a playback fault. Three values and not
 * four: there is no playback-buffer setting in this build to read, and a field that holds a constant
 * would say the same thing on every record.
 */
data class AttemptConfig(
    val fps: Int,
    val hevc: Boolean,
    val scaleTenths: Int,
)

/** What the build and the installation are, independent of any one attempt. */
data class AttemptEnvironment(
    val install: String,
    val version: String,
    val versionCode: Int,
    val model: String,
    val api: Int,
)

/**
 * One attempt, as a line in `events.jsonl`.
 *
 * Every field is a value that was read, never one that was assumed — the same rule the screen follows
 * when it draws the ladder. [why] is the one place two vocabularies meet, and [result] is what says
 * which one was written: a failure names a [CarPlayFailureReason], a refusal names a [WirelessGap], and
 * the two live in different packages so a reader can tell them apart by their spelling alone.
 */
data class AttemptRecord(
    val id: String,
    val generation: Int,
    val retry: Int,
    val startedWallClockMillis: Long,
    val startedUptimeMillis: Long,
    val durationMillis: Long,
    val environment: AttemptEnvironment,
    val transport: CarPlayTransport,
    val mode: WirelessHotspotMode?,
    val hop: WirelessBluetoothHop?,
    val gaps: List<WirelessGap>,
    val steps: List<AttemptStep>,
    val at: ConnectionStep?,
    val result: AttemptResult,
    val why: String,
    val detail: String?,
    val reset: Boolean,
    val config: AttemptConfig,
) {
    /**
     * The failure code the page can show: `W/PAIRING/BRING_UP_FAILED`.
     *
     * Derived, never stored twice — it is the same three values [transport], [at] and [why] read
     * through one function, which is what keeps the line on screen and the line in the file equal.
     */
    val code: String
        get() = "${transport.name.first()}/${at?.name ?: NO_VALUE}/$why"
}

/**
 * The record as one line of JSON, in a fixed field order.
 *
 * Hand-written rather than reflected: this module has no serialisation dependency, and the order is
 * part of the contract — a reader scans a file of these with `cut` or `jq`, and a field that moves is a
 * field somebody's script stops finding. Every optional value is written rather than omitted, so a
 * column never shifts: absent is `"-"` or `null`, never missing.
 */
fun encodeAttempt(record: AttemptRecord): String = buildString(256) {
    append('{')
    field("v", 1)
    comma()
    field("id", record.id)
    comma()
    field("gen", record.generation)
    comma()
    field("retry", record.retry)
    comma()
    field("t", record.startedWallClockMillis)
    comma()
    field("up", record.startedUptimeMillis)
    comma()
    field("install", record.environment.install)
    comma()
    field("ver", record.environment.version)
    comma()
    field("code", record.environment.versionCode)
    comma()
    field("model", record.environment.model)
    comma()
    field("api", record.environment.api)
    comma()
    field("transport", record.transport.name)
    comma()
    field("mode", record.mode?.name ?: NO_VALUE)
    comma()
    field("hop", record.hop?.name ?: NO_VALUE)
    comma()
    append("\"gaps\":[")
    record.gaps.forEachIndexed { index, gap ->
        if (index > 0) append(',')
        appendQuoted(gap.name)
    }
    append(']')
    comma()
    append("\"steps\":[")
    record.steps.forEachIndexed { index, step ->
        if (index > 0) append(',')
        append("{\"s\":")
        appendQuoted(step.step.name)
        append(",\"ms\":")
        append(step.millis)
        append('}')
    }
    append(']')
    comma()
    field("at", record.at?.name ?: NO_VALUE)
    comma()
    field("result", record.result.name)
    comma()
    field("why", record.why)
    comma()
    // The one free-text field in the record, so the one that has to be escaped and already redacted.
    val detail = record.detail
    if (detail == null) {
        append("\"detail\":null")
    } else {
        append("\"detail\":")
        appendQuoted(detail)
    }
    comma()
    field("ms", record.durationMillis)
    comma()
    field("reset", record.reset)
    comma()
    append("\"cfg\":{\"fps\":")
    append(record.config.fps)
    append(",\"codec\":")
    appendQuoted(if (record.config.hevc) "HEVC" else "H264")
    append(",\"res\":")
    append(record.config.scaleTenths)
    append('}')
    append('}')
}

private fun StringBuilder.comma() = append(',')

private fun StringBuilder.field(name: String, value: String) {
    append('"').append(name).append("\":")
    appendQuoted(value)
}

private fun StringBuilder.field(name: String, value: Int) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.field(name: String, value: Long) {
    append('"').append(name).append("\":").append(value)
}

private fun StringBuilder.field(name: String, value: Boolean) {
    append('"').append(name).append("\":").append(value)
}

/**
 * A quoted JSON string.
 *
 * Everything below 0x20 is escaped rather than written through: a `detail` carrying a newline would
 * otherwise end the record early and turn one attempt into two broken lines, which is the one way this
 * file can lose evidence after it was written.
 */
private fun StringBuilder.appendQuoted(value: String) {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}
