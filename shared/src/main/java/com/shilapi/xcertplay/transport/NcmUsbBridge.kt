package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import com.shilapi.xcertplay.DiagLog
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    descriptorHostMac: ByteArray?,
    private val airPlaySessionLive: () -> Boolean = { true },
    private val writeWindow: NcmWriteWindow = NcmWriteWindow(),
    val liveness: WiredLivenessStats = WiredLivenessStats(),
    val faultRecorder: FirstFaultRecorder? = null,
) : Closeable {
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0
    private var loggedWriteTimeout = false
    private var loggedAuthorizationWait = false
    @Volatile private var inboundFrameSeen = false
    private var consecutiveWriteFailures = 0
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    // Bulk IN uses one persistent async request: bulkTransfer() pins its byte[] in a JNI critical
    // section for the whole wait, which blocks ART's GC thread flip and, with it, every other USB
    // transfer (seen as ~0.8 s stalls of video and audio). A timed-out request stays queued, so no
    // data is lost between calls. This is the only requestWait() user on this connection.
    private val directReadBuffer = ByteBuffer.allocateDirect(READ_CHUNK_BYTES)
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
    private val statusThread = statusEndpoint?.let { endpoint ->
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, sequence)
        val transferred = connection.bulkTransfer(outEndpoint, block, block.size, timeoutMillis)
        if (transferred <= 0) {
            // Before StartCarPlaySession the phone keeps the NCM data path NAKed. That is not a detach
            // and later packets must be allowed to retry, so a failure before the phone has sent
            // anything is expected rather than a fault.
            if (!inboundFrameSeen) {
                if (!loggedWriteTimeout) {
                    loggedWriteTimeout = true
                    DiagLog.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; retaining bridge for retry")
                }
                return@synchronized
            }
            // The phone only sends once its data path is up, so from here a run of failures usually means
            // bulk OUT alone is gone: inbound video keeps arriving while touch, return audio and TCP ACKs
            // are dropped, and the user watches a frozen screen with no error. Android reports a NAK,
            // a timeout and a latched halt with the same failed result. CLEAR_FEATURE(ENDPOINT_HALT)
            // also resets the data toggle when the endpoint was not halted, so a single NAK is not
            // cleared here; three failures end the session and the reopen clears a real halt.
            when (writeWindow.verdict(clockMillis(), airPlaySessionLive())) {
                NcmWriteWindow.Verdict.EXPECTED -> {
                    // The user answers the phone's dialog on the phone's clock, so a hold here is a wait
                    // rather than a fault, however many transfers it costs.
                    if (!loggedAuthorizationWait) {
                        loggedAuthorizationWait = true
                        DiagLog.i(
                            IphoneCarPlayConfiguration.TAG,
                            "ncm bulk-out held for the phone's CarPlay authorization; not counting",
                        )
                    }
                    consecutiveWriteFailures = 0
                    return@synchronized
                }

                NcmWriteWindow.Verdict.EXPIRED -> throw failSession(
                    DisconnectOrigin.NCM_WRITE_EXHAUSTED,
                    "NCM write failed while the phone never authorized CarPlay within " +
                        "${NcmWriteWindow.AUTHORIZATION_GRACE_MILLIS / MILLIS_PER_SECOND}s",
                )

                NcmWriteWindow.Verdict.FAULT -> Unit
            }
            consecutiveWriteFailures += 1
            DiagLog.i(
                IphoneCarPlayConfiguration.TAG,
                "ncm bulk-out failed $consecutiveWriteFailures/$MAX_CONSECUTIVE_WRITE_FAILURES while the data path was live",
            )
            if (consecutiveWriteFailures >= MAX_CONSECUTIVE_WRITE_FAILURES) {
                // USBMUX tears its whole session down for the same class of failure.
                throw failSession(
                    DisconnectOrigin.NCM_WRITE_EXHAUSTED,
                    "NCM write failed $consecutiveWriteFailures times while the data path was live",
                )
            }
            return@synchronized
        }
        if (transferred != block.size) {
            // The phone holds a truncated NTB16 block, so this frame stream cannot be resumed in
            // place; record the failure so the read side stops alongside this writer.
            throw failSession(DisconnectOrigin.NCM_WRITE_EXHAUSTED, "NCM write transferred $transferred of ${block.size} bytes")
        }
        liveness.noteOutbound(transferred, clockMillis())
        consecutiveWriteFailures = 0
        loggedAuthorizationWait = false
        if (loggedWriteTimeout) {
            loggedWriteTimeout = false
            DiagLog.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out became ready")
        }
    }

    /**
     * Opens the window in which failed bulk OUT writes are the phone's CarPlay authorization dialog
     * rather than a fault. Call when the phone is asked to start CarPlay, before any AirPlay session
     * exists; the window closes by itself when that session arrives, and expires if it never does.
     */
    fun awaitCarPlayAuthorization() = writeWindow.arm(clockMillis())

    private fun clockMillis(): Long = SystemClock.elapsedRealtime()

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                drainFrames()
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    override fun close() {
        statusRunning.set(false)
        val requestToClose = synchronized(stateLock) {
            if (closed) return
            closed = true
            readRequest
        }
        // Wakes a reader blocked in requestWait(); it then observes the closed state.
        runCatching { requestToClose?.cancel() }
        statusThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(STATUS_POLL_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        for (usbInterface in claimedInterfaces.asReversed()) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: RuntimeException) {
                // Best-effort release; the connection close below is authoritative.
            }
        }
        connection.close()
        runCatching { requestToClose?.close() }
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
            } catch (_: RuntimeException) {
                return
            }
            if (transferred <= 0) {
                try {
                    Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (!loggedFirst) {
                loggedFirst = true
                DiagLog.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}",
                )
            }
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun drainFrames() {
        while (true) {
            if (bufferedSize < 12) return
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                throw failSession(DisconnectOrigin.NCM_NTB_CORRUPT, "NCM read buffer does not begin with an NTB16 header")
            }
            val blockLength = readU16(buffered, 8)
            if (blockLength < 28) throw failSession(DisconnectOrigin.NCM_NTB_CORRUPT, "Invalid NTB16 block length $blockLength")
            val padded = blockLength % USB_PACKET_SIZE == 0
            val wireLength = blockLength + if (padded) 1 else 0
            if (bufferedSize < wireLength) return
            if (padded && buffered[blockLength].toInt() != 0) {
                throw failSession(DisconnectOrigin.NCM_NTB_CORRUPT, "Invalid NTB16 short-packet pad")
            }
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            val remaining = bufferedSize - wireLength
            buffered.copyInto(buffered, 0, wireLength, bufferedSize)
            bufferedSize = remaining
        }
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(READ_CHUNK_BYTES, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
    }

    private fun enqueueFrame(frame: ByteArray) {
        // Marks the point the phone's data path is up; the write side keys its failure policy off it.
        inboundFrameSeen = true
        liveness.noteInbound(frame.size, clockMillis())
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession(DisconnectOrigin.NCM_QUEUE_OVERFLOW, "NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        checkOpen()
        val request = try {
            // Publish and queue atomically with close(), so detach cannot miss a new request.
            synchronized(stateLock) {
                checkOpenLocked()
                val current = readRequest ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw failSession(DisconnectOrigin.NCM_READ_FAULT, "Android could not initialize the NCM read request")
                    }
                    readRequest = it
                }
                if (!readQueued) {
                    directReadBuffer.clear()
                    if (!queueUsbRequest(current, directReadBuffer)) throw failSession(DisconnectOrigin.NCM_READ_FAULT, "Android could not queue the NCM read request")
                    readQueued = true
                }
                current
            }
        } catch (error: RuntimeException) {
            throw failSession(DisconnectOrigin.NCM_READ_FAULT, "NCM read failed", error)
        }
        try {
            val startWait = clockMillis()
            val completed = try {
                waitForUsbRequest(connection, timeoutMillis)
            } catch (_: TimeoutException) {
                // Nothing arrived yet; the request stays queued for the next call. USBMUX owns
                // authoritative detach/failure detection for the same phone.
                return null
            } ?: throw failSession(DisconnectOrigin.NCM_READ_FAULT, "Android returned no NCM read request")
            val waitDuration = clockMillis() - startWait
            if (waitDuration > 3000L) {
                DiagLog.w(IphoneCarPlayConfiguration.TAG, "ncm read long block in requestWait: ${waitDuration}ms")
            }
            if (completed !== request) throw failSession(DisconnectOrigin.NCM_READ_FAULT, "Android completed an unexpected NCM request")
            readQueued = false
            val transferred = directReadBuffer.position()
            if (transferred <= 0) return null
            directReadBuffer.flip()
            directReadBuffer.get(readBuffer, 0, transferred)
            return transferred
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession(DisconnectOrigin.NCM_READ_FAULT, "NCM read failed", error)
        }
    }

    private fun failSession(
        origin: DisconnectOrigin,
        message: String,
        cause: Throwable? = null,
    ): IphoneUsbException.DeviceUnavailable {
        faultRecorder?.record(origin, message, clockMillis())
        val diag = formatFailureSummary(
            message = message,
            origin = origin,
            consecutiveWriteFailures = consecutiveWriteFailures,
            queuedFrames = frames.size,
            queuedBytes = queuedBytes,
            stats = liveness,
            nowMillis = clockMillis(),
            recorder = faultRecorder,
        )
        DiagLog.e(IphoneCarPlayConfiguration.TAG, diag, cause)
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val USB_PACKET_SIZE = 512
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val MAX_CONSECUTIVE_WRITE_FAILURES = 3
        private const val MILLIS_PER_SECOND = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        internal fun open(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            airPlaySessionLive: () -> Boolean = { true },
            writeWindow: NcmWriteWindow = NcmWriteWindow(),
            liveness: WiredLivenessStats = WiredLivenessStats(),
            faultRecorder: FirstFaultRecorder? = null,
        ): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                DiagLog.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = connection.claimInterface(first, true)
                DiagLog.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${IphoneCarPlayConfiguration.alternateSetting(first)} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=$firstClaimed",
                )
                if (!firstClaimed) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}",
                    )
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = connection.claimInterface(function.data, true)
                    DiagLog.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${IphoneCarPlayConfiguration.alternateSetting(function.data)}" +
                            " class=${function.data.interfaceClass} ok=$dataClaimed",
                    )
                    if (!dataClaimed) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${function.data.id}",
                        )
                    }
                    claimed.add(function.data)
                }
                val altSelected = selectUsbInterface(connection, function.data)
                DiagLog.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=${function.data.id}/${IphoneCarPlayConfiguration.alternateSetting(function.data)} ok=$altSelected",
                )
                if (!altSelected) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                DiagLog.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
                    airPlaySessionLive = airPlaySessionLive,
                    writeWindow = writeWindow,
                    liveness = liveness,
                    faultRecorder = faultRecorder,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    currentInterface == controlInterfaceId &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        fun formatFailureSummary(
            message: String,
            origin: DisconnectOrigin,
            consecutiveWriteFailures: Int,
            queuedFrames: Int,
            queuedBytes: Int,
            stats: WiredLivenessStats,
            nowMillis: Long,
            recorder: FirstFaultRecorder?,
        ): String {
            recorder?.record(origin, message, nowMillis)
            val snap = stats.snapshot(nowMillis)
            val recordedOrigin = recorder?.origin ?: origin
            return "ncm-fail: $message; origin=$recordedOrigin; ${snap.summary()}; outFailures=$consecutiveWriteFailures queuedFrames=$queuedFrames queuedBytes=$queuedBytes"
        }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}
