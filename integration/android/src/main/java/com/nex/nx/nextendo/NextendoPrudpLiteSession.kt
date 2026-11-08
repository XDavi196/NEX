package com.nex.nx.nextendo

import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Pure PRUDP-Lite client state machine for the Switch/NEX WebSocket transport.
 *
 * It builds and validates the protocol handshake, frames RMC messages as reliable
 * DATA packets, acknowledges inbound packets, de-duplicates retransmissions and
 * reassembles fragmented RMC messages. Network I/O is kept outside this class so
 * the state machine can be tested deterministically.
 *
 * The caller must supply the title endpoint, access key and CONNECT authentication
 * payload from a separately validated runtime configuration/provider. No title
 * host, port, access key, BAAS token or ticket is guessed by this implementation.
 */
internal class NextendoPrudpLiteSession(config: Config) : AutoCloseable {
    data class Config(
        val accessKey: String,
        val clientSourceType: Int,
        val clientSourcePort: Int,
        val serverDestinationType: Int,
        val serverDestinationPort: Int,
        val minorVersion: Int,
        val supportedFunctions: Int,
        val connectPayload: ByteArray = byteArrayOf(),
        val fragmentSize: Int = 1300,
        val maxRmcMessageBytes: Int = 1024 * 1024,
        val maxPendingReliablePackets: Int = 256,
        val resendTimeoutMs: Long = 5000L
    ) {
        init {
            require(accessKey.isNotBlank()) { "A runtime-provided PRUDP access key is required." }
            require(clientSourceType in 0..0x0F && serverDestinationType in 0..0x0F) {
                "PRUDP station types must fit in four bits."
            }
            require(clientSourcePort in 0..0xFF && serverDestinationPort in 0..0xFF) {
                "PRUDP station ports must fit in eight bits."
            }
            require(minorVersion in 0..0xFF) { "PRUDP minor version is out of range." }
            require(supportedFunctions in 0..0xFFFFFF) {
                "PRUDP supported-function mask is out of range."
            }
            require(fragmentSize in 1..0xFFFF) { "PRUDP fragment size is out of range." }
            require(maxRmcMessageBytes in 1..(8 * 1024 * 1024)) {
                "Maximum RMC message size is out of range."
            }
            require(maxPendingReliablePackets in 1..4096) {
                "Maximum pending reliable packet count is out of range."
            }
            require(resendTimeoutMs in 1L..120_000L) {
                "PRUDP retransmission timeout is out of range."
            }
            require(connectPayload.size <= 0xFFFF) {
                "PRUDP CONNECT payload is too large for one packet."
            }
        }
    }

    enum class State {
        NEW,
        SYN_SENT,
        CONNECT_SENT,
        CONNECTED,
        CLOSED
    }

    sealed class Event {
        data class Connected(val responsePayload: ByteArray) : Event()
        data class RmcMessage(val message: NextendoRmcCodec.Message) : Event()
        data class ProtocolError(val reason: String) : Event()
        object Disconnected : Event()
    }

    data class Step(
        val outboundFrames: List<ByteArray> = emptyList(),
        val events: List<Event> = emptyList()
    )

    private data class PendingFrame(
        val frame: ByteArray,
        var lastSentAtMs: Long
    )

    private val clientSourceType = config.clientSourceType
    private val clientSourcePort = config.clientSourcePort
    private val serverDestinationType = config.serverDestinationType
    private val serverDestinationPort = config.serverDestinationPort
    private val minorVersion = config.minorVersion
    private val supportedFunctions = config.supportedFunctions
    private val connectPayload = config.connectPayload.copyOf()
    private val fragmentSize = config.fragmentSize
    private val maxRmcMessageBytes = config.maxRmcMessageBytes
    private val maxPendingReliablePackets = config.maxPendingReliablePackets
    private val resendTimeoutMs = config.resendTimeoutMs

    // Retain the title key only until the CONNECT proof has been generated.
    private var runtimeAccessKey: String? = config.accessKey
    private val decoder = NextendoPrudpLiteStreamDecoder()
    private val pendingReliable = LinkedHashMap<Int, PendingFrame>()
    private var pendingHandshake: PendingFrame? = null
    private val recentlyReceived = LinkedHashSet<Int>()
    private val fragmentBuffer = ByteArrayOutputStream()

