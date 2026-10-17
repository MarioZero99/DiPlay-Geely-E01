package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * The iAP2 link layer, deliberately kept independent from I/O, threads, CSM, and media.
 *
 * The caller supplies a monotonic millisecond clock, writes [takeOutput] to its byte stream, and
 * feeds arbitrary receive fragments back through [feed].  This class only exposes control-session
 * (session 10) bytes.  It does not open a Lockdown connection or own a [BlockingDuplexByteStream].
 *
 * An inbound wire frame is limited to the largest u16 length (`65535` bytes total).  Pending
 * outbound and out-of-order packets are additionally capped by [Iap2LinkConfig] (64 by default).
 * Pending output is capped at 1 MiB and pending events at 256 entries by default, so neither peer
 * input nor a caller that temporarily stops draining the engine can grow memory without bound.
 */
class Iap2LinkEngine(
    private val config: Iap2LinkConfig = Iap2LinkConfig(),
    private val onTrace: (String) -> Unit = {},
) {
    enum class State {
        IDLE,
        DETECTING,
        NEGOTIATING,
        NORMAL,
        DEAD,
    }

    sealed class Event {
        data class Control(val bytes: ByteArray) : Event()
        data class Writable(val value: Boolean) : Event()
        data class Dead(val reason: String?) : Event()
    }

    data class SessionDescriptor(
        val id: Int,
        val kind: Int,
        val version: Int,
    ) {
        init {
            require(id in 0..0xff)
            require(kind in 0..0xff)
            require(version in 0..0xff)
        }
    }

    data class SynchronizationPayload(
        val maxOutgoing: Int,
        val maxLength: Int,
        val retransmissionTimeoutMillis: Int,
        val acknowledgementTimeoutMillis: Int,
        val maxRetransmissions: Int,
        val maxAcknowledgements: Int,
        val sessions: List<SessionDescriptor>,
    ) {
        init {
            require(maxOutgoing in 0..0xff)
            require(maxLength in 0..MAX_WIRE_FRAME_BYTES)
            require(retransmissionTimeoutMillis in 0..0xffff)
            require(acknowledgementTimeoutMillis in 0..0xffff)
            require(maxRetransmissions in 0..0xff)
            require(maxAcknowledgements in 0..0xff)
        }

        fun encode(): ByteArray {
            val out = ByteArrayOutputStream(10 + sessions.size * SESSION_DESCRIPTOR_BYTES)
            out.write(SYNCHRONIZATION_VERSION)
            out.write(maxOutgoing)
            writeU16(out, maxLength)
            writeU16(out, retransmissionTimeoutMillis)
            writeU16(out, acknowledgementTimeoutMillis)
            out.write(maxRetransmissions)
            out.write(maxAcknowledgements)
            sessions.forEach {
                out.write(it.id)
                out.write(it.kind)
                out.write(it.version)
            }
            return out.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): SynchronizationPayload? {
                if (bytes.size < SYNCHRONIZATION_FIXED_BYTES || u8(bytes[0]) != SYNCHRONIZATION_VERSION) {
                    return null
                }
                val descriptors = ArrayList<SessionDescriptor>()
                var offset = SYNCHRONIZATION_FIXED_BYTES
                while (offset + SESSION_DESCRIPTOR_BYTES <= bytes.size) {
                    descriptors += SessionDescriptor(u8(bytes[offset]), u8(bytes[offset + 1]), u8(bytes[offset + 2]))
                    offset += SESSION_DESCRIPTOR_BYTES
                }
                return SynchronizationPayload(
                    maxOutgoing = u8(bytes[1]),
                    maxLength = readU16(bytes, 2),
                    retransmissionTimeoutMillis = readU16(bytes, 4),
                    acknowledgementTimeoutMillis = readU16(bytes, 6),
                    maxRetransmissions = u8(bytes[8]),
                    maxAcknowledgements = u8(bytes[9]),
                    sessions = descriptors,
                )
            }
        }
    }

    private data class Header(
        val length: Int,
        val control: Int,
        val sequence: Int,
        val acknowledgement: Int,
        val sessionId: Int,
    )

    private data class Packet(
        var sequence: Int,
        val sessionId: Int,
        val payload: ByteArray,
        var retransmissions: Int = 0,
        var deadlineMillis: Long = 0,
    )

    private var state = State.IDLE
    private var peerSynchronization = localSynchronization()
    private var peerSynchronizationReceived = false
    private var sentSequence = INITIAL_SEQUENCE
    private var lastSentAcknowledged: Int? = null
    private var lastReceivedInOrder = 0

    /**
     * Whether the packet that holds [lastReceivedInOrder] carried a payload.
     *
     * The peer puts an acknowledgement and its data under one sequence number, so the same number
     * can arrive twice with different contents. True is the reading that delivers nothing extra: a
     * sequence whose payload this side never saw is not something to guess at.
     */
    private var lastReceivedSequenceCarriedPayload = true

    private var lastAcknowledgedReceived: Int? = null
    private var accumulatedAcknowledgements = 0
    private var writable = false

    private val unacknowledged = ArrayList<Packet>()
    private val queued = ArrayDeque<Packet>()
    private val outOfOrder = ArrayList<Packet>()
    private val events = ArrayDeque<Event>()
    private val output = ByteArrayOutputStream()
    private val receive = ReceiveBuffer()
    private var awaitingPayload: Header? = null

    private var markerDeadlineMillis: Long? = null
    private var synchronizationDeadlineMillis: Long? = null
    private var sendAcknowledgementDeadlineMillis: Long? = null
    private var receiveAcknowledgementDeadlineMillis: Long? = null

    fun state(): State = state

    fun writable(): Boolean = writable

    fun peerSynchronization(): SynchronizationPayload = peerSynchronization

    fun takeOutput(): ByteArray {
        val bytes = output.toByteArray()
        output.reset()
        return bytes
    }

    fun pollEvent(): Event? = if (events.isEmpty()) null else events.removeFirst()

    fun nextDeadlineMillis(): Long? = listOfNotNull(
        markerDeadlineMillis,
        synchronizationDeadlineMillis,
        sendAcknowledgementDeadlineMillis,
        receiveAcknowledgementDeadlineMillis,
    ).minOrNull()

    /** Starts a link attempt. Wired CarKit uses [wiredInitiator] = true. */
    fun start(wiredInitiator: Boolean, nowMillis: Long) {
        if (state != State.IDLE) return
        state = State.DETECTING
        if (!appendOutput(IAP2_MARKER)) return
        markerDeadlineMillis = nowMillis + MARKER_RESEND_MILLIS
        if (wiredInitiator && state != State.DEAD) enterNegotiating(nowMillis)
    }

    /** Supplies any received byte fragment; empty fragments are harmless. */
    fun feed(bytes: ByteArray, nowMillis: Long) {
        if (state == State.DEAD || bytes.isEmpty()) return
        var offset = 0
        while (offset < bytes.size && state != State.DEAD) {
            val room = receive.remainingCapacity()
            if (room == 0) {
                if (!parseAvailable(nowMillis)) {
                    die("iAP2 inbound frame exceeds $MAX_WIRE_FRAME_BYTES bytes")
                    return
                }
                continue
            }
            val count = minOf(room, bytes.size - offset)
            receive.append(bytes, offset, count)
            offset += count
            while (parseAvailable(nowMillis) && state != State.DEAD) {
                // Drain all complete frames before accepting more input.
            }
        }
    }

    /** The byte stream ended. */
    fun feedEof() {
        die(null)
    }

    /** Advances all due protocol timers with the caller's monotonic clock. */
    fun advanceTime(nowMillis: Long) {
        while (state != State.DEAD) {
            val deadline = nextDeadlineMillis() ?: return
            if (deadline > nowMillis) return
            when {
                markerDeadlineMillis?.let { it <= nowMillis } == true -> {
                    markerDeadlineMillis = null
                    if (state == State.DETECTING) {
                        if (appendOutput(IAP2_MARKER)) {
                            markerDeadlineMillis = nowMillis + MARKER_RESEND_MILLIS
                        }
                    }
                }

                synchronizationDeadlineMillis?.let { it <= nowMillis } == true -> {
                    synchronizationDeadlineMillis = null
                    if (state == State.NEGOTIATING) {
                        sendSynchronization()
                        if (state != State.DEAD) {
                            synchronizationDeadlineMillis = nowMillis + SYNCHRONIZATION_RESEND_MILLIS
                        }
                    }
                }

                sendAcknowledgementDeadlineMillis?.let { it <= nowMillis } == true -> {
                    sendAcknowledgementDeadlineMillis = null
                    if (state == State.NORMAL) {
                        lastAcknowledgedReceived = lastReceivedInOrder
                        sendAcknowledgement()
                    }
                }

                receiveAcknowledgementDeadlineMillis?.let { it <= nowMillis } == true -> {
                    receiveAcknowledgementDeadlineMillis = null
                    retransmitDuePacket(nowMillis)
                }
            }
        }
    }

    /** Queues a complete raw control-session payload (session id 10). */
    fun sendControl(bytes: ByteArray, nowMillis: Long) {
        require(bytes.size <= MAX_PAYLOAD_BYTES) {
            "iAP2 control payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        if (peerSynchronizationReceived || state == State.NORMAL) {
            require(peerPayloadIsAcceptable(bytes)) {
                "iAP2 control payload exceeds peer maxLength ${peerSynchronization.maxLength}"
            }
        }
        sendPacket(Packet(0, CONTROL_SESSION_ID, bytes.copyOf()), nowMillis)
    }

    private fun parseAvailable(nowMillis: Long): Boolean {
        if (state == State.DEAD) return false
        if (state == State.DETECTING) {
            if (receive.size < IAP2_MARKER.size) return false
            if (!receive.matches(IAP2_MARKER)) {
                die("iAP2 marker was not received")
                return false
            }
            receive.discard(IAP2_MARKER.size)
            enterNegotiating(nowMillis)
            return true
        }

        val pending = awaitingPayload
        if (pending != null) {
            val payloadWithChecksumBytes = pending.length - HEADER_BYTES
            if (receive.size < payloadWithChecksumBytes) return false
            val payloadWithChecksum = receive.take(payloadWithChecksumBytes)
            awaitingPayload = null
            if (checksumValid(payloadWithChecksum)) {
                processFrame(
                    pending,
                    payloadWithChecksum.copyOf(payloadWithChecksum.size - CHECKSUM_BYTES),
                    nowMillis,
                )
            }
            return true
        }

        var discarded = false
        while (receive.size >= 2 && (receive[0] != LINK_START_HIGH || receive[1] != LINK_START_LOW)) {
            receive.discard(1)
            discarded = true
        }
        if (receive.size < HEADER_BYTES) return discarded
        val rawHeader = receive.take(HEADER_BYTES)
        val header = parseHeader(rawHeader)
        if (header == null || header.length < HEADER_BYTES) return true
        if (header.length > MAX_WIRE_FRAME_BYTES) {
            die("iAP2 frame length ${header.length} exceeds $MAX_WIRE_FRAME_BYTES")
            return false
        }
        if (header.length == HEADER_BYTES) {
            processFrame(header, null, nowMillis)
        } else {
            awaitingPayload = header
        }
        return true
    }

    private fun enterNegotiating(nowMillis: Long) {
        state = State.NEGOTIATING
        markerDeadlineMillis = null
        sendSynchronization()
        if (state != State.DEAD) {
            synchronizationDeadlineMillis = nowMillis + SYNCHRONIZATION_RESEND_MILLIS
        }
    }

    private fun processFrame(header: Header, payload: ByteArray?, nowMillis: Long) {
        if ((header.control and CONTROL_RESET) != 0) {
            die("peer sent iAP2 reset")
            return
        }
        if ((header.control and CONTROL_SYNCHRONIZE) != 0) {
            SynchronizationPayload.decode(payload ?: ByteArray(0))?.let {
                handleSynchronization(it, header.sequence)
            }
        }
        if ((header.control and CONTROL_ACKNOWLEDGEMENT) != 0) {
            accumulatedAcknowledgements += 1
            handleAcknowledgement(header.acknowledgement, nowMillis)
        }
        if ((header.control and CONTROL_EXTENDED_ACKNOWLEDGEMENT) != 0 && payload != null) {
            handleExtendedAcknowledgement(payload)
        }
        /*
         * Every frame that is not an extended acknowledgement may carry data, whether or not it has a
         * payload, and an acknowledgement-only frame is not exempt: the peer numbers it and expects an
         * answer inside the acknowledgement timeout it advertised, and this side's acknowledgement
         * field is what tells the peer it may keep sending. Treating a missing payload as "nothing to
         * read here" left those frames unanswered for the life of the link -- a bench run where the
         * phone answered our certificate with exactly such a frame saw forty seconds of silence after
         * it, and the challenge that should have followed never came.
         */
        if ((header.control and CONTROL_ACKNOWLEDGEMENT.inv()) == 0) {
            handleData(Packet(header.sequence, header.sessionId, payload ?: EMPTY_PAYLOAD), nowMillis)
        }
        if (peerSynchronization.maxAcknowledgements > 0 &&
            accumulatedAcknowledgements >= peerSynchronization.maxAcknowledgements
        ) {
            accumulatedAcknowledgements = 0
            lastAcknowledgedReceived = lastReceivedInOrder
            sendAcknowledgement()
        }
    }

    private fun handleSynchronization(sync: SynchronizationPayload, sequence: Int) {
        if (state != State.NEGOTIATING) return
        peerSynchronization = sync
        peerSynchronizationReceived = true
        lastReceivedInOrder = sequence
        lastReceivedSequenceCarriedPayload = true
        lastAcknowledgedReceived = sequence
        sendAcknowledgement()
    }

    private fun handleAcknowledgement(acknowledgement: Int, nowMillis: Long) {
        if (state == State.NEGOTIATING) {
            if (!peerSynchronizationReceived || !peerLimitsAreUsable() || queued.any { !peerPayloadIsAcceptable(it.payload) }) {
                die("peer iAP2 maxLength cannot carry pending control packets")
                return
            }
            state = State.NORMAL
            synchronizationDeadlineMillis = null
            setWritable(true)
        }
        lastSentAcknowledged = acknowledgement

        var rearmed = false
        while (unacknowledged.isNotEmpty()) {
            val first = unacknowledged.first()
            val distance = sequenceDistance(first.sequence, lastSentAcknowledged)
            if (distance > 0 && distance <= peerSynchronization.maxAcknowledgements + ACK_SLACK) {
                receiveAcknowledgementDeadlineMillis = first.deadlineMillis
                rearmed = true
                break
            }
            unacknowledged.removeAt(0)
        }
        if (!rearmed) receiveAcknowledgementDeadlineMillis = null

        while (sequenceDistance(sentSequence, lastSentAcknowledged) < peerSynchronization.maxOutgoing) {
            val packet = queued.pollFirst() ?: break
            sendPacket(packet, nowMillis)
            setWritable(true)
        }
    }

    private fun handleExtendedAcknowledgement(missing: ByteArray) {
        if (state != State.NORMAL) return
        for (packet in unacknowledged) {
            if (!missing.any { u8(it) == packet.sequence }) continue
            packet.retransmissions += 1
            if (packet.retransmissions == peerSynchronization.maxRetransmissions) {
                die("iAP2 packet ${packet.sequence} was not acknowledged")
                return
            }
            sendData(packet.sequence, packet.sessionId, packet.payload)
            sendAcknowledgementDeadlineMillis = null
            receiveAcknowledgementDeadlineMillis = packet.deadlineMillis
        }
    }

    private fun handleData(packet: Packet, nowMillis: Long) {
        val distance = sequenceDistance(packet.sequence, lastReceivedInOrder)
        if (distance == 0) {
            /*
             * The peer sends its acknowledgement and its data under one sequence number, and the
             * radio decides which of the two this side reads first. When the payload-less one
             * arrives first it takes the sequence, and the control-session frame that follows --
             * same number, a moment later -- reads as a retransmission of a packet already seen.
             * That is how the challenge that authenticates the accessory went missing while the
             * phone waited for a signature: the run timed out with the challenge sitting in its
             * own packet log. The number belongs to the frame carrying the data; the empty frame
             * is only borrowing it, so deliver the data half instead of counting the sequence
             * twice.
             */
            if (!lastReceivedSequenceCarriedPayload && packet.payload.isNotEmpty()) {
                if (packet.sessionId == CONTROL_SESSION_ID) {
                    lastReceivedSequenceCarriedPayload = true
                    enqueueEvent(Event.Control(packet.payload))
                } else {
                    // Traced, not counted. Only a control-session payload takes the sequence: if a
                    // frame this layer cannot deliver marked the number as carried, a control frame
                    // arriving behind it under that same number -- the order the note above is about
                    // -- would read as a repeat and be dropped. Warning about the payload it cannot
                    // use does not have to cost that.
                    onTrace("iap2 rx unhandled session=${packet.sessionId} payload_bytes=${packet.payload.size}")
                }
            }
            sendAcknowledgement()
            return
        }
        if (distance > peerSynchronization.maxOutgoing + ACK_SLACK) {
            sendAcknowledgement()
            return
        }
        if (outOfOrder.any { it.sequence == packet.sequence }) return
        if (outOfOrder.size >= config.maximumOutOfOrderPackets) {
            die("iAP2 out-of-order packet limit exceeded")
            return
        }
        outOfOrder += packet
        if (distance > 1) {
            if (distance >= peerSynchronization.maxOutgoing) {
                val missing = ArrayList<Int>()
                var sequence = lastReceivedInOrder
                while (sequenceDistance(packet.sequence, sequence) > 1) {
                    sequence = nextSequence(sequence)
                    missing += sequence
                }
                sendAcknowledgementDeadlineMillis = null
                sendExtendedAcknowledgement(missing)
            }
            return
        }

        outOfOrder.sortBy { sequenceDistance(it.sequence, lastReceivedInOrder) }
        while (outOfOrder.isNotEmpty() && sequenceDistance(outOfOrder.first().sequence, lastReceivedInOrder) == 1) {
            val inOrder = outOfOrder.removeAt(0)
            lastReceivedInOrder = inOrder.sequence
            lastReceivedSequenceCarriedPayload = inOrder.payload.isNotEmpty()
            if (inOrder.payload.isNotEmpty()) {
                if (inOrder.sessionId == CONTROL_SESSION_ID) {
                    enqueueEvent(Event.Control(inOrder.payload))
                } else {
                    onTrace("iap2 rx unhandled session=${inOrder.sessionId} payload_bytes=${inOrder.payload.size}")
                }
            }
        }

        if (peerSynchronization.maxAcknowledgements == 0) return
        val windowBeforeForcedAck = (peerSynchronization.maxOutgoing - config.maxOutgoingDelta).coerceAtLeast(1)
        if (sequenceDistance(lastReceivedInOrder, lastAcknowledgedReceived) >= windowBeforeForcedAck) {
            sendAcknowledgementDeadlineMillis = null
            lastAcknowledgedReceived = lastReceivedInOrder
            sendAcknowledgement()
        } else {
            /*
             * Half the peer's acknowledgement timeout, not the whole of it.
             *
             * The timeout is when the peer stops waiting, and the pump only reaches this deadline on
             * its next pass, so firing on the deadline itself puts the acknowledgement on the wire
             * after the peer has already given up: measured against the 500 ms an iPhone advertises,
             * ours went out at 505 to 508 ms. Half the window leaves room for that pass and for the
             * radio to carry it.
             */
            sendAcknowledgementDeadlineMillis =
                nowMillis + peerSynchronization.acknowledgementTimeoutMillis / 2
        }
    }

    private fun sendPacket(packet: Packet, nowMillis: Long) {
        if (state != State.NORMAL ||
            sequenceDistance(sentSequence, lastSentAcknowledged) > peerSynchronization.maxOutgoing
        ) {
            if (queued.size >= config.maximumQueuedPackets) {
                die("iAP2 outbound queue limit exceeded")
            } else {
                queued += packet
                setWritable(false)
            }
            return
        }
        sentSequence = nextSequence(sentSequence)
        packet.sequence = sentSequence
        packet.retransmissions = 0
        packet.deadlineMillis = nowMillis + peerSynchronization.retransmissionTimeoutMillis
        sendAcknowledgementDeadlineMillis = null
        sendData(packet.sequence, packet.sessionId, packet.payload)
        lastAcknowledgedReceived = lastReceivedInOrder
        if (peerSynchronization.maxRetransmissions > 0) {
            unacknowledged += packet
            receiveAcknowledgementDeadlineMillis = packet.deadlineMillis
        } else {
            lastSentAcknowledged = sentSequence
        }
    }

    private fun retransmitDuePacket(nowMillis: Long) {
        if (state != State.NORMAL || unacknowledged.isEmpty()) return
        val next = unacknowledged.minByOrNull { it.deadlineMillis } ?: return
        val secondDeadline = unacknowledged.filter { it !== next }.minOfOrNull { it.deadlineMillis }
        next.deadlineMillis = nowMillis + peerSynchronization.retransmissionTimeoutMillis
        next.retransmissions += 1
        if (next.retransmissions == peerSynchronization.maxRetransmissions) {
            die("iAP2 packet ${next.sequence} was not acknowledged")
            return
        }
        sendData(next.sequence, next.sessionId, next.payload)
        receiveAcknowledgementDeadlineMillis = secondDeadline ?: next.deadlineMillis
    }

    private fun sendSynchronization() {
        writePacket(localSynchronization().encode(), sentSequence, CONTROL_SYNCHRONIZE, 0)
    }

    /**
     * An acknowledgement does not take a sequence number of its own: it goes out under the number of
     * the last packet that carried data.
     *
     * That looks like a defect and is not one. Numbering acknowledgements in turn was tried on the
     * bench, and the link died on it: the phone acknowledges only what it considers the next data
     * packet, so with an acknowledgement consuming a number the frame after it arrives one ahead of
     * what the phone expects, is held as a gap, and the accessory retransmits until it gives up
     * (`iAP2 packet 101 was not acknowledged`, four attempts). Under the numbering below the phone
     * acknowledges as it goes. The phone does the same thing on its own acknowledgement-only frames --
     * it repeats the sequence of the packet it sent last, which `Iap2LinkEngineAcknowledgementTest`
     * records from the bench.
     */
    private fun sendAcknowledgement() {
        writePacket(null, sentSequence, CONTROL_ACKNOWLEDGEMENT, 0)
    }

    private fun sendExtendedAcknowledgement(missing: List<Int>) {
        writePacket(missing.map { it.toByte() }.toByteArray(), sentSequence, CONTROL_EXTENDED_ACKNOWLEDGEMENT, 0)
    }

    private fun sendData(sequence: Int, sessionId: Int, payload: ByteArray) {
        writePacket(payload, sequence, CONTROL_ACKNOWLEDGEMENT, sessionId)
    }

    private fun writePacket(payload: ByteArray?, sequence: Int, control: Int, sessionId: Int) {
        if (state == State.DEAD) return
        accumulatedAcknowledgements = 0
        val length = payload?.size?.plus(HEADER_BYTES + CHECKSUM_BYTES) ?: HEADER_BYTES
        val header = ByteArray(HEADER_BYTES)
        header[0] = LINK_START_HIGH
        header[1] = LINK_START_LOW
        header[2] = (length ushr 8).toByte()
        header[3] = length.toByte()
        header[4] = control.toByte()
        header[5] = sequence.toByte()
        header[6] = lastReceivedInOrder.toByte()
        header[7] = sessionId.toByte()
        header[8] = checksum(header, HEADER_BYTES - CHECKSUM_BYTES).toByte()
        val frame = ByteArrayOutputStream(length)
        frame.write(header)
        if (payload != null) {
            frame.write(payload)
            frame.write(checksum(payload, payload.size))
        }
        appendOutput(frame.toByteArray())
    }

    private fun setWritable(value: Boolean) {
        if (writable == value) return
        writable = value
        enqueueEvent(Event.Writable(value))
    }

    private fun die(reason: String?) {
        if (state == State.DEAD) return
        markerDeadlineMillis = null
        synchronizationDeadlineMillis = null
        sendAcknowledgementDeadlineMillis = null
        receiveAcknowledgementDeadlineMillis = null
        state = State.DEAD
        writable = false
        output.reset()
        events.clear()
        events += Event.Dead(reason)
    }

    private fun appendOutput(bytes: ByteArray): Boolean {
        if (state == State.DEAD) return false
        if (bytes.size > config.maximumPendingOutputBytes - output.size()) {
            die("iAP2 pending output limit exceeded")
            return false
        }
        output.write(bytes)
        return true
    }

    private fun enqueueEvent(event: Event) {
        if (state == State.DEAD) return
        if (events.size >= config.maximumPendingEvents) {
            die("iAP2 pending event limit exceeded")
            return
        }
        events += event
    }

    // A usable session-10 link must carry at least one control byte in addition to its frame
    // header and payload checksum.  This also gives higher layers a positive chunk limit.
    private fun peerLimitsAreUsable(): Boolean = peerSynchronization.maxLength > HEADER_BYTES + CHECKSUM_BYTES

    private fun peerPayloadIsAcceptable(payload: ByteArray): Boolean =
        peerLimitsAreUsable() && payload.size <= peerSynchronization.maxLength - HEADER_BYTES - CHECKSUM_BYTES

    private fun localSynchronization() = SynchronizationPayload(
        maxOutgoing = config.maxOutgoing,
        maxLength = MAX_WIRE_FRAME_BYTES,
        retransmissionTimeoutMillis = if (config.zeroAcknowledgements) 0 else DEFAULT_RETRANSMISSION_TIMEOUT_MILLIS,
        acknowledgementTimeoutMillis = if (config.zeroAcknowledgements) 0 else config.acknowledgementTimeoutMillis,
        maxRetransmissions = if (config.zeroAcknowledgements) 0 else DEFAULT_MAX_RETRANSMISSIONS,
        maxAcknowledgements = if (config.zeroAcknowledgements) 0 else DEFAULT_MAX_ACKNOWLEDGEMENTS,
        sessions = if (config.declareOptionalSessions) {
            listOf(
                SessionDescriptor(CONTROL_SESSION_ID, 0, config.controlSessionVersion),
                SessionDescriptor(EA_SESSION_ID, 2, 1),
                SessionDescriptor(FILE_TRANSFER_SESSION_ID, 1, 2),
            )
        } else {
            listOf(
                SessionDescriptor(CONTROL_SESSION_ID, 0, config.controlSessionVersion),
            )
        },
    )

    private class ReceiveBuffer {
        private val bytes = ByteArray(MAX_WIRE_FRAME_BYTES)
        private var head = 0
        private var tail = 0

        val size: Int get() = tail - head

        fun remainingCapacity(): Int {
            compact()
            return bytes.size - tail
        }

        operator fun get(index: Int): Byte = bytes[head + index]

        fun append(source: ByteArray, offset: Int, count: Int) {
            require(count <= remainingCapacity())
            source.copyInto(bytes, tail, offset, offset + count)
            tail += count
        }

        fun discard(count: Int) {
            require(count in 0..size)
            head += count
            if (head == tail) {
                head = 0
                tail = 0
            }
        }

        fun take(count: Int): ByteArray {
            require(count in 0..size)
            return bytes.copyOfRange(head, head + count).also { discard(count) }
        }

        fun matches(expected: ByteArray): Boolean = expected.indices.all { bytes[head + it] == expected[it] }

        private fun compact() {
            if (head == 0) return
            if (head < tail) bytes.copyInto(bytes, 0, head, tail)
            tail -= head
            head = 0
        }
    }

    companion object {
        const val CONTROL_SESSION_ID = 10
        const val EA_SESSION_ID = 11
        const val FILE_TRANSFER_SESSION_ID = 12

        val IAP2_MARKER = byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10)

        private const val HEADER_BYTES = 9
        private const val CHECKSUM_BYTES = 1
        const val MAX_WIRE_FRAME_BYTES = 0xffff
        const val MAX_PAYLOAD_BYTES = MAX_WIRE_FRAME_BYTES - HEADER_BYTES - CHECKSUM_BYTES
        private const val SESSION_DESCRIPTOR_BYTES = 3
        private const val SYNCHRONIZATION_FIXED_BYTES = 10
        private const val SYNCHRONIZATION_VERSION = 1
        private const val LINK_START_HIGH: Byte = 0xff.toByte()
        private const val LINK_START_LOW: Byte = 0x5a
        private const val CONTROL_SYNCHRONIZE = 0x80
        private const val CONTROL_ACKNOWLEDGEMENT = 0x40
        private const val CONTROL_EXTENDED_ACKNOWLEDGEMENT = 0x20
        private const val CONTROL_RESET = 0x10
        private const val INITIAL_SEQUENCE = 99
        private const val MARKER_RESEND_MILLIS = 1_000L
        private const val SYNCHRONIZATION_RESEND_MILLIS = 500L
        private const val DEFAULT_RETRANSMISSION_TIMEOUT_MILLIS = 4_000
        private const val DEFAULT_MAX_RETRANSMISSIONS = 4
        private const val DEFAULT_MAX_ACKNOWLEDGEMENTS = 3
        private const val ACK_SLACK = 10

        /** Stands in for the absent payload of an acknowledgement-only frame. */
        private val EMPTY_PAYLOAD = ByteArray(0)

        private fun parseHeader(bytes: ByteArray): Header? {
            if (bytes.size != HEADER_BYTES || !checksumValid(bytes)) return null
            if (bytes[0] != LINK_START_HIGH || bytes[1] != LINK_START_LOW) return null
            return Header(
                length = readU16(bytes, 2),
                control = u8(bytes[4]),
                sequence = u8(bytes[5]),
                acknowledgement = u8(bytes[6]),
                sessionId = u8(bytes[7]),
            )
        }

        private fun checksumValid(bytes: ByteArray): Boolean = bytes.fold(0) { sum, byte -> (sum + u8(byte)) and 0xff } == 0

        private fun checksum(bytes: ByteArray, count: Int): Int =
            (-bytes.take(count).sumOf { u8(it) }) and 0xff

        private fun sequenceDistance(sequence: Int, previous: Int?): Int =
            if (previous == null) 0 else (sequence - previous) and 0xff

        private fun nextSequence(sequence: Int): Int = (sequence + 1) and 0xff

        private fun readU16(bytes: ByteArray, offset: Int): Int = (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])

        private fun u8(byte: Byte): Int = byte.toInt() and 0xff

        private fun writeU16(out: ByteArrayOutputStream, value: Int) {
            out.write(value ushr 8)
            out.write(value)
        }
    }
}

