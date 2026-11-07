package com.nex.nx.nextendo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Strict PRUDP-Lite frame codec.
 *
 * The framing follows the public NintendoClients PRUDP-Lite reference
 * (MIT-licensed); this class does not implement the connection handshake,
 * packet authentication, reliability windows, or a live transport.
 */
internal object NextendoPrudpLiteCodec {
    const val TYPE_SYN = 0
    const val TYPE_CONNECT = 1
    const val TYPE_DATA = 2
    const val TYPE_DISCONNECT = 3
    const val TYPE_PING = 4

    const val FLAG_ACK = 0x001
    const val FLAG_RELIABLE = 0x002
    const val FLAG_NEED_ACK = 0x004
    const val FLAG_HAS_SIZE = 0x008
    const val FLAG_MULTI_ACK = 0x200

    private const val MAGIC = 0x80
    private const val HEADER_SIZE = 12
    private const val OPTION_SUPPORT = 0
    private const val OPTION_CONNECTION_SIGNATURE = 1
    private const val OPTION_CONNECTION_SIGNATURE_LITE = 128
    private const val SIGNATURE_SIZE = 16

    data class Packet(
        val type: Int,
        val flags: Int = 0,
        val sourceType: Int = 0,
        val destinationType: Int = 0,
        val sourcePort: Int = 0,
        val destinationPort: Int = 0,
        val fragmentId: Int = 0,
        val packetId: Int = 0,
        val minorVersion: Int = 0,
        val supportedFunctions: Int = 0,
        val connectionSignature: ByteArray = byteArrayOf(),
        val signature: ByteArray = byteArrayOf(),
        val payload: ByteArray = byteArrayOf()
    )

    data class DecodeResult(
        val packets: List<Packet>,
        val remainder: ByteArray
    )

