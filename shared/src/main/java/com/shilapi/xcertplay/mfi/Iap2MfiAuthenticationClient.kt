package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Runs LIVI's minimal iAP2 CSM MFi exchange without taking ownership of [Iap2Session]. */
class Iap2MfiAuthenticationClient(
    private val authentication: MfiAuthenticator,
    private val maximumCertificateLength: Int = MfiAuthenticationClient.DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH,
) {
    init {
        require(maximumCertificateLength in 1..MAX_CSM_CERTIFICATE_BYTES) {
            "maximumCertificateLength must be in 1..$MAX_CSM_CERTIFICATE_BYTES"
        }
    }

    /**
     * Blocks until the phone confirms AA05, or throws a typed authentication failure.
     *
     * Returns whether the phone actually challenged this accessory. It is false only when
     * [carryOnWhenTheChallengeNeverComes] let a silent phone through, so a caller that wants to say what
     * happened can, and a caller that does not can ignore it.
     */
    fun run(
        session: Iap2Session,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onProgress: (String) -> Unit = {},
        carryOnWhenTheChallengeNeverComes: Boolean = false,
    ): Boolean {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS"
        }
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val certificate = authentication.readCertificate(maximumCertificateLength)
        val certificateFrame = when (authentication.certificateType) {
            MfiCertificateType.MFI -> Iap2AuthenticationMessages.accessoryCertificate(certificate)
            MfiCertificateType.BAA -> Iap2AuthenticationMessages.accessoryCertificateBody(certificate)
        }
        val certificatePayloadBytes = certificateFrame.payload.size
        onProgress(
            "mfi type=${authentication.certificateType} " +
                "certificate loaded bytes=${certificate.size} " +
                "payload bytes=$certificatePayloadBytes",
        )
        /*
         * Whether the phone asked for the certificate at all.
         *
         * A phone that never asked has not engaged with this accessory and its silence means nothing, so
         * silence is only ever tolerated after this is set. A phone that did ask has looked at who it is
         * talking to, which is the state the whole question is about.
         */
        var asked = false
        while (true) {
            val remaining = remainingMillis(deadlineNanos)
            if (remaining == 0L) return whenItGoesQuiet(asked, carryOnWhenTheChallengeNeverComes, timeoutMillis, onProgress)
            val frame = session.recv(remaining)
                ?: return whenItGoesQuiet(asked, carryOnWhenTheChallengeNeverComes, timeoutMillis, onProgress)
            when (frame.messageId) {
                REQUEST_CERTIFICATE -> {
                    asked = true
                    onProgress("iap2 mfi rx=0xaa00 request-certificate")
                    onProgress("iap2 mfi 0xaa00 parameters=${frame.payload.joinToString("") { "%02x".format(it) }}")
                    send(session, certificateFrame, deadlineNanos)
                    onProgress("iap2 mfi tx=0xaa01 certificate bytes=$certificatePayloadBytes")
                }
                REQUEST_CHALLENGE -> {
                    val challenge = requireParameterZero(frame)
                    if (challenge.size !in 1..MAXIMUM_CHALLENGE_BYTES) {
                        throw Iap2MfiAuthenticationException("Invalid iAP2 MFi challenge length ${challenge.size}")
                    }
                    onProgress("iap2 mfi rx=0xaa02 challenge bytes=${challenge.size}")
                    val signature = authentication.signChallenge(challenge)
                    send(session, Iap2AuthenticationMessages.response(signature), deadlineNanos)
                    onProgress("iap2 mfi tx=0xaa03 signature bytes=${signature.size}")
                }
                AUTHENTICATION_SUCCEEDED -> {
                    onProgress("iap2 mfi rx=0xaa05 authentication-succeeded")
                    return true
                }
                AUTHENTICATION_FAILED -> throw Iap2MfiAuthenticationException("iPhone sent AuthenticationFailed")
                else -> throw Iap2MfiAuthenticationException(
                    "Unexpected iAP2 MFi message 0x${frame.messageId.toString(16).padStart(4, '0')}",
                )
            }
        }
    }

    /**
     * What a phone that has stopped talking means.
     *
     * It asked for the certificate and went quiet: no challenge, and no `AuthenticationFailed` either. The
     * specification's own outcome for a certificate it will not accept is `AuthenticationFailed` (§12.3.2),
     * so silence is neither of the two paths it draws -- and the reconnection flow it draws (§23.2.5.6,
     * Figure 23-4) has no authentication step at all, which is what an accessory the phone already knows
     * would look like. Apple's own note that MFi certificates "may be cached to streamline session
     * initiation" points the same way.
     *
     * Carrying on is how that reading gets tested rather than argued: if the phone takes the rest of the
     * flow, the silence meant "already known", and if it does not, the run says so at the next step instead
     * of here. It is off unless a caller asks for it, and it never applies to a phone that never asked for
     * the certificate -- that one has not engaged with this accessory at all.
     */
    private fun whenItGoesQuiet(
        asked: Boolean,
        carryOn: Boolean,
        timeoutMillis: Long,
        onProgress: (String) -> Unit,
    ): Boolean {
        if (carryOn && asked) {
            onProgress(
                "iap2 mfi: certificate sent, no challenge within ${timeoutMillis}ms; carrying on without one",
            )
            return false
        }
        throw Iap2MfiAuthenticationException("Timed out waiting for iAP2 MFi authentication")
    }

    private fun send(
        session: Iap2Session,
        frame: Iap2Frame,
        deadlineNanos: Long,
    ) {
        val remaining = remainingMillis(deadlineNanos)
        if (remaining == 0L) throw Iap2MfiAuthenticationException("Timed out sending iAP2 MFi authentication reply")
        session.send(frame, remaining)
    }

    private fun requireParameterZero(frame: Iap2Frame): ByteArray {
        return try {
            Iap2AuthenticationMessages.challenge(frame)
        } catch (failure: Exception) {
            throw Iap2MfiAuthenticationException("iAP2 MFi challenge is missing parameter 0", failure)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return 0
        return ((remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
            .coerceAtMost(MAX_TIMEOUT_MILLIS)
    }

    companion object {
        private const val REQUEST_CERTIFICATE = 0xaa00
        private const val CERTIFICATE = 0xaa01
        private const val REQUEST_CHALLENGE = 0xaa02
        private const val RESPONSE = 0xaa03
        private const val AUTHENTICATION_FAILED = 0xaa04
        private const val AUTHENTICATION_SUCCEEDED = 0xaa05
        private const val MAXIMUM_CHALLENGE_BYTES = 128
        private const val MAX_CSM_CERTIFICATE_BYTES = 65_525

        /**
         * How long the accessory waits for the exchange to finish.
         *
         * The specification puts the whole of authentication under control session version 2 inside
         * fifteen seconds, and the link that precedes it costs about a second and a half, so a link that
         * has not authenticated by ten seconds is not going to. This was thirty seconds, which is twice
         * the specification's own budget for the step: it made every failed attempt cost half a minute of
         * standing still, and it read as patience rather than as the bug it was covering for.
         */
        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** A malformed, rejected, unexpected, or timed-out iAP2 MFi authentication exchange. */
class Iap2MfiAuthenticationException(message: String, cause: Throwable? = null) : MfiException(message, cause)
