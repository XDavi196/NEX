package com.nex.nx.nextendo

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoCitronBaasSignerTest {
    @Test
    fun importsPkcs8PemAndProducesRs256SignatureForPublishedKid() {
        val pair = KeyPairGenerator.getInstance("RSA").run {
            initialize(2048)
            generateKeyPair()
        }
        val body = Base64.getMimeEncoder(64, byteArrayOf(10))
            .encodeToString(pair.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
        val signer = NextendoCitronBaasSigner.fromPem(pem)
        val input = "citron-baas-test".toByteArray()
        val signature = signer.signRs256(input)

        assertEquals(NextendoCitronBaasProfile.KEY_ID, signer.keyId)
        assertEquals(NextendoCitronBaasProfile.JWKS_URL, signer.jwksUrl)
        assertEquals(256, signature.size)
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pair.public)
        verifier.update(input)
        assertTrue(verifier.verify(signature))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEncryptedOrUnsupportedPem() {
        NextendoCitronBaasSigner.fromPem(
            "-----BEGIN ENCRYPTED PRIVATE KEY-----\n" +
                "ZmFrZQ==\n" +
                "-----END ENCRYPTED PRIVATE KEY-----"
        )
    }
}
