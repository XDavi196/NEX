package com.nex.nx.nextendo

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PRUDP-Lite CONNECT proof used by the public Nextendo server implementation.
 *
 * This is a legacy protocol requirement (MD5 + HMAC-MD5), not a general-purpose
 * authentication design. The access key must be supplied by the title/runtime
 * configuration. It is never stored in source, logged, or guessed here.
 */
internal object NextendoPrudpLiteAuthenticator {
    private const val SIGNATURE_BYTES = 16

    fun signConnect(accessKey: String, connectionSignature: ByteArray): ByteArray {
        require(accessKey.isNotBlank()) { "A title PRUDP access key is required." }
        require(connectionSignature.size == SIGNATURE_BYTES) {
            "The PRUDP-Lite connection signature must contain exactly 16 bytes."
        }

        val key = MessageDigest.getInstance("MD5")
            .digest(accessKey.toByteArray(Charsets.UTF_8))
        val signedData = ByteArray(key.size + connectionSignature.size)
        System.arraycopy(key, 0, signedData, 0, key.size)
        System.arraycopy(connectionSignature, 0, signedData, key.size, connectionSignature.size)

        return Mac.getInstance("HmacMD5").run {
            init(SecretKeySpec(key, "HmacMD5"))
            doFinal(signedData)
        }
    }
}
