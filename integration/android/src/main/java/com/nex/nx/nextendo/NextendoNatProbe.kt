package com.nex.nx.nextendo

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-byte big-endian NNCS message type 101, verified against the public Pretendo NNCS implementation.
 * This codec is not assigned any hostname or port; title-specific routing is external.
 */
internal object NextendoNatProbeCodec {
    data class Response(
        val testId: Long,
        val externalPort: Int,
        val externalAddress: Inet4Address,
        val localAddress: Inet4Address
    )

    fun encodeRequest(testId: Long): ByteArray {
        require(testId in 0L..0xFFFFFFFFL) { "NAT probe test id is out of range." }
        return ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(testId.toInt())
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .array()
    }

    fun decodeResponse(
        packet: ByteArray,
        expectedTestId: Long? = null
    ): Response {
        require(packet.size == 16) { "NAT probe response must be exactly 16 bytes." }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val testId = buffer.int.toLong() and 0xFFFFFFFFL
        val externalPortValue = buffer.int.toLong() and 0xFFFFFFFFL
        require(externalPortValue in 1L..65535L) { "NAT probe returned an invalid external port." }
        if (expectedTestId != null) {
            require(testId == expectedTestId) { "NAT probe response test id does not match." }
        }
        val externalAddress = readIpv4(buffer.int)
        val localAddress = readIpv4(buffer.int)
        return Response(
            testId = testId,
            externalPort = externalPortValue.toInt(),
            externalAddress = externalAddress,
            localAddress = localAddress
        )
    }

    private fun readIpv4(value: Int): Inet4Address {
        val bytes = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
        return InetAddress.getByAddress(bytes) as Inet4Address
    }
}

/**
 * Conservative two-vantage NAT classifier. It owns no socket; close() clears
 * observations so a later probe run cannot reuse stale external mappings.
 */
internal class NextendoNatProbeTracker(vantageIds: Set<String>) : AutoCloseable {
    enum class State {
        IDLE,
        PROBING,
        CLASSIFIED,
        CLOSED
    }

    enum class NatType {
        CONE,
        SYMMETRIC,
        UNKNOWN
    }

    data class Classification(
        val type: NatType,
        val externalAddress: Inet4Address?,
        val externalPort: Int?
    )

    private val requiredVantages = vantageIds.toList()
    private val observations = linkedMapOf<String, NextendoNatProbeCodec.Response>()

    var state: State = State.IDLE
        private set

    var classification: Classification? = null
        private set

    init {
        require(requiredVantages.size == 2) { "Exactly two distinct NAT vantage points are required." }
        require(requiredVantages.all { it.isNotBlank() }) { "NAT vantage identifiers must not be blank." }
    }

    fun begin() {
        check(state == State.IDLE) { "NAT probe tracker cannot be restarted." }
        state = State.PROBING
    }

    fun record(
        vantageId: String,
        response: NextendoNatProbeCodec.Response
    ): Classification? {
        check(state == State.PROBING) { "NAT probe tracker is not probing." }
        require(vantageId in requiredVantages) { "Unknown NAT vantage point." }
        check(!observations.containsKey(vantageId)) { "NAT vantage point was already recorded." }

        observations[vantageId] = response
        if (observations.size < requiredVantages.size) return null

        val results = requiredVantages.map { observations.getValue(it) }
        val distinctExternalAddresses = results.map { it.externalAddress }.toSet()

        val type = when {
            distinctExternalAddresses.size != 1 -> NatType.UNKNOWN
            results.map { it.externalPort }.toSet().size == 1 -> NatType.CONE
            else -> NatType.SYMMETRIC
        }

        val externalAddress = results.first().externalAddress.takeIf {
            distinctExternalAddresses.size == 1
        }
        val externalPort = results.first().externalPort.takeIf { type == NatType.CONE }
        return Classification(type, externalAddress, externalPort).also {
            classification = it
            state = State.CLASSIFIED
        }
    }

    override fun close() {
        observations.clear()
        classification = null
        state = State.CLOSED
    }
}
