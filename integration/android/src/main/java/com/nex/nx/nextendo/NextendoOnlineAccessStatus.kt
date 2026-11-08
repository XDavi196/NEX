package com.nex.nx.nextendo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class NextendoOnlineAccessStatus(
    val allow: Boolean,
    val reason: String
)

/** Strict parser for the public, read-only GET /api/online-status contract. */
internal object NextendoOnlineAccessStatusParser {
    private val allowedReasons = setOf(
        "", "unknown", "disabled", "unverified", "discord_unlinked", "elsewhere"
    )

    fun parse(response: String): NextendoOnlineAccessStatus {
        val root = runCatching {
            Json.parseToJsonElement(response) as? JsonObject
        }.getOrNull() ?: throw IllegalArgumentException(
            "Nextendo online-status response is not a JSON object."
        )

        val allowPrimitive = root["allow"] as? JsonPrimitive
            ?: throw IllegalArgumentException("Nextendo online-status is missing allow.")
        require(!allowPrimitive.isString) {
            "Nextendo online-status allow must be a JSON boolean."
        }
        val allow = allowPrimitive.booleanOrNull
            ?: throw IllegalArgumentException("Nextendo online-status allow must be a JSON boolean.")

        val reasonPrimitive = root["reason"] as? JsonPrimitive
            ?: throw IllegalArgumentException("Nextendo online-status is missing reason.")
        require(reasonPrimitive.isString) {
            "Nextendo online-status reason must be a string."
        }
        val reason = reasonPrimitive.content
        require(reason in allowedReasons) {
            "Nextendo online-status returned an unknown gate reason."
        }
        require(if (allow) reason.isEmpty() else reason.isNotEmpty()) {
            "Nextendo online-status allow and reason are inconsistent."
        }
        return NextendoOnlineAccessStatus(allow, reason)
    }
}
