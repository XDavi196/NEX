package com.nex.nx.nextendo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Parses /api/me's nsa field only when it is a 16-character hexadecimal string. */
internal object NextendoAccountNsaParser {
    fun parse(response: String): Long {
        val root = runCatching { Json.parseToJsonElement(response) as? JsonObject }
            .getOrNull() ?: return 0L
        val value = (root["nsa"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull ?: return 0L
        if (value.length != 16 || value.any { it.digitToIntOrNull(16) == null }) return 0L
        return value.toULongOrNull(16)?.takeIf { it != 0uL }?.toLong() ?: 0L
    }
}
