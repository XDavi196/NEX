package com.nex.nx.nextendo

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoBaasJwksVerifierTest {
    private fun publicJwk(): NextendoBaasJwk.PublicRsaJwk {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        return NextendoBaasJwk.from(generator.generateKeyPair().public as RSAPublicKey)
    }

    @Test
    fun verifiesExactPublishedPublicJwk() {
        val jwk = publicJwk()
        val verified = NextendoBaasJwksVerifier.verifyDocument(
            jwksUrl = "https://baas.example.invalid/1.0.0/certificates",
            document = """{"keys":[${jwk.toJson()}]}""",
            expected = jwk
        )

        assertEquals(jwk.keyId, verified.keyId)
        assertTrue(verified.matches(jwk))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDifferentKeyUnderTheExpectedKid() {
        val expected = publicJwk()
        val different = publicJwk()
        NextendoBaasJwksVerifier.verifyDocument(
            "https://baas.example.invalid/1.0.0/certificates",
            """{"keys":[${different.toJson().replace(different.keyId, expected.keyId)}]}""",
            expected
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingKid() {
        val expected = publicJwk()
        NextendoBaasJwksVerifier.verifyDocument(
            "https://baas.example.invalid/1.0.0/certificates",
            """{"keys":[]}""",
            expected
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrivateKeyMaterialInPublicJwks() {
        val expected = publicJwk()
        val key = expected.toJson().removeSuffix("}") + ""","d":"must-not-be-published"}"""
        NextendoBaasJwksVerifier.verifyDocument(
            "https://baas.example.invalid/1.0.0/certificates",
            """{"keys":[$key]}""",
            expected
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrivateKeyMaterialInAnUnrelatedJwksEntry() {
        val expected = publicJwk()
        val unrelated = publicJwk()
        val privateEntry = unrelated.toJson().removeSuffix("}") +
            ""","d":"must-not-be-published"}"""
        val document = "{\"keys\":[" + expected.toJson() + "," + privateEntry + "]}"
        NextendoBaasJwksVerifier.verifyDocument(
            "https://baas.example.invalid/1.0.0/certificates",
            document,
            expected
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpsOrMutableJwksUrls() {
        val expected = publicJwk()
        NextendoBaasJwksVerifier.verifyDocument(
            "http://baas.example.invalid/jwks?redirect=1",
            """{"keys":[${expected.toJson()}]}""",
            expected
        )
    }
}
