package com.nex.nx.nextendo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses a public-client OAuth token response without requiring a refresh token.
 *
 * The currently published Nextendo account-server source returns access_token,
 * token_type, expires_in and scope for third-party authorization_code grants,
 * but does not include refresh_token. The parser therefore treats refresh as
 * optional and never fabricates a replacement.
 */
internal object NextendoOAuthTokenParser {
    fun parse(response: String, nowEpochSeconds: Long): NextendoAuthClient.OAuthTokens {
        require(nowEpochSeconds >= 0L) { "The current time is outside the supported range." }
        val payload = Json.parseToJsonElement(response).jsonObject

        fun requiredString(field: String): String =
            payload[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Nextendo OAuth response is missing $field.")

        val accessToken = requiredString("access_token")
        val refreshToken = payload["refresh_token"]
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()
        val tokenType = payload["token_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (tokenType.isNotBlank()) {
            require(tokenType.equals("Bearer", ignoreCase = true)) {
                "Nextendo returned an unsupported OAuth token type."
            }
        }

        val expiresIn = payload["expires_in"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("Nextendo OAuth response is missing a valid expires_in.")
        require(expiresIn > 0L) { "Nextendo returned an invalid access-token lifetime." }
        require(nowEpochSeconds <= Long.MAX_VALUE - expiresIn) {
            "Nextendo OAuth expiry is outside the supported time range."
        }

        return NextendoAuthClient.OAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = nowEpochSeconds + expiresIn
        )
    }
}
