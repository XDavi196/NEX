package com.nex.nx.nextendo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Public, aggregate Nextendo Network counters returned by GET /api/stats. */
internal data class NextendoNetworkStats(
    val onlinePlayers: Long,
    val totalRegistered: Long,
    val gamesOnline: Long
)

internal object NextendoNetworkStatsParser {
    fun parse(response: String): NextendoNetworkStats {
        val root = runCatching {
            Json.parseToJsonElement(response) as? JsonObject
        }.getOrNull() ?: throw IllegalArgumentException("Nextendo stats response is not a JSON object.")

        fun count(name: String): Long {
            val primitive = root[name] as? JsonPrimitive
                ?: throw IllegalArgumentException("Nextendo stats response is missing $name.")
            require(!primitive.isString) {
                "Nextendo stats field $name must be a JSON integer, not a string."
            }
            val value = primitive.longOrNull
                ?: throw IllegalArgumentException("Nextendo stats field $name is not an integer.")
            require(value >= 0L) { "Nextendo stats field $name cannot be negative." }
            return value
        }

        return NextendoNetworkStats(
            onlinePlayers = count("online_players"),
            totalRegistered = count("total_registered"),
            gamesOnline = count("games_online")
        )
    }
}
