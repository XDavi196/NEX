package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Test

class NextendoSmm2LoginCodecTest {
    @Test
    fun encodesLoginExUsernameFollowedByAuthenticationInfoWithoutAnotherBuffer() {
        val authenticationInfo = byteArrayOf(0x41, 0x42, 0x43)
        val body = NextendoSmm2LoginCodec.encodeBody("player", authenticationInfo)

        assertEquals(10, body[0].toInt() and 0xFF)
        assertEquals(0, body[1].toInt() and 0xFF)
        assertEquals("player", String(body, 2, 6, Charsets.UTF_8))
        assertEquals(0, body[8].toInt() and 0xFF)
        assertEquals(listOf<Byte>(0x41, 0x42, 0x43), body.copyOfRange(9, body.size).toList())

        val frame = NextendoSmm2LoginCodec.encodeRequest("player", authenticationInfo, 77L)
        val decoded = NextendoRmcCodec.decode(frame)
        assertEquals(NextendoRmcCodec.Kind.REQUEST, decoded.kind)
        assertEquals(NextendoSmm2LoginCodec.PROTOCOL_ID, decoded.protocolId)
        assertEquals(NextendoSmm2LoginCodec.METHOD_LOGIN_EX, decoded.methodId)
        assertEquals(77L, decoded.callId)
        assertEquals(body.toList(), decoded.body.toList())
    }

    @Test
    fun rejectsMissingAuthenticationInfoAndInvalidCallId() {
        try {
            NextendoSmm2LoginCodec.encodeBody("player", byteArrayOf())
            throw AssertionError("Expected missing AuthenticationInfo to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        try {
            NextendoSmm2LoginCodec.encodeRequest("player", byteArrayOf(1), -1L)
            throw AssertionError("Expected invalid call ID to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
