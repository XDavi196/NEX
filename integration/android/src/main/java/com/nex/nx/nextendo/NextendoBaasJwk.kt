package com.nex.nx.nextendo

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Public-only RSA JWK representation used to register a NEX Android Keystore key.
 *
 * The thumbprint follows RFC 7638 canonical member ordering: e, kty, n.
 * Private key material is never represented by this type or serialized.
 */
internal object NextendoBaasJwk {
    data class PublicRsaJwk(
        val keyId: String,
        val modulus: String,
        val exponent: String
    ) {
        fun toJson(): String =
            """{"kty":"RSA","use":"sig","alg":"RS256","kid":"$keyId","n":"$modulus","e":"$exponent"}"""

        fun toJwksJson(): String = """{"keys":[${toJson()}]}"""
    }

    fun from(publicKey: RSAPublicKey, registeredKeyId: String? = null): PublicRsaJwk {
        val modulus = base64Url(unsignedBytes(publicKey.modulus))
        val exponent = base64Url(unsignedBytes(publicKey.publicExponent))
        val canonical = """{"e":"$exponent","kty":"RSA","n":"$modulus"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val thumbprint = MessageDigest.getInstance("SHA-256").digest(canonical)
        return PublicRsaJwk(
            keyId = registeredKeyId?.also { require(it.isNotBlank()) }
                ?: base64Url(thumbprint),
            modulus = modulus,
            exponent = exponent
        )
    }

    private fun unsignedBytes(value: BigInteger): ByteArray {
        require(value.signum() > 0) { "RSA public key values must be positive." }
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
