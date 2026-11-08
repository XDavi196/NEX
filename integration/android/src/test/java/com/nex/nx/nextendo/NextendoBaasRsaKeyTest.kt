package com.nex.nx.nextendo

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoBaasRsaKeyTest {
    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun exportsRfc7638ThumbprintAndPublicOnlyJwkSet() {
        val pair = keyPair()
        val jwk = NextendoBaasJwk.from(pair.public as RSAPublicKey)
        val json = Json.parseToJsonElement(jwk.toJson()).jsonObject
        val set = Json.parseToJsonElement(jwk.toJwksJson()).jsonObject

        assertEquals("RSA", json["kty"]?.toString()?.trim('"'))
        assertEquals("RS256", json["alg"]?.toString()?.trim('"'))
        assertEquals("sig", json["use"]?.toString()?.trim('"'))
        assertEquals(jwk.keyId, json["kid"]?.toString()?.trim('"'))
        assertEquals(1, set["keys"]?.jsonArray?.size)
        assertTrue(jwk.modulus.isNotBlank())
        assertTrue(jwk.exponent.isNotBlank())
        assertFalse(json.containsKey("d"))
        assertFalse(json.containsKey("p"))
        assertFalse(json.containsKey("q"))
        assertFalse(json.containsKey("dp"))
        assertFalse(json.containsKey("dq"))
        assertFalse(json.containsKey("qi"))
    }

    @Test
    fun rfc7638ThumbprintIsStableForTheSamePublicKey() {
        val publicKey = keyPair().public as RSAPublicKey
        val first = NextendoBaasJwk.from(publicKey)
        val second = NextendoBaasJwk.from(publicKey)
        assertEquals(first.keyId, second.keyId)
        assertEquals(first.modulus, second.modulus)
        assertEquals(first.exponent, second.exponent)
    }

    @Test
    fun rsaPublicJwkVerifiesRs256SignaturesFromMatchingPrivateKey() {
        val pair = keyPair()
        val publicJwk = NextendoBaasJwk.from(pair.public as RSAPublicKey)
        val signingInput = "eyJhbGciOiJSUzI1NiJ9.eyJubmV4IjoibngyLnRlc3QifQ"
            .toByteArray(StandardCharsets.US_ASCII)
        val signer = object : NextendoBaasSigner {
            override val keyId: String = publicJwk.keyId
            override val jwksUrl: String = "https://example.invalid/baas/jwks"
            override fun signRs256(signingInput: ByteArray): ByteArray =
                Signature.getInstance("SHA256withRSA").run {
                    initSign(pair.private)
                    update(signingInput)
                    sign()
                }
        }

        val signatureBytes = signer.signRs256(signingInput)
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pair.public)
        verifier.update(signingInput)
        assertTrue(verifier.verify(signatureBytes))
    }

    @Test
    fun publicJwkEncodesUnsignedRsaParametersAsBase64Url() {
        val jwk = NextendoBaasJwk.from(keyPair().public as RSAPublicKey)
        assertTrue(jwk.modulus.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(jwk.exponent.matches(Regex("[A-Za-z0-9_-]+")))
        val modulus = Base64.getUrlDecoder().decode(jwk.modulus)
        assertTrue(modulus.isNotEmpty())
        assertFalse(modulus[0] == 0.toByte())
    }
}
