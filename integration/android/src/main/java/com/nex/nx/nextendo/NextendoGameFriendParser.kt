package com.nex.nx.nextendo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal data class NextendoGameFriend(
    val pid: Long,
    val networkServiceAccountId: Long,
    val username: String,
    val presenceStatus: Int,
    val appId: String,
    val appIdValue: Long,
    val appDetail: String,
    val appField: ByteArray,
    val friendCode: String = "",
    val imageBase64: String = ""
)

internal data class NextendoGameFriendSnapshot(
    val friends: List<NextendoGameFriend>,
    val requests: List<NextendoGameFriend>
)

/**
 * Parses the richer /api/friends payload returned for an nx2. game token.
 * This is deliberately separate from OAuth userinfo. Nextendo's reference
 * emulator binds its persistent player PID to the Switch NetworkServiceAccountId;
 * use that documented account mapping when the friend API omits an explicit NSA.
 * Presence fields themselves remain server-sourced and are never synthesized.
 */
internal object NextendoGameFriendParser {
    private const val MAX_APP_FIELD_BYTES = 0xC0
    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun parse(response: String): List<NextendoGameFriend> =
        parseSnapshot(response).friends

    fun parseSnapshot(response: String): NextendoGameFriendSnapshot {
        val root = runCatching { Json.parseToJsonElement(response) as? JsonObject }
            .getOrNull() ?: return NextendoGameFriendSnapshot(emptyList(), emptyList())

        fun readList(name: String): List<NextendoGameFriend> =
            (root[name] as? JsonArray)
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull(::parseFriend)
                .distinctBy { it.pid }
                .take(300)

        return NextendoGameFriendSnapshot(
            friends = readList("friends"),
            requests = readList("requests")
        )
    }

    private fun parseFriend(item: JsonObject): NextendoGameFriend? {
        val pid = item.longField("pid") ?: return null
        if (pid <= 0L) return null
        val username = item.stringField("name")
            .ifBlank { item.stringField("username") }
            .ifBlank { item.stringField("display_name") }
        if (username.isBlank()) return null

        // Friends and incoming requests can exist without a presence object. Do not
        // drop those records or manufacture an offline/playing status.
        val presence = item["presence"] as? JsonObject
        val status = presence?.intField("status") ?: -1
        if (status !in -1..2) return null

        // Prefer an explicit server-provided NSA. The existing PID fallback is
        // retained for the currently documented Nextendo account mapping.
        val nsa = item.hex64Field("nsa") ?: pid
        val appIdValue = presence?.hex64Field("app_id") ?: 0L

        val appFieldValue = presence?.get("app_field")
        val encodedAppField = when (appFieldValue) {
            null -> ""
            is JsonPrimitive -> {
                if (!appFieldValue.isString) return null
                appFieldValue.contentOrNull.orEmpty()
            }
            else -> return null
        }
        val appField = decodeBase64(encodedAppField) ?: return null
        if (appField.size > MAX_APP_FIELD_BYTES) return null

        return NextendoGameFriend(
            pid = pid,
            networkServiceAccountId = nsa,
            username = username,
            presenceStatus = status,
            appId = presence?.stringField("app_id").orEmpty(),
            appIdValue = appIdValue,
            appDetail = presence?.stringField("app_detail").orEmpty(),
            appField = appField,
            friendCode = item.stringField("friend_code"),
            imageBase64 = item.stringField("image")
                .ifBlank { item.stringField("image_base64") }
        )
    }

    /** Strict RFC 4648 decoder with bounded output; no Android-only API is required. */
    private fun decodeBase64(value: String): ByteArray? {
        if (value.isEmpty()) return byteArrayOf()
        if (value.any(Char::isWhitespace)) return null
        val encoded = value
        if (encoded.length > ((MAX_APP_FIELD_BYTES + 2) / 3) * 4) return null
        if (encoded.length % 4 != 0) return null

        val padding = when {
            encoded.endsWith("==") -> 2
            encoded.endsWith("=") -> 1
            else -> 0
        }
        val outputSize = encoded.length / 4 * 3 - padding
        if (outputSize !in 0..MAX_APP_FIELD_BYTES) return null

        val output = ByteArray(outputSize)
        var outputIndex = 0
        var index = 0
        while (index < encoded.length) {
            val finalBlock = index + 4 == encoded.length
            val c0 = BASE64.indexOf(encoded[index])
            val c1 = BASE64.indexOf(encoded[index + 1])
            val c2Char = encoded[index + 2]
            val c3Char = encoded[index + 3]
            val c2 = if (c2Char == '=') 0 else BASE64.indexOf(c2Char)
            val c3 = if (c3Char == '=') 0 else BASE64.indexOf(c3Char)

            if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) return null
            if ((!finalBlock && (c2Char == '=' || c3Char == '=')) ||
                (c2Char == '=' && c3Char != '=')
            ) return null
            if (c2Char == '=' && (c1 and 0x0F) != 0) return null
            if (c3Char == '=' && c2Char != '=' && (c2 and 0x03) != 0) return null

            val bits = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            if (outputIndex < output.size) output[outputIndex++] = (bits shr 16).toByte()
            if (c2Char != '=' && outputIndex < output.size) output[outputIndex++] = (bits shr 8).toByte()
            if (c3Char != '=' && outputIndex < output.size) output[outputIndex++] = bits.toByte()
            index += 4
        }

        if (outputIndex != output.size) return null
        return output
    }

    private fun JsonObject.stringField(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.longField(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.hex64Field(name: String): Long? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val value = primitive.contentOrNull ?: return null
        if (value.length != 16 || value.any { it.digitToIntOrNull(16) == null }) return null
        return value.toULongOrNull(16)?.takeIf { it != 0uL }?.toLong()
    }

    private fun JsonObject.intField(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull
}
