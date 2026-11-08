package com.nex.nx.nextendo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/**
 * Parser for the public GET /api/online-counts contract:
 * { "counts": { "<16-hex-title-id>": <non-negative integer> } }.
 *
 * Missing title IDs remain absent. A title not published by the server is
 * unknown, not an implied zero-player result.
 */
internal object NextendoOnlineCountsParser {
    private val titleIdPattern = Regex("^[0-9a-fA-F]{16}$")
    private const val MAX_TITLE_ENTRIES = 512

    fun parse(response: String): Map<String, Int> {
        val root = runCatching {
            Json.parseToJsonElement(response) as? JsonObject
        }.getOrNull() ?: throw IllegalArgumentException(
            "Nextendo online-counts response is not a JSON object."
        )

        val counts = root["counts"] as? JsonObject
            ?: throw IllegalArgumentException(
                "Nextendo online-counts response is missing its counts object."
            )
        require(counts.size <= MAX_TITLE_ENTRIES) {
            "Nextendo online-counts response has too many title entries."
        }

        val result = linkedMapOf<String, Int>()
        for ((rawTitleId, rawCount) in counts) {
            require(titleIdPattern.matches(rawTitleId)) {
                "Nextendo online-counts contains an invalid title ID."
            }
            val titleId = rawTitleId.lowercase(Locale.ROOT)
            require(!result.containsKey(titleId)) {
                "Nextendo online-counts contains duplicate normalized title IDs."
            }

            val primitive = rawCount as? JsonPrimitive
                ?: throw IllegalArgumentException("Nextendo player count must be an integer.")
            require(!primitive.isString) {
                "Nextendo player count must be a JSON integer, not a string."
            }
            val count = primitive.intOrNull
                ?: throw IllegalArgumentException("Nextendo player count is not an integer.")
            require(count >= 0) { "Nextendo player count cannot be negative." }
            result[titleId] = count
        }
        return result
    }
}