/** Configuration advertised by the local link endpoint. */
data class Iap2LinkConfig(
    val maxOutgoing: Int = 30,
    val maxOutgoingDelta: Int = 0,
    val acknowledgementTimeoutMillis: Int = 500,
    val zeroAcknowledgements: Boolean = false,
    val controlSessionVersion: Int = 1,
    /**
     * Whether the synchronization frame declares the EA and file-transfer sessions beside session 10.
     *
     * True is the historical declaration and stays the default: a link that names fewer sessions than
     * the accessory can serve is a protocol-visible change, so a route gives them up on purpose and
     * never by omission. The wireless RFCOMM link is the one that does — see `Iap2LinkChannel`.
     */
    val declareOptionalSessions: Boolean = true,
    val maximumQueuedPackets: Int = 64,
    val maximumOutOfOrderPackets: Int = 64,
    val maximumPendingOutputBytes: Int = 1_048_576,
    val maximumPendingEvents: Int = 256,
) {
    init {
        require(maxOutgoing in 0..0xff)
        require(maxOutgoingDelta in 0..0xff)
        require(acknowledgementTimeoutMillis in 0..0xffff)
        require(controlSessionVersion in 0..0xff)
        require(maximumQueuedPackets in 1..256)
        require(maximumOutOfOrderPackets in 1..256)
        require(maximumPendingOutputBytes >= 1)
        require(maximumPendingEvents >= 1)
    }
}
