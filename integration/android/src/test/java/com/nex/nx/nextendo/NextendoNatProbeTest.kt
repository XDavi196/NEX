package com.nex.nx.nextendo

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextendoNatProbeTest {
    @Test
    fun encodesAndParsesTheSixteenByteBigEndianProbe() {
        val request = NextendoNatProbeCodec.encodeRequest(101L)
        assertEquals(16, request.size)
        assertEquals(101, ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN).int)

        val packet = response(
            testId = 101L,
            externalPort = 45678,
            externalAddress = ipv4("198.51.100.5"),
            serverAddress = ipv4("203.0.113.10")
        )
        val parsed = NextendoNatProbeCodec.decodeResponse(packet, expectedTestId = 101L)
        assertEquals(45678, parsed.externalPort)
        assertEquals("198.51.100.5", parsed.externalAddress.hostAddress)
        assertEquals("203.0.113.10", parsed.serverAddress.hostAddress)
    }

    @Test
    fun classifiesSameMappingAsConeAndDifferentPortsAsSymmetric() {
        val cone = NextendoNatProbeTracker(setOf("primary", "secondary"))
        cone.begin()
        assertNull(cone.record("primary", response(101L, 45000, "198.51.100.5", "203.0.113.10")))
        val coneResult = cone.record(
            "secondary",
            response(102L, 45000, "198.51.100.5", "203.0.113.11")
        )
        assertEquals(NextendoNatProbeTracker.NatType.CONE, coneResult?.type)
        assertEquals(NextendoNatProbeTracker.State.CLASSIFIED, cone.state)
        cone.close()
        assertEquals(NextendoNatProbeTracker.State.CLOSED, cone.state)
        assertNull(cone.classification)

        val symmetric = NextendoNatProbeTracker(setOf("primary", "secondary"))
        symmetric.begin()
        symmetric.record("primary", response(101L, 45000, "198.51.100.5", "203.0.113.10"))
        val symmetricResult = symmetric.record(
            "secondary",
            response(102L, 45001, "198.51.100.5", "203.0.113.11")
        )
        assertEquals(NextendoNatProbeTracker.NatType.SYMMETRIC, symmetricResult?.type)
        symmetric.close()
    }

    @Test
    fun unknownWhenExternalAddressChangesAcrossVantages() {
        val tracker = NextendoNatProbeTracker(setOf("primary", "secondary"))
        tracker.begin()
        tracker.record("primary", response(101L, 45000, "198.51.100.5", "203.0.113.10"))
        val result = tracker.record(
            "secondary",
            response(102L, 45000, "198.51.100.6", "203.0.113.11")
        )
        assertEquals(NextendoNatProbeTracker.NatType.UNKNOWN, result?.type)
        tracker.close()
    }

    @Test(expected = IllegalStateException::class)
    fun closedTrackerCannotBeReused() {
        val tracker = NextendoNatProbeTracker(setOf("primary", "secondary"))
        tracker.close()
        tracker.begin()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedProbeLength() {
        NextendoNatProbeCodec.decodeResponse(ByteArray(15))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedTestId() {
        NextendoNatProbeCodec.decodeResponse(
            response(102L, 45000, "198.51.100.5", "203.0.113.10"),
            expectedTestId = 101L
        )
    }

    private fun response(
        testId: Long,
        externalPort: Int,
        externalAddress: String,
        serverAddress: String
    ): ByteArray = response(
        testId,
        externalPort,
        ipv4(externalAddress),
        ipv4(serverAddress)
    )

    private fun response(
        testId: Long,
        externalPort: Int,
        externalAddress: Inet4Address,
        serverAddress: Inet4Address
    ): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(testId.toInt())
        buffer.putInt(externalPort)
        buffer.putInt(ipv4ToInt(externalAddress))
        buffer.putInt(ipv4ToInt(serverAddress))
        return buffer.array()
    }

    private fun ipv4(value: String): Inet4Address =
        InetAddress.getByName(value) as Inet4Address

    private fun ipv4ToInt(address: Inet4Address): Int {
        val bytes = address.address
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }
}
