package com.shilapi.xcertplay.iap2.session

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoint
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2CsmChannel

/**
 * One immediately readable and writable iAP2 CSM session.
 *
 * [Iap2CsmChannel] remains the lower-level frame transport. This class is the protocol-facing
 * facade used by services: it accepts endpoint builders, sends complete frames, receives complete
 * frames, and exposes the typed body reader.
 *
 * Successful frames are not passed to [onTrace]. [Iap2FrameFormatter.format] walks every TLV on
 * the transport thread, and playback does not need that text. Failures, ready, and close are
 * still reported.
 */
open class Iap2Session internal constructor(
    private val channel: Iap2CsmChannel?,
    private val traceContext: String = "test",
    private val onTrace: (String) -> Unit = {},
) : AutoCloseable {
    open val isClosed: Boolean get() = channel?.isClosed ?: false

    open fun awaitReady(timeoutMillis: Long): Boolean {
        val ready = channel?.awaitReady(timeoutMillis) ?: true
        emitTrace("IAP2 READY [$traceContext] ready=$ready")
        return ready
    }

    open fun send(frame: Iap2Frame, timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS) {
        val ch = channel ?: return
        try {
            ch.send(frame, timeoutMillis)
        } catch (failure: Throwable) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    Iap2TraceDirection.TX,
                    traceContext,
                    frame.messageId,
                    failure,
                ),
            )
            throw failure
        }
    }

    fun send(
        endpoint: Iap2Endpoint,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
        block: Iap2BodyBuilder.() -> Unit,
    ) {
        send(Iap2Messages.build(endpoint, block), timeoutMillis)
    }

    fun sendRaw(
        messageId: Int,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
        block: Iap2BodyBuilder.() -> Unit,
    ) {
        send(Iap2Messages.buildRaw(messageId, block), timeoutMillis)
    }

    open fun recv(timeoutMillis: Long): Iap2Frame? {
        val ch = channel ?: return null
        return try {
            ch.recv(timeoutMillis)
        } catch (failure: Throwable) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    Iap2TraceDirection.RX,
                    traceContext,
                    messageId = null,
                    failure = failure,
                ),
            )
            throw failure
        }
    }

    fun reader(frame: Iap2Frame): Iap2BodyReader = Iap2Messages.reader(frame)

    override fun close() {
        try {
            channel?.close()
        } finally {
            emitTrace("IAP2 CLOSE [$traceContext]")
        }
    }

    private fun emitTrace(message: String) {
        try {
            onTrace(message)
        } catch (_: Exception) {
            // Logging must never change protocol behavior.
        }
    }

    companion object {
        private const val DEFAULT_SEND_TIMEOUT_MILLIS = 5_000L

        fun open(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wired",
            onTrace: (String) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.open(underlying, linkTrace(traceContext, onTrace)), traceContext, onTrace)

        fun openWireless(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wireless",
            onTrace: (String) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.openWireless(underlying, linkTrace(traceContext, onTrace)), traceContext, onTrace)

        fun openTunnel(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wireless-tunnel",
            onTrace: (String) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.openTunnel(underlying, linkTrace(traceContext, onTrace)), traceContext, onTrace)

        fun wrap(
            channel: Iap2CsmChannel,
            traceContext: String = "iap2",
            onTrace: (String) -> Unit = {},
        ): Iap2Session = Iap2Session(channel, traceContext, onTrace)

        /**
         * The link engine's own messages, tagged with the session they came from.
         *
         * Three links run in one process — wired, wireless RFCOMM, and the Wi-Fi tunnel — and the
         * engine only knows it is a link. The context is what turns "iap2 rx unhandled session=11"
         * into a line someone can act on. A sink that throws is swallowed here for the same reason
         * [emitTrace] swallows it: logging must never change protocol behavior.
         */
        private fun linkTrace(context: String, sink: (String) -> Unit): (String) -> Unit =
            { message ->
                try {
                    sink("[$context] $message")
                } catch (_: Exception) {
                    // See above.
                }
            }
    }
}
