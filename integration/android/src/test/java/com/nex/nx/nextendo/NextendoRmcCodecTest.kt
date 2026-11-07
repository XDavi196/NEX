package com.nex.nx.nextendo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NextendoRmcCodecTest {
    @Test
    fun roundTripsSmm2DataStoreRequest() {
        val body = byteArrayOf(10, 20, 30, 40)
        val decoded = NextendoRmcCodec.decode(
            NextendoRmcCodec.encodeRequest(
                protocolId = NextendoSmm2DataStore.PROTOCOL_ID,
                methodId = NextendoSmm2DataStore.GET_USERS.toLong(),
                callId = 77L,
                body = body
            )
        )
        assertEquals(NextendoRmcCodec.Kind.REQUEST, decoded.kind)
        assertEquals(115, decoded.protocolId)
        assertEquals(77L, decoded.callId)
        assertEquals(48L, decoded.methodId)
        assertArrayEquals(body, decoded.body)
    }

    @Test
    fun roundTripsSuccessAndErrorResponses() {
        val body = byteArrayOf(1, 3, 5)
        val success = NextendoRmcCodec.decode(
            NextendoRmcCodec.encodeSuccessResponse(
                protocolId = 115,
                methodId = 48L,
                callId = 9L,
                body = body
            )
        )
        assertEquals(NextendoRmcCodec.Kind.SUCCESS_RESPONSE, success.kind)
        assertEquals(48L, success.methodId)
        assertEquals(9L, success.callId)
        assertArrayEquals(body, success.body)

        val error = NextendoRmcCodec.decode(
            NextendoRmcCodec.encodeErrorResponse(
                protocolId = 115,
                resultCode = 0x80000001L,
                callId = 10L
            )
        )
        assertEquals(NextendoRmcCodec.Kind.ERROR_RESPONSE, error.kind)
        assertEquals(0x80000001L, error.resultCode)
        assertEquals(10L, error.callId)
    }

    @Test
    fun supportsExtendedProtocolIds() {
        val decoded = NextendoRmcCodec.decode(
            NextendoRmcCodec.encodeRequest(
                protocolId = 0x1234,
                methodId = 5L,
                callId = 6L
            )
        )
        assertEquals(0x1234, decoded.protocolId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLengthMismatch() {
        val frame = NextendoRmcCodec.encodeRequest(115, 48L, 1L)
        NextendoRmcCodec.decode(frame.copyOf(frame.size - 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedRequestHeader() {
        NextendoRmcCodec.decode(byteArrayOf(1, 0, 0, 0, 0))
    }
}
