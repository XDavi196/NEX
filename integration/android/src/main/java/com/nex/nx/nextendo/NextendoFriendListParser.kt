package com.nex.nx.nextendo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses OAuth userinfo into the friend-cache model. This remains independent
 * from android.org.json so the response contract can be tested on the JVM.
 */
internal object NextendoFriendListParser {
    fun parse(response: String): NextendoSocialApi.FriendList {
        val root = runCatching { Json.parseToJsonElement(response) as? JsonObject }
            .getOrNull()
            ?: return NextendoSocialApi.FriendList(emptyList(), emptyList())

        val friends = (root["friends"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .map(::parseFriend)
            .sortedWith(
                compareByDescending<NextendoSocialApi.Friend> { it.presenceStatus }
                    .thenBy { it.username.lowercase() }
            )

        // Published OAuth userinfo currently exposes accepted friends only.
        return NextendoSocialApi.FriendList(friends = friends, requests = emptyList())
    }

    private fun parseFriend(item: JsonObject): NextendoSocialApi.Friend {
        val presence = item["presence"] as? JsonObject
        val status = presence?.intField("status") ?: item.intField("status") ?: -1

        val appId = presence?.stringField("app_id").orEmpty()
            .ifBlank { item.stringField("app_id") }
        val appName = presence?.stringField("app_name").orEmpty()
            .ifBlank { item.stringField("app_name") }
            .ifBlank { presence?.stringField("app_detail").orEmpty() }
            .ifBlank { item.stringField("app_detail") }
        val image = item.stringField("image").ifBlank { item.stringField("image_base64") }

        return NextendoSocialApi.Friend(
            pid = item.longField("pid") ?: 0L,
            username = item.stringField("name")
                .ifBlank { item.stringField("username") }
                .ifBlank { item.stringField("display_name") },
            friendCode = item.stringField("friend_code"),
            presenceStatus = status,
            appId = appId,
            appName = appName,
            imageBase64 = image
        )
    }

    private fun JsonObject.stringField(name: String): String =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
            .orEmpty()

    private fun JsonObject.longField(name: String): Long? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

    private fun JsonObject.intField(name: String): Int? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
}
