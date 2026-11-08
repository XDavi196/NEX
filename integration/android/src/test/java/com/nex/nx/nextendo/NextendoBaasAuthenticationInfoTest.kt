package com.nex.nx.nextendo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NextendoBaasAuthenticationInfoTest {
    private class TestSigner : NextendoBaasSigner {
        override val keyId = "nex-test-key"
        override val jwksUrl = "https://baas.example.test/1.0.0/certificates"
        override fun signRs256(signingInput: ByteArray) = byteArrayOf(0x41, 0x42, 0x43)
    }

    @Test
    fun wrapsNnexJwtInsideAuthenticationInfoForLoginExExtraData() {
        val gameToken = "nx2.server-issued-test-token"
        val claims = mapOf(
            "iss" to JsonPrimitive("https://baas.example.test/issuer"),
            "aud" to JsonPrimitive("smm2-test"),
            "sub" to JsonPrimitive("test-player"),
            "hm" to JsonPrimitive(true),
            "iat" to JsonPrimitive(1_800_000_000L),
            "exp" to JsonPrimitive(1_800_003_600L)
        )
        val idToken = NextendoBaasIdTokenBuilder.build(claims, gameToken, TestSigner())
        val extraData = NextendoBaasAuthenticationInfo.encodeLoginExtraData(idToken, gameToken)

        val input = ByteBuffer.wrap(extraData).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("AuthenticationInfo", readNexString(input))
        val dataSize = input.int
        assertEquals(extraData.size - input.position(), dataSize)

        assertEquals(1, input.get().toInt() and 0xFF)
        val structureFieldSize = input.int
        assertEquals(dataSize - 5, structureFieldSize)
        val wrappedJwt = readNexString(input)
        assertEquals(3, input.int)
        assertEquals(1, input.get().toInt() and 0xFF)
        assertEquals(40000, input.int)
        assertEquals(input.limit(), input.position())
        assertEquals(3, wrappedJwt.split('.').size)

        val payload = String(
            Base64.getUrlDecoder().decode(wrappedJwt.split('.')[1]),
            StandardCharsets.UTF_8
        )
        assertTrue(payload.contains("\"nnex\":\"$gameToken\""))
    }

    @Test
    fun rejectsTokenWhenNnexDoesNotMatchCurrentAccountSession() {
        val claims = mapOf(
            "iss" to JsonPrimitive("https://baas.example.test/issuer"),
            "aud" to JsonPrimitive("smm2-test"),
            "sub" to JsonPrimitive("test-player"),
            "hm" to JsonPrimitive(true),
            "iat" to JsonPrimitive(1_800_000_000L),
            "exp" to JsonPrimitive(1_800_003_600L)
        )
        val idToken = NextendoBaasIdTokenBuilder.build(
            claims,
            "nx2.other-session-token",
            TestSigner()
        )
        invalid {
            NextendoBaasAuthenticationInfo.encodeLoginExtraData(
                idToken,
                "nx2.current-session-token"
            )
        }
    }

    @Test
    fun rejectsMalformedOrWrongSessionJwt() {
        invalid {
            NextendoBaasAuthenticationInfo.encodeLoginExtraData(
                "not-a-jwt",
                "nx2.current-session-token"
            )
        }
        invalid {
            NextendoBaasAuthenticationInfo.encodeLoginExtraData(
                "eyJhbGciOiJSUzI1NiJ9.eyJubmV4Ijoib3RoZXIifQ.c2ln",
                "nx2.current-session-token"
            )
        }
    }

    private fun readNexString(input: ByteBuffer): String {
        val length = input.short.toInt() and 0xFFFF
        val bytes = ByteArray(length)
        input.get(bytes)
        assertEquals(0, bytes.last().toInt())
        return String(bytes, 0, bytes.size - 1, StandardCharsets.UTF_8)
    }

    private fun invalid(action: () -> Unit) {
        try {
            action()
            fail("Expected invalid BAAS LoginEx payload to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed behavior.
        }
    }
}
