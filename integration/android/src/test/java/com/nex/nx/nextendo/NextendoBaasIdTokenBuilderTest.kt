package com.nex.nx.nextendo

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NextendoBaasIdTokenBuilderTest {
    private class TestSigner : NextendoBaasSigner {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").run {
            initialize(2048)
            generateKeyPair()
        }

        override val keyId = "nex-test-key-only"
        override val jwksUrl = "https://baas.test.invalid/1.0.0/certificates"
        val publicKey = keyPair.public
        var lastSigningInput: ByteArray? = null

        override fun signRs256(signingInput: ByteArray): ByteArray {
            lastSigningInput = signingInput.copyOf()
            return Signature.getInstance("SHA256withRSA").run {
                initSign(keyPair.private)
                update(signingInput)
                sign()
            }
        }
    }

    private fun claims(): Map<String, kotlinx.serialization.json.JsonElement> = mapOf(
        "iss" to JsonPrimitive("https://baas.test.invalid/issuer"),
        "aud" to JsonPrimitive("nex-test-audience"),
        "sub" to JsonPrimitive("test-subject"),
        "jti" to JsonPrimitive("test-jti"),
        "hm" to JsonPrimitive(true),
        "iat" to JsonPrimitive(1_800_000_000L),
        "exp" to JsonPrimitive(1_800_003_600L)
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
        assertEquals("https://baas.test.invalid/1.0.0/certificates", payload["jku"].toString().trim('"'))
        assertEquals("https://baas.test.invalid/issuer", payload["iss"].toString().trim('"'))
        assertEquals("true", payload["hm"].toString())
        assertEquals(
            parts[0] + "." + parts[1],
            String(signer.lastSigningInput!!, StandardCharsets.US_ASCII)
        )

        val signingInput = (parts[0] + "." + parts[1]).toByteArray(StandardCharsets.US_ASCII)
        val signatureBytes = Base64.getUrlDecoder().decode(parts[2])
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(signer.publicKey)
        verifier.update(signingInput)
        assertTrue("The compact JWT must carry a valid RS256 signature.", verifier.verify(signatureBytes))

        val tamperedVerifier = Signature.getInstance("SHA256withRSA")
        tamperedVerifier.initVerify(signer.publicKey)
        tamperedVerifier.update((parts[0] + "." + parts[1] + "x").toByteArray(StandardCharsets.US_ASCII))
        assertFalse("A modified signing input must not verify.", tamperedVerifier.verify(signatureBytes))
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
    fun rejectsMissingMalformedOrInvertedLifetimeClaims() {
        invalid {
            NextendoBaasIdTokenBuilder.build(
                claims() - "iat",
                "nx2.test-server-issued-token",
                TestSigner()
            )
        }
        invalid {
            NextendoBaasIdTokenBuilder.build(
                claims() + ("iat" to JsonPrimitive("1800000000")),
                "nx2.test-server-issued-token",
                TestSigner()
            )
        }
        invalid {
            NextendoBaasIdTokenBuilder.build(
                claims() + ("exp" to JsonPrimitive(1_800_000_000L)),
                "nx2.test-server-issued-token",
                TestSigner()
            )
        }
        invalid {
            NextendoBaasIdTokenBuilder.build(
                claims() + ("exp" to JsonPrimitive(1_799_999_999L)),
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
