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
 * Nextendo's public core extracts the string-valued "nnex" payload claim from
 * the AuthenticationInfo DataHolder in LoginEx.extraData. The public BAAS-JWKS
 * service resolves the RSA public key from the JWS "jku" header and matching
 * "kid". Those values must be provisioned for NEX; no private key is bundled.
 */
internal interface NextendoBaasSigner {
    /** NEX-specific key ID registered in the BAAS JWKS. */
    val keyId: String

    /** HTTPS URL that publishes this signer's matching public JWK. */
    val jwksUrl: String

    /** Signs the exact ASCII compact-JWT signing input using the NEX-issued RSA key. */
    fun signRs256(signingInput: ByteArray): ByteArray
}

internal object NextendoBaasIdTokenBuilder {
    /**
     * Builds a compact RS256 id_token using claims supplied by a verified NEX
     * provisioning provider. The JWK Set URL is a JOSE header parameter ("jku"),
     * not an account payload claim. No sub/iss/aud values are invented here.
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

        requireHttpsUrl(claims["iss"]?.let(::jsonString), "iss")
        requireHttpsUrl(signer.jwksUrl, "jku")
        requireNonBlankStringClaim(claims, "aud")
        requireNonBlankStringClaim(claims, "sub")
        require(claims["hm"] == JsonPrimitive(true)) {
            "The NEX BAAS hm claim must match the public AuthenticationInfo contract."
        }

        val header = JsonObject(
            linkedMapOf(
                "alg" to JsonPrimitive("RS256"),
                "kid" to JsonPrimitive(signer.keyId),
                "typ" to JsonPrimitive("id_token"),
                "jku" to JsonPrimitive(signer.jwksUrl)
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

    private fun jsonString(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.contentOrNull

    private fun requireHttpsUrl(value: String?, name: String) {
        val uri = value?.let { runCatching { URI(it) }.getOrNull() }
        require(
            uri != null &&
                uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        ) {
            "The NEX BAAS $name must be an HTTPS URL provisioned for NEX."
        }
    }

    private fun requireNonBlankStringClaim(
        claims: Map<String, JsonElement>,
        name: String
    ): String {
        val value = jsonString(claims[name])
        require(!value.isNullOrBlank()) {
            "The NEX BAAS $name claim has not been provisioned."
        }
        return value
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
