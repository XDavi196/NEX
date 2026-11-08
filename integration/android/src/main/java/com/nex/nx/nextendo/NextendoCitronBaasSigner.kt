package com.nex.nx.nextendo

import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Citron-compatible signer for the fixed Nextendo BAAS key.
 *
 * NEX never generates a substitute key: the PEM must be supplied by an
 * authorized Nextendo operator and its derived public JWK must match the live
 * Nextendo JWKS before the signer can be installed.
 */
internal class NextendoCitronBaasSigner private constructor(
    private val privateKey: RSAPrivateCrtKey,
    val publicJwk: NextendoBaasJwk.PublicRsaJwk
) : NextendoBaasSigner {
    override val keyId: String = NextendoCitronBaasProfile.KEY_ID
    override val jwksUrl: String = NextendoCitronBaasProfile.JWKS_URL

    override fun signRs256(signingInput: ByteArray): ByteArray {
        require(signingInput.isNotEmpty()) { "RS256 signing input must not be empty." }
        require(signingInput.size <= MAX_SIGNING_INPUT_BYTES) {
            "RS256 signing input exceeds the configured size limit."
        }
        return Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signingInput)
            sign()
        }
    }

    companion object {
        private const val MAX_SIGNING_INPUT_BYTES = 64 * 1024
        private val RSA_ENCRYPTION_OID = byteArrayOf(
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01
        )

        fun fromPem(pem: String): NextendoCitronBaasSigner {
            require(pem.length in 100..(32 * 1024)) {
                "A chave BAAS PEM deve ter entre 100 bytes e 32 KiB."
            }
            val isPkcs8 = pem.contains("-----BEGIN PRIVATE KEY-----")
            val isPkcs1 = pem.contains("-----BEGIN RSA PRIVATE KEY-----")
            require(isPkcs8 || isPkcs1) {
                "Formato PEM inválido. Use uma chave RSA PKCS#8 ou PKCS#1 não criptografada."
            }

            val body = pem
                .replace(Regex("-----BEGIN [^-]+-----"), "")
                .replace(Regex("-----END [^-]+-----"), "")
                .replace(Regex("\\s"), "")
            val decoded = runCatching { Base64.getDecoder().decode(body) }
                .getOrElse { throw IllegalArgumentException("A chave BAAS PEM contém Base64 inválido.") }
            val pkcs8 = if (isPkcs1) wrapPkcs1(decoded) else decoded
            val privateKey = runCatching {
                KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as RSAPrivateCrtKey
            }.getOrElse {
                throw IllegalArgumentException("Não foi possível ler a chave privada RSA BAAS.")
            }
            require(privateKey.modulus.bitLength() in 2048..4096) {
                "A chave BAAS RSA precisa ter entre 2048 e 4096 bits."
            }

            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
            ) as java.security.interfaces.RSAPublicKey
            return NextendoCitronBaasSigner(
                privateKey = privateKey,
                publicJwk = NextendoBaasJwk.from(
                    publicKey,
                    NextendoCitronBaasProfile.KEY_ID
                )
            )
        }

        /** Wraps a PKCS#1 RSAPrivateKey structure in a PKCS#8 PrivateKeyInfo. */
        private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
            val algorithmIdentifier = der(
                0x30,
                RSA_ENCRYPTION_OID + byteArrayOf(0x05, 0x00)
            )
            val privateKeyInfo = byteArrayOf(0x02, 0x01, 0x00) +
                algorithmIdentifier + der(0x04, pkcs1)
            return der(0x30, privateKeyInfo)
        }

        private fun der(tag: Int, content: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + derLength(content.size) + content

        private fun derLength(length: Int): ByteArray {
            require(length >= 0)
            if (length < 0x80) return byteArrayOf(length.toByte())
            if (length <= 0xFF) {
                return byteArrayOf(0x81.toByte(), length.toByte())
            }
            if (length <= 0xFFFF) {
                return byteArrayOf(
                    0x82.toByte(),
                    (length ushr 8).toByte(),
                    length.toByte()
                )
            }
            return byteArrayOf(
                0x83.toByte(),
                (length ushr 16).toByte(),
                (length ushr 8).toByte(),
                length.toByte()
            )
        }
    }
}
