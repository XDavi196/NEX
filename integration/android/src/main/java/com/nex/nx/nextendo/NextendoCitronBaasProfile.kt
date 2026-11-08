package com.nex.nx.nextendo

import java.security.SecureRandom
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Public Citron/Nextendo BAAS id_token profile; contains no signing secret. */
internal object NextendoCitronBaasProfile {
    const val HOSTNAME = "e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com"
    const val ISSUER = "https://e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com"
    const val JWKS_URL = "https://e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com/1.0.0/certificates"
    const val AUDIENCE = "ed9e2f05d286f7b8"
    const val KEY_ID = "nextendo-baas-key-1"
    const val DEVICE_SERIAL = "XAW10000000000"
    const val TOKEN_LIFETIME_SECONDS = 3L * 60L * 60L

    fun claimsFor(session: NextendoSessionStore.Session): Map<String, JsonElement> {
        require(session.pid > 0L) {
            "A verified Nextendo PID is required for BAAS authentication."
        }
        require(session.nexGameToken.startsWith("nx2.") && session.nexGameToken.length > 4) {
            "A Nextendo-issued nx2. game token is required for BAAS authentication."
        }

        val now = System.currentTimeMillis() / 1000L
        val expiresAt = minOf(
            now + TOKEN_LIFETIME_SECONDS,
            session.nexGameTokenExpiresAtEpochSeconds
        )
        require(expiresAt > now) {
            "The Nextendo game token expires before a BAAS id_token can be issued."
        }

        // Citron sends the same random device ID at both the top-level and inside
        // the nintendo metadata object. It is not used as a PID or account identity.
        val deviceId = randomHex(16)
        val claims = linkedMapOf<String, JsonElement>(
            "sub" to JsonPrimitive(randomHex(16)),
            "aud" to JsonPrimitive(AUDIENCE),
            "iss" to JsonPrimitive(ISSUER),
            "typ" to JsonPrimitive("id_token"),
            "iat" to JsonPrimitive(now),
            "exp" to JsonPrimitive(expiresAt),
            "jti" to JsonPrimitive(UUID.randomUUID().toString()),
            "di" to JsonPrimitive(deviceId),
            "sn" to JsonPrimitive(DEVICE_SERIAL),
            "bs:did" to JsonPrimitive(randomHex(16)),
            "nintendo" to JsonObject(
                linkedMapOf(
                    "dt" to JsonPrimitive("NX Prod 1"),
                    "pc" to JsonPrimitive("HAC"),
                    "di" to JsonPrimitive(deviceId),
                    "sn" to JsonPrimitive(DEVICE_SERIAL),
                    "ist" to JsonPrimitive(false)
                )
            ),
            "hm" to JsonPrimitive(true)
        )
        return claims
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        val alphabet = "0123456789abcdef"
        return buildString(byteCount * 2) {
            bytes.forEach { value ->
                val unsigned = value.toInt() and 0xFF
                append(alphabet[unsigned ushr 4])
                append(alphabet[unsigned and 0x0F])
            }
        }
    }
}