    fun encode(packet: Packet): ByteArray {
        validatePacket(packet)
        val options = encodeOptions(packet)
        require(options.size <= 0xFF) { "PRUDP-Lite option block is too large." }

        val output = ByteBuffer.allocate(HEADER_SIZE + options.size + packet.payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        output.put(MAGIC.toByte())
        output.put(options.size.toByte())
        output.putShort(packet.payload.size.toShort())
        output.put(((packet.sourceType shl 4) or packet.destinationType).toByte())
        output.put(packet.sourcePort.toByte())
        output.put(packet.destinationPort.toByte())
        output.put(packet.fragmentId.toByte())
        output.putShort((packet.type or (packet.flags shl 4)).toShort())
        output.putShort(packet.packetId.toShort())
        output.put(options)
        output.put(packet.payload)
        return output.array()
    }

    /** Decodes complete frames and returns any incomplete trailing frame unchanged. */
    fun decode(input: ByteArray): DecodeResult {
        val packets = mutableListOf<Packet>()
        var offset = 0

        while (input.size - offset >= HEADER_SIZE) {
            require((input[offset].toInt() and 0xFF) == MAGIC) {
                "Invalid PRUDP-Lite magic byte."
            }

            val optionSize = input[offset + 1].toInt() and 0xFF
            val payloadSize = readUInt16LE(input, offset + 2)
            val frameSize = HEADER_SIZE + optionSize + payloadSize
            if (input.size - offset < frameSize) break

            val sourceDestination = input[offset + 4].toInt() and 0xFF
            val sourceType = sourceDestination ushr 4
            val destinationType = sourceDestination and 0x0F
            val sourcePort = input[offset + 5].toInt() and 0xFF
            val destinationPort = input[offset + 6].toInt() and 0xFF
            val fragmentId = input[offset + 7].toInt() and 0xFF
            val typeAndFlags = readUInt16LE(input, offset + 8)
            val type = typeAndFlags and 0x0F
            val flags = typeAndFlags ushr 4
            val packetId = readUInt16LE(input, offset + 10)

            require(type in TYPE_SYN..TYPE_PING) { "Unknown PRUDP-Lite packet type." }

            val options = decodeOptions(input, offset + HEADER_SIZE, optionSize)
            require(options.keys == expectedOptionIds(type, flags)) {
                "Unexpected PRUDP-Lite option set."
            }

            val support = options[OPTION_SUPPORT]?.let { readUInt32LE(it, 0) } ?: 0L
            val payloadStart = offset + HEADER_SIZE + optionSize
            packets += Packet(
                type = type,
                flags = flags,
                sourceType = sourceType,
                destinationType = destinationType,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                fragmentId = fragmentId,
                packetId = packetId,
                minorVersion = (support and 0xFFL).toInt(),
                supportedFunctions = (support ushr 8).toInt(),
                connectionSignature = options[OPTION_CONNECTION_SIGNATURE] ?: byteArrayOf(),
                signature = options[OPTION_CONNECTION_SIGNATURE_LITE] ?: byteArrayOf(),
                payload = input.copyOfRange(payloadStart, payloadStart + payloadSize)
            )
            offset += frameSize
        }

        return DecodeResult(
            packets = packets,
            remainder = input.copyOfRange(offset, input.size)
        )
    }

    private fun validatePacket(packet: Packet) {
        require(packet.type in TYPE_SYN..TYPE_PING) { "Unknown PRUDP-Lite packet type." }
        require(packet.flags in 0..0x0FFF) { "PRUDP-Lite flags are out of range." }
        require(packet.sourceType in 0..0x0F && packet.destinationType in 0..0x0F) {
            "PRUDP-Lite station type is out of range."
        }
        require(packet.sourcePort in 0..0xFF && packet.destinationPort in 0..0xFF) {
            "PRUDP-Lite station port is out of range."
        }
        require(packet.fragmentId in 0..0xFF) { "PRUDP-Lite fragment id is out of range." }
        require(packet.packetId in 0..0xFFFF) { "PRUDP-Lite packet id is out of range." }
        require(packet.minorVersion in 0..0xFF) { "PRUDP-Lite minor version is out of range." }
        require(packet.supportedFunctions in 0..0xFFFFFF) {
            "PRUDP-Lite supported-function mask is out of range."
        }
        require(packet.payload.size <= 0xFFFF) { "PRUDP-Lite payload is too large." }
    }

    private fun encodeOptions(packet: Packet): ByteArray {
        val output = ByteArrayOutputStream()
        if (packet.type == TYPE_SYN || packet.type == TYPE_CONNECT) {
            val support = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(packet.minorVersion or (packet.supportedFunctions shl 8))
                .array()
            writeOption(output, OPTION_SUPPORT, support)
        }

        when {
            packet.type == TYPE_SYN && packet.flags and FLAG_ACK != 0 -> {
                require(packet.connectionSignature.size == SIGNATURE_SIZE) {
                    "A PRUDP-Lite SYN acknowledgement requires a 16-byte connection signature."
                }
                require(packet.signature.isEmpty()) { "Unexpected Lite signature on SYN." }
                writeOption(output, OPTION_CONNECTION_SIGNATURE, packet.connectionSignature)
            }
            packet.type == TYPE_CONNECT && packet.flags and FLAG_ACK == 0 -> {
                require(packet.signature.size == SIGNATURE_SIZE) {
                    "A PRUDP-Lite CONNECT request requires a 16-byte Lite signature."
                }
                require(packet.connectionSignature.isEmpty()) {
                    "Unexpected connection signature on CONNECT."
                }
                writeOption(output, OPTION_CONNECTION_SIGNATURE_LITE, packet.signature)
            }
            else -> {
                require(packet.connectionSignature.isEmpty() && packet.signature.isEmpty()) {
                    "Unexpected PRUDP-Lite signature for this packet."
                }
            }
        }
        return output.toByteArray()
    }

    private fun decodeOptions(
        input: ByteArray,
        start: Int,
        size: Int
    ): Map<Int, ByteArray> {
        val result = linkedMapOf<Int, ByteArray>()
        var cursor = start
        val end = start + size
        while (cursor < end) {
            require(end - cursor >= 2) { "Truncated PRUDP-Lite option header." }
            val id = input[cursor].toInt() and 0xFF
            val length = input[cursor + 1].toInt() and 0xFF
            cursor += 2
            require(end - cursor >= length) { "Truncated PRUDP-Lite option value." }
            require(id == OPTION_SUPPORT ||
                id == OPTION_CONNECTION_SIGNATURE ||
                id == OPTION_CONNECTION_SIGNATURE_LITE
            ) { "Unknown PRUDP-Lite option." }
            require(!result.containsKey(id)) { "Duplicate PRUDP-Lite option." }
            val value = input.copyOfRange(cursor, cursor + length)
            when (id) {
                OPTION_SUPPORT -> require(length == 4) {
                    "Invalid PRUDP-Lite support-option length."
                }
                OPTION_CONNECTION_SIGNATURE,
                OPTION_CONNECTION_SIGNATURE_LITE -> require(length == SIGNATURE_SIZE) {
                    "Invalid PRUDP-Lite signature-option length."
                }
            }
            result[id] = value
            cursor += length
        }
        return result
    }

    private fun expectedOptionIds(type: Int, flags: Int): Set<Int> = when {
        type == TYPE_SYN && flags and FLAG_ACK != 0 ->
            setOf(OPTION_SUPPORT, OPTION_CONNECTION_SIGNATURE)
        type == TYPE_SYN -> setOf(OPTION_SUPPORT)
        type == TYPE_CONNECT && flags and FLAG_ACK == 0 ->
            setOf(OPTION_SUPPORT, OPTION_CONNECTION_SIGNATURE_LITE)
        type == TYPE_CONNECT -> setOf(OPTION_SUPPORT)
        else -> emptySet()
    }

    private fun writeOption(output: ByteArrayOutputStream, id: Int, value: ByteArray) {
        require(value.size <= 0xFF)
        output.write(id)
        output.write(value.size)
        output.write(value)
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
}

/** Buffers split TCP/WebSocket reads and discards an incomplete frame on close. */
internal class NextendoPrudpLiteStreamDecoder : AutoCloseable {
    private var pending = byteArrayOf()
    private var closed = false

    @Synchronized
    fun append(chunk: ByteArray): List<NextendoPrudpLiteCodec.Packet> {
        check(!closed) { "PRUDP-Lite stream decoder is closed." }
        if (chunk.isEmpty()) return emptyList()

        val combined = ByteArray(pending.size + chunk.size)
        System.arraycopy(pending, 0, combined, 0, pending.size)
        System.arraycopy(chunk, 0, combined, pending.size, chunk.size)

        val decoded = NextendoPrudpLiteCodec.decode(combined)
        pending = decoded.remainder
        return decoded.packets
    }

    @Synchronized
    fun pendingByteCount(): Int = pending.size

    @Synchronized
    override fun close() {
        pending = byteArrayOf()
        closed = true
    }
}
