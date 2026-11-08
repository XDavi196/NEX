package com.nex.nx.nextendo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * Per-installation RS256 signer backed by Android Keystore.
 *
 * The private key is non-exportable and never leaves Android Keystore. The
 * public JWK can be exported for registration by the Nextendo operator.
 * This does not publish the JWK or enable BAAS by itself: the configured HTTPS
 * JWKS URL must actually serve this exact kid/modulus/exponent before use.
 */
internal class NextendoAndroidKeystoreBaasSigner private constructor(
    private val privateKey: PrivateKey,
    val publicJwk: NextendoBaasJwk.PublicRsaJwk,
    override val jwksUrl: String
) : NextendoBaasSigner {
    override val keyId: String = publicJwk.keyId

    override fun signRs256(signingInput: ByteArray): ByteArray {
        require(signingInput.isNotEmpty()) { "RS256 signing input must not be empty." }
        require(signingInput.size <= MAX_SIGNING_INPUT_BYTES) {
            "RS256 signing input exceeds the configured size limit."
        }
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(signingInput)
        return signature.sign()
    }

    /** Public JWK Set; contains no private key parameters. */
    fun exportPublicJwks(): String = publicJwk.toJwksJson()

    companion object {
        const val KEY_ALIAS = "com.nex.nx.nextendo.baas.rs256.v1"
        const val RSA_KEY_SIZE_BITS = 2048
        private const val MAX_SIGNING_INPUT_BYTES = 64 * 1024
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        private data class KeyMaterial(
            val privateKey: PrivateKey,
            val publicKey: RSAPublicKey
        )

        fun createOrLoad(jwksUrl: String): NextendoAndroidKeystoreBaasSigner {
            validateJwksUrl(jwksUrl)
            val material = loadOrCreateKeyMaterial()
            return NextendoAndroidKeystoreBaasSigner(
                privateKey = material.privateKey,
                publicJwk = NextendoBaasJwk.from(material.publicKey),
                jwksUrl = jwksUrl
            )
        }

        /**
         * Creates or loads the per-installation key and returns only its public JWK Set.
         * Safe to share with the Nextendo operator for registration; no private
         * parameters or signing capability are returned.
         */
        fun exportOrCreatePublicJwks(): String =
            NextendoBaasJwk.from(loadOrCreateKeyMaterial().publicKey).toJwksJson()

        private fun loadOrCreateKeyMaterial(): KeyMaterial {
            var store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!store.containsAlias(KEY_ALIAS)) {
                val generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA,
                    ANDROID_KEYSTORE
                )
                generator.initialize(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setKeySize(RSA_KEY_SIZE_BITS)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setRandomizedEncryptionRequired(false)
                        .build()
                )
                generator.generateKeyPair()
                store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            }

            val privateKey = store.getKey(KEY_ALIAS, null) as? PrivateKey
                ?: throw IllegalStateException(
                    "NEX BAAS private key is unavailable in Android Keystore."
                )
            val publicKey = store.getCertificate(KEY_ALIAS)?.publicKey as? RSAPublicKey
                ?: throw IllegalStateException(
                    "NEX BAAS public key is unavailable in Android Keystore."
                )
            return KeyMaterial(privateKey, publicKey)
        }

        private fun validateJwksUrl(value: String) {
            val uri = runCatching { URI(value) }.getOrNull()
            require(
                uri != null &&
                    uri.scheme.equals("https", ignoreCase = true) &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null &&
                    uri.fragment == null
            ) {
                "The NEX BAAS JWKS URL must be a provisioned HTTPS URL."
            }
        }
    }
}
