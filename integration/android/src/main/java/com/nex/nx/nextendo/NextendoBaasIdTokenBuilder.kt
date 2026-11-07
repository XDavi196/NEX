package com.nex.nx.nextendo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Signing boundary for the BAAS id_token sent through ACC to a game's NEX login.
 *
 * Nextendo's public protocol contract requires the nx2. game token in the JWT's
 * custom "nnex" claim. NEX must obtain its own registered RS256 signing material
 * and issuer/JWKS configuration before this boundary can be connected to ACC.
 *
 * No private key, fallback key or other emulator's signing material is bundled here.
 */
internal interface NextendoBaasSigner {
    /** NEX-specific key ID registered by Nextendo in the BAAS JWKS. */
    val keyId: String

    /** Signs the exact ASCII compact-JWT signing input using the NEX-issued RSA key. */
    fun signRs256(signingInput: ByteArray): ByteArray
}

internal object NextendoBaasIdTokenBuilder {
    /**
     * Builds a compact RS256 id_token using claims supplied by a verified NEX
     * BAAS contract provider. This method does not invent sub/iss/aud/jku/device
     * claims: the caller must supply the exact values issued for NEX.
     */
    fun build(
        claims: Map<String, JsonElement>,
        gameToken: String,
        signer: NextendoBaasSigner
    ): String {
        require(gameToken.startsWith("nx2.") && gameToken.length > 4) {
            "A Nextendo-issued nx2. game token is required for the nnex claim."
        }
        require(signer.keyId.isNotBlank()) {
            "The NEX BAAS signing key ID has not been provisioned."
        }
        require("nnex" !in claims) {
            "The nnex claim is reserved for the validated Nextendo game token."
        }

        requireHttpsClaim(claims, "iss")
        requireHttpsClaim(claims, "jku")
        requireNonBlankStringClaim(claims, "aud")
        requireNonBlankStringClaim(claims, "sub")

        val header = JsonObject(
            linkedMapOf(
                "alg" to JsonPrimitive("RS256"),
                "kid" to JsonPrimitive(signer.keyId),
                "typ" to JsonPrimitive("id_token")
            )
        )
        val payload = JsonObject(
            LinkedHashMap(claims).apply {
                put("nnex", JsonPrimitive(gameToken))
            }
        )

        val encodedHeader = base64Url(header.toString().toByteArray(StandardCharsets.UTF_8))
        val encodedPayload = base64Url(payload.toString().toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$encodedHeader.$encodedPayload"
            .toByteArray(StandardCharsets.US_ASCII)
        val signature = signer.signRs256(signingInput)
        require(signature.isNotEmpty()) {
            "The NEX BAAS signer returned an empty RS256 signature."
        }

        return encodedHeader + "." + encodedPayload + "." + base64Url(signature)
    }

    private fun requireHttpsClaim(claims: Map<String, JsonElement>, name: String) {
        val value = requireNonBlankStringClaim(claims, name)
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "The NEX BAAS $name claim must be the HTTPS URL provisioned by Nextendo."
        }
    }

    private fun requireNonBlankStringClaim(
        claims: Map<String, JsonElement>,
        name: String
    ): String {
        val value = (claims[name] as? JsonPrimitive)?.contentOrNull
        require(!value.isNullOrBlank()) {
            "The NEX BAAS $name claim has not been provisioned."
        }
        return value
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