    private var currentState = State.NEW
    private var connectFrame: ByteArray? = null
    private var connectionSignature: ByteArray? = null
    private var negotiatedMinorVersion = 0
    private var negotiatedSupportedFunctions = 0
    private var nextReliablePacketId = 1
    private var nextExpectedFragmentId = 1

    @Synchronized
    fun state(): State = currentState

    /** Starts the handshake and returns the initial SYN frame. */
    @Synchronized
    fun start(nowMs: Long = monotonicNowMs()): Step {
        check(currentState == State.NEW) { "PRUDP-Lite session has already been started." }

        val syn = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_SYN,
            flags = NextendoPrudpLiteCodec.FLAG_NEED_ACK,
            sourceType = clientSourceType,
            destinationType = serverDestinationType,
            sourcePort = clientSourcePort,
            destinationPort = serverDestinationPort,
            packetId = 0,
            minorVersion = minorVersion,
            supportedFunctions = supportedFunctions
        )
        currentState = State.SYN_SENT
        val frame = NextendoPrudpLiteCodec.encode(syn)
        pendingHandshake = PendingFrame(frame, nowMs)
        return Step(outboundFrames = listOf(frame.copyOf()))
    }

    /**
     * Feeds one WebSocket binary message (or arbitrary partial bytes) into the
     * stream decoder and returns responses/events generated by complete packets.
     */
    @Synchronized
    fun receive(bytes: ByteArray, nowMs: Long = monotonicNowMs()): Step {
        check(currentState != State.NEW && currentState != State.CLOSED) {
            "PRUDP-Lite session is not receiving data."
        }
        val outbound = mutableListOf<ByteArray>()
        val events = mutableListOf<Event>()

        for (packet in decoder.append(bytes)) {
            processPacket(packet, outbound, events, nowMs)
        }
        return Step(outbound, events)
    }

    /** Sends one complete encoded RMC frame as reliable PRUDP DATA packets. */
    @Synchronized
    fun sendRmc(encodedRmc: ByteArray, nowMs: Long = monotonicNowMs()): Step {
        check(currentState == State.CONNECTED) { "PRUDP-Lite handshake is not complete." }
        require(encodedRmc.size <= maxRmcMessageBytes) {
            "RMC message exceeds the configured size limit."
        }
        val rmcMessage = NextendoRmcCodec.decode(encodedRmc)
        require(rmcMessage.kind == NextendoRmcCodec.Kind.REQUEST) {
            "Only outbound RMC requests can be sent by the PRUDP client."
        }

        val chunkCount = (encodedRmc.size + fragmentSize - 1) / fragmentSize
        require(chunkCount in 1..MAX_FRAGMENT_COUNT) {
            "RMC message requires too many PRUDP-Lite fragments."
        }
        require(pendingReliable.size + chunkCount <= maxPendingReliablePackets) {
            "PRUDP reliable send window is full; wait for acknowledgements."
        }

        val frames = ArrayList<ByteArray>(chunkCount)
        var offset = 0
        repeat(chunkCount) { index ->
            val end = minOf(offset + fragmentSize, encodedRmc.size)
            val fragmentId = if (index == chunkCount - 1) 0 else index + 1
            val packetId = allocatePacketId()
            val packet = NextendoPrudpLiteCodec.Packet(
                type = NextendoPrudpLiteCodec.TYPE_DATA,
                flags = NextendoPrudpLiteCodec.FLAG_RELIABLE or
                    NextendoPrudpLiteCodec.FLAG_NEED_ACK,
                sourceType = clientSourceType,
                destinationType = serverDestinationType,
                sourcePort = clientSourcePort,
                destinationPort = serverDestinationPort,
                fragmentId = fragmentId,
                packetId = packetId,
                payload = encodedRmc.copyOfRange(offset, end)
            )
            val frame = NextendoPrudpLiteCodec.encode(packet)
            pendingReliable[packetId] = PendingFrame(frame, nowMs)
            frames += frame
            offset = end
        }
        return Step(outboundFrames = frames)
    }

    /**
     * Retransmits PRUDP reliable frames whose acknowledgement has not arrived.
     * The timeout defaults to Nextendo's published Switch NEX profile (5 seconds).
     * A zero retry limit in that profile means the reliable sender keeps retrying
     * while the connection remains open; WebSocket/TLS remains the outer transport.
     */
    @Synchronized
    fun tick(nowMs: Long = monotonicNowMs()): Step {
        if (currentState == State.NEW || currentState == State.CLOSED) return Step()
        val due = mutableListOf<ByteArray>()
        pendingHandshake?.let { pending ->
            if (isDue(nowMs, pending.lastSentAtMs)) {
                pending.lastSentAtMs = nowMs
                due += pending.frame.copyOf()
            }
        }
        pendingReliable.values.forEach { pending ->
            if (isDue(nowMs, pending.lastSentAtMs)) {
                pending.lastSentAtMs = nowMs
                due += pending.frame.copyOf()
            }
        }
        return Step(outboundFrames = due)
    }

    private fun isDue(nowMs: Long, lastSentAtMs: Long): Boolean {
        require(nowMs >= lastSentAtMs) { "PRUDP monotonic clock moved backwards." }
        return nowMs - lastSentAtMs >= resendTimeoutMs
    }

    /** Returns defensive copies of unacknowledged DATA packets. */
    @Synchronized
    fun pendingReliableFrames(): List<ByteArray> =
        pendingReliable.values.map { it.frame.copyOf() }

    @Synchronized
    fun pendingReliableCount(): Int = pendingReliable.size

    private fun processPacket(
        packet: NextendoPrudpLiteCodec.Packet,
        outbound: MutableList<ByteArray>,
        events: MutableList<Event>,
        nowMs: Long
    ) {
        when (packet.type) {
            NextendoPrudpLiteCodec.TYPE_SYN -> processSyn(packet, outbound, events, nowMs)
            NextendoPrudpLiteCodec.TYPE_CONNECT -> processConnect(packet, events)
            NextendoPrudpLiteCodec.TYPE_DATA -> processData(packet, outbound, events)
            NextendoPrudpLiteCodec.TYPE_PING -> processPing(packet, outbound)
            NextendoPrudpLiteCodec.TYPE_DISCONNECT -> processDisconnect(packet, outbound, events)
            else -> events += Event.ProtocolError("unsupported_packet_type")
        }
    }

    private fun processSyn(
        packet: NextendoPrudpLiteCodec.Packet,
        outbound: MutableList<ByteArray>,
        events: MutableList<Event>,
        nowMs: Long
    ) {
        if (currentState == State.CONNECT_SENT) {
            val knownSignature = connectionSignature
            if (!validateServerDirection(packet) &&
                packet.isSynAcknowledgement() &&
                knownSignature != null &&
                knownSignature.contentEquals(packet.connectionSignature)
            ) {
                connectFrame?.let {
                    pendingHandshake?.lastSentAtMs = nowMs
                    outbound += it.copyOf()
                }
            } else {
                events += Event.ProtocolError("unexpected_syn_retransmission")
            }
            return
        }
        if (currentState != State.SYN_SENT) {
            events += Event.ProtocolError("unexpected_syn")
            return
        }
        if (!packet.isSynAcknowledgement()) {
            events += Event.ProtocolError("invalid_syn_ack_flags")
            return
        }
        if (validateServerDirection(packet)) {
            events += Event.ProtocolError("unexpected_station_address")
            return
        }
        if (packet.connectionSignature.size != CONNECTION_SIGNATURE_BYTES) {
            events += Event.ProtocolError("invalid_connection_signature")
            return
        }

        val accessKey = runtimeAccessKey
            ?: throw IllegalStateException("The runtime PRUDP access key is no longer available.")
        pendingHandshake = null
        connectionSignature = packet.connectionSignature.copyOf()
        negotiatedMinorVersion = packet.minorVersion
        negotiatedSupportedFunctions = packet.supportedFunctions and supportedFunctions

        val connect = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_CONNECT,
            flags = NextendoPrudpLiteCodec.FLAG_NEED_ACK,
            sourceType = clientSourceType,
            destinationType = serverDestinationType,
            sourcePort = clientSourcePort,
            destinationPort = serverDestinationPort,
            packetId = CONNECT_PACKET_ID,
            minorVersion = negotiatedMinorVersion,
            supportedFunctions = negotiatedSupportedFunctions,
            signature = NextendoPrudpLiteAuthenticator.signConnect(
                accessKey,
                packet.connectionSignature
            ),
            payload = connectPayload.copyOf()
        )
        val frame = NextendoPrudpLiteCodec.encode(connect)
        connectFrame = frame
        pendingHandshake = PendingFrame(frame, nowMs)
        runtimeAccessKey = null
        connectPayload.fill(0)
        currentState = State.CONNECT_SENT
        outbound += frame
    }

    private fun processConnect(
        packet: NextendoPrudpLiteCodec.Packet,
        events: MutableList<Event>
    ) {
        if (currentState == State.CONNECTED) {
            // Repeated CONNECT+ACKs are harmless; do not signal a second connection.
            return
        }
        if (currentState != State.CONNECT_SENT) {
            events += Event.ProtocolError("unexpected_connect_packet")
            return
        }
        if (packet.flags and NextendoPrudpLiteCodec.FLAG_ACK == 0 ||
            packet.flags and NextendoPrudpLiteCodec.FLAG_HAS_SIZE == 0
        ) {
            events += Event.ProtocolError("invalid_connect_ack_flags")
            return
        }
        if (validateServerDirection(packet)) {
            events += Event.ProtocolError("unexpected_station_address")
            return
        }
        if (packet.packetId != CONNECT_PACKET_ID) {
            events += Event.ProtocolError("invalid_connect_ack_packet_id")
            return
        }

        currentState = State.CONNECTED
        pendingHandshake = null
        connectFrame = null
        connectionSignature = null
        events += Event.Connected(packet.payload.copyOf())
    }

    private fun processData(
        packet: NextendoPrudpLiteCodec.Packet,
        outbound: MutableList<ByteArray>,
        events: MutableList<Event>
    ) {
        if (currentState != State.CONNECTED) {
            events += Event.ProtocolError("data_before_connect")
            return
        }
        if (validateServerDirection(packet)) {
            events += Event.ProtocolError("unexpected_station_address")
            return
        }

        if (packet.flags and NextendoPrudpLiteCodec.FLAG_MULTI_ACK != 0) {
            acknowledgeMultiple(packet.payload, events)
            return
        }
        if (packet.flags and NextendoPrudpLiteCodec.FLAG_ACK != 0) {
            pendingReliable.remove(packet.packetId)
            return
        }

        if (packet.flags and NextendoPrudpLiteCodec.FLAG_NEED_ACK != 0) {
            outbound += acknowledgement(packet)
            if (wasRecentlyReceived(packet.packetId)) return
        }

        val fragmentError = appendFragment(packet)
        if (fragmentError != null) {
            events += Event.ProtocolError(fragmentError)
            return
        }
        if (packet.fragmentId != 0) return

        val messageBytes = fragmentBuffer.toByteArray()
        resetFragmentBuffer()
        if (messageBytes.size > maxRmcMessageBytes) {
            events += Event.ProtocolError("rmc_message_too_large")
            return
        }

        val message = runCatching { NextendoRmcCodec.decode(messageBytes) }
            .getOrElse {
                events += Event.ProtocolError("invalid_rmc_frame")
                return
            }
        events += Event.RmcMessage(message)
    }

    private fun appendFragment(packet: NextendoPrudpLiteCodec.Packet): String? {
        if (packet.fragmentId != 0) {
            if (fragmentBuffer.size() == 0) {
                if (packet.fragmentId != 1) {
                    resetFragmentBuffer()
                    return "fragment_sequence_must_start_at_one"
                }
                nextExpectedFragmentId = 1
            }
            if (packet.fragmentId != nextExpectedFragmentId) {
                resetFragmentBuffer()
                return "fragment_sequence_mismatch"
            }
            if (packet.fragmentId == 0xFF) {
                resetFragmentBuffer()
                return "fragment_sequence_overflow"
            }
            nextExpectedFragmentId++
        }

        if (fragmentBuffer.size() + packet.payload.size > maxRmcMessageBytes) {
            resetFragmentBuffer()
            return "rmc_message_too_large"
        }
        fragmentBuffer.write(packet.payload)
        return null
    }

    private fun resetFragmentBuffer() {
        fragmentBuffer.reset()
        nextExpectedFragmentId = 1
    }

    private fun acknowledgeMultiple(
        payload: ByteArray,
        events: MutableList<Event>
    ) {
        if (payload.size < 4) {
            events += Event.ProtocolError("invalid_multi_ack_payload")
            return
        }
        val extraCount = payload[1].toInt() and 0xFF
        if (payload.size < 4 + extraCount * 2) {
            events += Event.ProtocolError("truncated_multi_ack_payload")
            return
        }
        val base = readUInt16LE(payload, 2)
        val acknowledged = pendingReliable.keys.filter { it <= base }.toMutableSet()
        var offset = 4
        repeat(extraCount) {
            acknowledged += readUInt16LE(payload, offset)
            offset += 2
        }
        acknowledged.forEach { packetId -> pendingReliable.remove(packetId) }
    }

    private fun processPing(
        packet: NextendoPrudpLiteCodec.Packet,
        outbound: MutableList<ByteArray>
    ) {
        if (validateServerDirection(packet)) return
        if (packet.flags and NextendoPrudpLiteCodec.FLAG_ACK != 0) return
        if (packet.flags and NextendoPrudpLiteCodec.FLAG_NEED_ACK != 0) {
            outbound += acknowledgement(packet)
        }
    }

    private fun processDisconnect(
        packet: NextendoPrudpLiteCodec.Packet,
        outbound: MutableList<ByteArray>,
        events: MutableList<Event>
    ) {
        if (validateServerDirection(packet)) {
            events += Event.ProtocolError("unexpected_station_address")
            return
        }
        if (packet.flags and NextendoPrudpLiteCodec.FLAG_ACK == 0 &&
            packet.flags and NextendoPrudpLiteCodec.FLAG_NEED_ACK != 0
        ) {
            outbound += acknowledgement(packet)
        }
        currentState = State.CLOSED
        releaseSecrets()
        connectFrame = null
        connectionSignature = null
        decoder.close()
        pendingReliable.clear()
        pendingHandshake = null
        resetFragmentBuffer()
        events += Event.Disconnected
    }

    private fun acknowledgement(packet: NextendoPrudpLiteCodec.Packet): ByteArray {
        val ack = NextendoPrudpLiteCodec.Packet(
            type = packet.type,
            flags = NextendoPrudpLiteCodec.FLAG_ACK,
            sourceType = clientSourceType,
            destinationType = serverDestinationType,
            sourcePort = clientSourcePort,
            destinationPort = serverDestinationPort,
            fragmentId = packet.fragmentId,
            packetId = packet.packetId
        )
        return NextendoPrudpLiteCodec.encode(ack)
    }

    private fun validateServerDirection(packet: NextendoPrudpLiteCodec.Packet): Boolean =
        packet.sourceType != serverDestinationType ||
            packet.sourcePort != serverDestinationPort ||
            packet.destinationType != clientSourceType ||
            packet.destinationPort != clientSourcePort

    private fun wasRecentlyReceived(packetId: Int): Boolean {
        if (!recentlyReceived.add(packetId)) return true
        if (recentlyReceived.size > RECEIVED_WINDOW_SIZE) {
            val oldest = recentlyReceived.first()
            recentlyReceived.remove(oldest)
        }
        return false
    }

    private fun allocatePacketId(): Int {
        check(pendingReliable.size < maxPendingReliablePackets) {
            "PRUDP reliable send window is full."
        }
        repeat(0x10000) {
            val id = nextReliablePacketId
            nextReliablePacketId = (nextReliablePacketId + 1) and 0xFFFF
            if (id !in pendingReliable) return id
        }
        throw IllegalStateException("No free PRUDP packet sequence id is available.")
    }

    private fun releaseSecrets() {
        runtimeAccessKey = null
        connectPayload.fill(0)
    }

    @Synchronized
    override fun close() {
        currentState = State.CLOSED
        releaseSecrets()
        connectFrame = null
        connectionSignature = null
        decoder.close()
        pendingReliable.clear()
        pendingHandshake = null
        resetFragmentBuffer()
    }

    private fun NextendoPrudpLiteCodec.Packet.isSynAcknowledgement(): Boolean =
        type == NextendoPrudpLiteCodec.TYPE_SYN &&
            flags and NextendoPrudpLiteCodec.FLAG_ACK != 0 &&
            flags and NextendoPrudpLiteCodec.FLAG_HAS_SIZE != 0

    private companion object {
        const val MAX_FRAGMENT_COUNT = 255
        const val RECEIVED_WINDOW_SIZE = 128
        const val CONNECTION_SIGNATURE_BYTES = 16
        const val CONNECT_PACKET_ID = 1

        fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

        fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }
}
