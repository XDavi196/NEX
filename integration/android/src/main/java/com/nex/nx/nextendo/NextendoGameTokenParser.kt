package com.nex.nx.nextendo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Validates the documented game-token response without interpreting the opaque server-signed nx2 token. */
internal object NextendoGameTokenParser {
    fun parse(response: String, nowEpochSeconds: Long): NextendoAuthClient.NexGameToken {
        val payload = Json.parseToJsonElement(response).jsonObject
        fun requiredString(field: String): String =
            payload[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Nextendo game-token response is missing $field.")
        fun requiredLong(field: String): Long =
            payload[field]?.jsonPrimitive?.longOrNull
                ?: throw IllegalArgumentException("Nextendo game-token response is missing a valid $field.")

        val token = requiredString("nex_token")
        require(token.startsWith("nx2.") && token.length > 4) { "Nextendo returned a malformed nx2 game token." }
        val pid = requiredLong("pid")
        require(pid > 0L) { "Nextendo returned an invalid player PID." }
        val expiresIn = requiredLong("expires_in")
        require(expiresIn > 0L) { "Nextendo returned an invalid game-token lifetime." }
        require(nowEpochSeconds >= 0L && nowEpochSeconds <= Long.MAX_VALUE - expiresIn) {
            "Nextendo game-token expiry is outside the supported time range."
        }
        val username = requiredString("username")
        val friendCode = payload["friend_code"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return NextendoAuthClient.NexGameToken(
            token = token,
            expiresAtEpochSeconds = nowEpochSeconds + expiresIn,
            pid = pid,
            username = username,
            friendCode = friendCode
        )
    }
}
