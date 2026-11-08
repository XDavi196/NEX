package com.nex.nx.nextendo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoPrudpLiteCodecTest {
    @Test
    fun roundTripsSynAndSynAcknowledgement() {
        val syn = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_SYN,
            sourceType = 1,
            destinationType = 2,
            sourcePort = 3,
            destinationPort = 4,
            packetId = 0x1234,
            minorVersion = 1,
            supportedFunctions = 0x10203
        )
        val decodedSyn = NextendoPrudpLiteCodec.decode(NextendoPrudpLiteCodec.encode(syn))
        assertEquals(1, decodedSyn.packets.size)
        assertEquals(NextendoPrudpLiteCodec.TYPE_SYN, decodedSyn.packets.single().type)
        assertEquals(1, decodedSyn.packets.single().sourceType)
        assertEquals(2, decodedSyn.packets.single().destinationType)
        assertEquals(0x1234, decodedSyn.packets.single().packetId)
        assertEquals(1, decodedSyn.packets.single().minorVersion)
        assertEquals(0x10203, decodedSyn.packets.single().supportedFunctions)

        val signature = ByteArray(16) { it.toByte() }
        val synAck = syn.copy(
            flags = NextendoPrudpLiteCodec.FLAG_ACK,
            connectionSignature = signature
        )
        val decodedAck = NextendoPrudpLiteCodec.decode(NextendoPrudpLiteCodec.encode(synAck))
        assertArrayEquals(signature, decodedAck.packets.single().connectionSignature)
    }

    @Test
    fun roundTripsConnectAndDataFrames() {
        val liteSignature = ByteArray(16) { (it + 3).toByte() }
        val connect = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_CONNECT,
            signature = liteSignature,
            minorVersion = 2,
            supportedFunctions = 0x112233
        )
        val decodedConnect = NextendoPrudpLiteCodec.decode(
            NextendoPrudpLiteCodec.encode(connect)
        ).packets.single()
        assertArrayEquals(liteSignature, decodedConnect.signature)
        assertEquals(2, decodedConnect.minorVersion)
        assertEquals(0x112233, decodedConnect.supportedFunctions)

        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val data = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_DATA,
            flags = NextendoPrudpLiteCodec.FLAG_RELIABLE or
                NextendoPrudpLiteCodec.FLAG_NEED_ACK,
            fragmentId = 1,
            packetId = 99,
            payload = payload
        )
        val decodedData = NextendoPrudpLiteCodec.decode(NextendoPrudpLiteCodec.encode(data))
            .packets.single()
        assertEquals(data.flags, decodedData.flags)
        assertEquals(data.fragmentId, decodedData.fragmentId)
        assertEquals(data.packetId, decodedData.packetId)
        assertArrayEquals(payload, decodedData.payload)
    }

    @Test
    fun decodesConcatenatedFramesAndBuffersPartialReads() {
        val first = NextendoPrudpLiteCodec.encode(
            NextendoPrudpLiteCodec.Packet(type = NextendoPrudpLiteCodec.TYPE_PING, packetId = 1)
        )
        val second = NextendoPrudpLiteCodec.encode(
            NextendoPrudpLiteCodec.Packet(
                type = NextendoPrudpLiteCodec.TYPE_DATA,
                payload = byteArrayOf(7, 8, 9)
            )
        )
        val combined = first + second
        val decoded = NextendoPrudpLiteCodec.decode(combined)
        assertEquals(2, decoded.packets.size)
        assertTrue(decoded.remainder.isEmpty())

        val stream = NextendoPrudpLiteStreamDecoder()
        assertTrue(stream.append(second.copyOfRange(0, 5)).isEmpty())
        assertEquals(5, stream.pendingByteCount())
        val completed = stream.append(second.copyOfRange(5, second.size))
        assertEquals(1, completed.size)
        assertArrayEquals(byteArrayOf(7, 8, 9), completed.single().payload)
        stream.close()
        assertEquals(0, stream.pendingByteCount())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidMagic() {
        NextendoPrudpLiteCodec.decode(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownOptions() {
        NextendoPrudpLiteCodec.decode(
            byteArrayOf(
                0x80.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                99, 0
            )
        )
    }

    @Test(expected = IllegalStateException::class)
    fun closedStreamDecoderRejectsFurtherReads() {
        val stream = NextendoPrudpLiteStreamDecoder()
        stream.close()
        stream.append(byteArrayOf(1))
    }
}
