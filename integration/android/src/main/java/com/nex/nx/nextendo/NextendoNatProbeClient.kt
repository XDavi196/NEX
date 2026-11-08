package com.nex.nx.nextendo

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Performs a bounded two-vantage NNCS mapping probe.
 *
 * Both requests deliberately use the same bound UDP socket: otherwise the OS/NAT
 * may allocate different local ports and the observations would not be comparable.
 * The endpoints are mandatory runtime configuration. This class contains no
 * production hostname, port, DNS override, or automatic game-lifecycle hook.
 *
 * The current 101 probe compares the external mapping observed by two distinct
 * server addresses. It does not measure filtering behavior or implement peer
 * discovery, keepalives, or UDP hole-punching.
 */
internal class NextendoNatProbeClient(
    endpoints: List<Endpoint>,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS
) {
    data class Endpoint(
        val vantageId: String,
        val host: String,
        val port: Int
    )

    data class Result(
        val classification: NextendoNatProbeTracker.Classification,
        val localUdpPort: Int,
        val responses: Map<String, NextendoNatProbeCodec.Response>
    )

    private data class ResolvedEndpoint(
        val endpoint: Endpoint,
        val address: Inet4Address
    )

    private val endpoints: List<Endpoint> = endpoints.toList()

    init {
        require(this.endpoints.size == REQUIRED_VANTAGES) {
            "Exactly two NNCS endpoints must be configured."
        }
        require(this.endpoints.map { it.vantageId }.toSet().size == REQUIRED_VANTAGES) {
            "NNCS vantage identifiers must be unique."
        }
        require(this.endpoints.all { it.vantageId.isNotBlank() }) {
            "NNCS vantage identifiers must not be blank."
        }
        require(this.endpoints.all { it.host.isNotBlank() }) {
            "NNCS endpoint hosts must be configured."
        }
        require(this.endpoints.all { it.port in 1..65535 }) {
            "NNCS endpoint ports must be between 1 and 65535."
        }
        require(timeoutMs in 100..MAX_TIMEOUT_MS) {
            "NNCS probe timeout must be between 100 and $MAX_TIMEOUT_MS milliseconds."
        }
    }

    /**
     * Sends message type 101 to both configured vantages from one UDP socket,
     * then accepts only 16-byte replies from each configured source address/port.
     */
    @Throws(Exception::class)
    fun probe(): Result {
        val resolved = endpoints.map { endpoint ->
            ResolvedEndpoint(endpoint, resolveIpv4(endpoint.host))
        }
        require(resolved.map { it.address }.toSet().size == REQUIRED_VANTAGES) {
            "The two NNCS endpoints must resolve to different IPv4 addresses."
        }

        val tracker = NextendoNatProbeTracker(endpoints.map { it.vantageId }.toSet())
        tracker.begin()
        val responses = linkedMapOf<String, NextendoNatProbeCodec.Response>()
        val request = NextendoNatProbeCodec.encodeRequest(MAPPING_PROBE_MESSAGE_TYPE)

        DatagramSocket().use { socket ->
            val localPort = socket.localPort
            resolved.forEach { target ->
                socket.send(
                    DatagramPacket(
                        request,
                        request.size,
                        target.address,
                        target.endpoint.port
                    )
                )
            }

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
            while (responses.size < REQUIRED_VANTAGES) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) {
                    throw SocketTimeoutException(
                        "Timed out waiting for both configured NNCS vantage responses."
                    )
                }

                val remainingMillis = (
                    (remainingNanos + 999_999L) / 1_000_000L
                ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                socket.soTimeout = remainingMillis

                val receiveBuffer = ByteArray(MAX_RESPONSE_BYTES)
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                socket.receive(packet)

                val source = resolved.firstOrNull {
                    it.address == packet.address && it.endpoint.port == packet.port
                } ?: continue

                if (source.endpoint.vantageId in responses) {
                    continue
                }

                val response = NextendoNatProbeCodec.decodeResponse(
                    packet.data.copyOf(packet.length),
                    expectedTestId = MAPPING_PROBE_MESSAGE_TYPE
                )
                responses[source.endpoint.vantageId] = response
                tracker.record(source.endpoint.vantageId, response)
            }

            val classification = checkNotNull(tracker.classification) {
                "NNCS returned both replies but the mapping classifier did not complete."
            }
            return Result(
                classification = classification,
                localUdpPort = localPort,
                responses = responses.toMap()
            )
        }
    }

    private fun resolveIpv4(host: String): Inet4Address {
        val address = InetAddress.getAllByName(host.trim())
            .firstOrNull { it is Inet4Address }
        require(address is Inet4Address) {
            "NNCS endpoint '$host' did not resolve to an IPv4 address."
        }
        return address
    }

    companion object {
        const val MAPPING_PROBE_MESSAGE_TYPE = 101
        const val DEFAULT_TIMEOUT_MS = 2_000
        const val MAX_TIMEOUT_MS = 10_000
        private const val REQUIRED_VANTAGES = 2
        private const val MAX_RESPONSE_BYTES = 64
    }
}
