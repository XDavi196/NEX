package com.nex.nx.nextendo

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NextendoBaasIdTokenBuilderTest {
    private class TestSigner : NextendoBaasSigner {
        override val keyId = "nex-test-key-only"
        override val jwksUrl = "https://baas.test.invalid/1.0.0/certificates"
        var lastSigningInput: ByteArray? = null

        override fun signRs256(signingInput: ByteArray): ByteArray {
            lastSigningInput = signingInput
            return "test-signature".toByteArray(StandardCharsets.US_ASCII)
        }
    }

    private fun claims(): Map<String, kotlinx.serialization.json.JsonElement> = mapOf(
        "iss" to JsonPrimitive("https://baas.test.invalid/issuer"),
        "aud" to JsonPrimitive("nex-test-audience"),
        "sub" to JsonPrimitive("test-subject"),
        "jti" to JsonPrimitive("test-jti"),
        "hm" to JsonPrimitive(true)
    )

    @Test
    fun insertsOnlyTheProvidedNx2TokenIntoNnexAndUsesProvisionedRs256Header() {
        val signer = TestSigner()
        val token = NextendoBaasIdTokenBuilder.build(
            claims = claims(),
            gameToken = "nx2.test-server-issued-token",
            signer = signer
        )
        val parts = token.split(".")
        assertEquals(3, parts.size)

        val header = Json.parseToJsonElement(
            String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
        ) as JsonObject
        val payload = Json.parseToJsonElement(
            String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
        ) as JsonObject

        assertEquals("RS256", header["alg"].toString().trim('"'))
        assertEquals("nex-test-key-only", header["kid"].toString().trim('"'))
        assertEquals("id_token", header["typ"].toString().trim('"'))
        assertEquals(
            "https://baas.test.invalid/1.0.0/certificates",
            header["jku"].toString().trim('"')
        )
        assertEquals("nx2.test-server-issued-token", payload["nnex"].toString().trim('"'))
        assertEquals(
            "https://baas.test.invalid/1.0.0/certificates",
            payload["jku"].toString().trim('"')
        )
        assertEquals("https://baas.test.invalid/issuer", payload["iss"].toString().trim('"'))
        assertEquals("true", payload["hm"].toString())
        assertEquals(
            parts[0] + "." + parts[1],
            String(signer.lastSigningInput!!, StandardCharsets.US_ASCII)
        )
        assertEquals(
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                "test-signature".toByteArray(StandardCharsets.US_ASCII)
            ),
            parts[2]
        )
    }

    @Test
    fun rejectsOAuthTokenOrEmptyGameToken() {
        invalid { NextendoBaasIdTokenBuilder.build(claims(), "oauth.access.token", TestSigner()) }
        invalid { NextendoBaasIdTokenBuilder.build(claims(), "nx2.", TestSigner()) }
    }

    @Test
    fun rejectsUnprovisionedClaimsAndInvalidProvisionedUrls() {
        invalid {
            NextendoBaasIdTokenBuilder.build(
                claims() - "aud",
                "nx2.test-server-issued-token",
                TestSigner()
            )
        }
        invalid {
            NextendoBaasIdTokenBuilder.build(
                claims() - "hm",
                "nx2.test-server-issued-token",
                TestSigner()
            )
        }
        val invalidJwksSigner = object : NextendoBaasSigner {
            override val keyId = "nex-test-key-only"
            override val jwksUrl = "http://baas.test.invalid/jwks"
            override fun signRs256(signingInput: ByteArray) = byteArrayOf(1)
        }
        invalid {
            NextendoBaasIdTokenBuilder.build(
                claims(),
                "nx2.test-server-issued-token",
                invalidJwksSigner
            )
        }
        invalid {
            NextendoBaasIdTokenBuilder.build(
                claims() + ("iss" to JsonPrimitive("http://baas.test.invalid/issuer")),
                "nx2.test-server-issued-token",
                TestSigner()
            )
        }
    }

    @Test
    fun rejectsCallerSuppliedNnexOrJkuClaimAndMissingSignerIdentity() {
        invalid {
            NextendoBaasIdTokenBuilder.build(
                claims() + ("nnex" to JsonPrimitive("nx2.not-the-current-token")),
                "nx2.test-server-issued-token",
                TestSigner()
            )
        }
        invalid {
            NextendoBaasIdTokenBuilder.build(
                claims() + ("jku" to JsonPrimitive("https://attacker.invalid/jwks")),
                "nx2.test-server-issued-token",
                TestSigner()
            )
        }
        val signer = object : NextendoBaasSigner {
            override val keyId = ""
            override val jwksUrl = "https://baas.test.invalid/jwks"
            override fun signRs256(signingInput: ByteArray) = byteArrayOf(1)
        }
        invalid { NextendoBaasIdTokenBuilder.build(claims(), "nx2.test-server-issued-token", signer) }
    }

    @Test
    fun rejectsSignerThatDoesNotReturnSignature() {
        val signer = object : NextendoBaasSigner {
            override val keyId = "nex-test-key-only"
            override val jwksUrl = "https://baas.test.invalid/jwks"
            override fun signRs256(signingInput: ByteArray) = byteArrayOf()
        }
        invalid { NextendoBaasIdTokenBuilder.build(claims(), "nx2.test-server-issued-token", signer) }
    }

    private fun invalid(action: () -> Unit) {
        try {
            action()
            fail("Expected the BAAS builder to reject unprovisioned or invalid input.")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
