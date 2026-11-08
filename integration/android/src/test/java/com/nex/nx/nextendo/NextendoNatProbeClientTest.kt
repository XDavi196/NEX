package com.nex.nx.nextendo

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoNatProbeClientTest {
    @Test
    fun probesBothVantagesThroughTheSameUdpSocketAndClassifiesStableMapping() {
        val first = FakeNncsServer(
            bindAddress = "127.0.0.1",
            externalPort = 45_000,
            externalAddress = "198.51.100.20",
            serverAddress = "203.0.113.10"
        )
        val second = FakeNncsServer(
            bindAddress = "127.0.0.2",
            externalPort = 45_000,
            externalAddress = "198.51.100.20",
            serverAddress = "203.0.113.11"
        )

        try {
            val result = NextendoNatProbeClient(
                endpoints = listOf(
                    NextendoNatProbeClient.Endpoint("primary", "127.0.0.1", first.port),
                    NextendoNatProbeClient.Endpoint("secondary", "127.0.0.2", second.port)
                )
            ).probe()

            first.await()
            second.await()

            assertEquals(NextendoNatProbeTracker.NatType.CONE, result.classification.type)
            assertEquals(45_000, result.classification.externalPort)
            assertEquals("198.51.100.20", result.classification.externalAddress?.hostAddress)
            assertEquals(NextendoNatProbeClient.MAPPING_PROBE_MESSAGE_TYPE, first.messageType)
            assertEquals(NextendoNatProbeClient.MAPPING_PROBE_MESSAGE_TYPE, second.messageType)
            assertTrue(result.localUdpPort in 1..65535)
            assertEquals(first.clientSourcePort, second.clientSourcePort)
            assertEquals(result.localUdpPort, first.clientSourcePort)
            assertEquals(setOf("primary", "secondary"), result.responses.keys)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun reportsDifferentExternalPortsAsSymmetricMapping() {
        val first = FakeNncsServer(
            "127.0.0.1", 45_000, "198.51.100.20", "203.0.113.10"
        )
        val second = FakeNncsServer(
            "127.0.0.2", 45_001, "198.51.100.20", "203.0.113.11"
        )

        try {
            val result = NextendoNatProbeClient(
                listOf(
                    NextendoNatProbeClient.Endpoint("primary", "127.0.0.1", first.port),
                    NextendoNatProbeClient.Endpoint("secondary", "127.0.0.2", second.port)
                )
            ).probe()

            first.await()
            second.await()
            assertEquals(NextendoNatProbeTracker.NatType.SYMMETRIC, result.classification.type)
            assertEquals(null, result.classification.externalPort)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTwoConfiguredVantagesThatResolveToTheSameIpv4Address() {
        NextendoNatProbeClient(
            listOf(
                NextendoNatProbeClient.Endpoint("primary", "127.0.0.1", 10_025),
                NextendoNatProbeClient.Endpoint("secondary", "localhost", 10_125)
            )
        ).probe()
    }

    @Test(expected = IllegalArgumentException::class)
    fun requiresExactlyTwoConfiguredVantages() {
        NextendoNatProbeClient(
            listOf(NextendoNatProbeClient.Endpoint("primary", "127.0.0.1", 10_025))
        )
    }

    private class FakeNncsServer(
        bindAddress: String,
        private val externalPort: Int,
        private val externalAddress: String,
        private val serverAddress: String
    ) : Closeable {
        private val socket = DatagramSocket(
            InetSocketAddress(InetAddress.getByName(bindAddress), 0)
        ).apply {
            soTimeout = 2_500
        }

        val port: Int = socket.localPort

        @Volatile
        var clientSourcePort: Int = -1
            private set

        @Volatile
        var messageType: Int = -1
            private set

        @Volatile
        private var failure: Throwable? = null

        private val worker = Thread({
            try {
                val requestBuffer = ByteArray(64)
                val request = DatagramPacket(requestBuffer, requestBuffer.size)
                socket.receive(request)
                if (request.length != 16) {
                    error("NNCS request must be exactly 16 bytes.")
                }

                messageType = ByteBuffer.wrap(
                    request.data,
                    request.offset,
                    request.length
                ).order(ByteOrder.BIG_ENDIAN).int
                clientSourcePort = request.port

                val responseBytes = ByteBuffer.allocate(16)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(messageType)
                    .putInt(externalPort)
                    .putInt(ipv4ToInt(externalAddress))
                    .putInt(ipv4ToInt(serverAddress))
                    .array()
                socket.send(
                    DatagramPacket(
                        responseBytes,
                        responseBytes.size,
                        request.address,
                        request.port
                    )
                )
            } catch (error: Throwable) {
                failure = error
            }
        }, "fake-nncs-$bindAddress").apply {
            isDaemon = true
            start()
        }

        fun await() {
            worker.join(3_000)
            check(!worker.isAlive) { "Fake NNCS server did not receive a probe." }
            failure?.let { throw IllegalStateException("Fake NNCS server failed.", it) }
        }

        override fun close() {
            socket.close()
            worker.join(250)
        }

        private fun ipv4ToInt(value: String): Int =
            ByteBuffer.wrap(InetAddress.getByName(value).address)
                .order(ByteOrder.BIG_ENDIAN)
                .int
    }
}
