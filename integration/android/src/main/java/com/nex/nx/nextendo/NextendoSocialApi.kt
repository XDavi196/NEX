package com.nex.nx.nextendo

import org.json.JSONArray
import org.json.JSONObject

/**
 * Social data exposed to a player-authorized NEX client.
 *
 * The friends list uses Nextendo's account friends endpoint. OAuth remains owned by NEX:
 * the caller supplies the NEX access token and Nextendo validates the NEX Client ID.
 */
object NextendoSocialApi {
    data class Friend(
        val pid: Long,
        val username: String,
        val friendCode: String,
        val presenceStatus: Int,
        val appId: String,
        val appName: String,
        val imageBase64: String
    ) {
        fun presenceLabel(): String = when (presenceStatus) {
            2 -> {
                val activity = appName.ifBlank { appId }
                if (activity.isBlank()) "Jogando agora" else "Jogando · $activity"
            }
            1 -> "Online"
            0 -> "Offline"
            else -> "Presença indisponível"
        }
    }

    data class FriendList(
        val friends: List<Friend>,
        val requests: List<Friend>
    )

    data class UserInfo(
        val pid: Long,
        val username: String,
        val friendCode: String
    )

    /** Basic identity used immediately after OAuth succeeds. */
    fun fetchUserInfo(accessToken: String): UserInfo {
        val json = NextendoAuthClient.getUserInfo(accessToken)
        return UserInfo(
            pid = json.optLong("pid", 0L),
            username = json.optString("username")
                .ifBlank { json.optString("display_name") },
            friendCode = json.optString("friend_code")
        )
    }

    /**
     * Reads accepted friends from the OAuth-scoped /api/oauth/userinfo response.
     * The published response does not expose incoming requests or live presence.
     */
    fun fetchFriendList(accessToken: String): FriendList {
        require(accessToken.isNotBlank()) { "A conta Nextendo não está autenticada." }
        val json = NextendoAuthClient.getFriends(accessToken)
        return FriendList(
            friends = parseFriends(json.optJSONArray("friends")),
            requests = emptyList()
        )
    }

    fun fetchFriends(accessToken: String): List<Friend> =
        fetchFriendList(accessToken).friends

    internal fun parseFriends(array: JSONArray?): List<Friend> {
        if (array == null) return emptyList()
        val result = ArrayList<Friend>(array.length())

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val presence = item.optJSONObject("presence")
            val status = when {
                presence?.has("status") == true -> presence.optInt("status", -1)
                item.has("status") -> item.optInt("status", -1)
                else -> -1
            }

            val appId = presence?.optString("app_id").orEmpty()
                .ifBlank { item.optString("app_id") }
            val appName = presence?.optString("app_name").orEmpty()
                .ifBlank { item.optString("app_name") }
                .ifBlank { presence?.optString("app_detail").orEmpty() }
                .ifBlank { item.optString("app_detail") }
            val image = item.optString("image")
                .ifBlank { item.optString("image_base64") }

            result += Friend(
                pid = item.optLong("pid", 0L),
                username = item.optString("name")
                    .ifBlank { item.optString("username") }
                    .ifBlank { item.optString("display_name") },
                friendCode = item.optString("friend_code"),
                presenceStatus = status,
                appId = appId,
                appName = appName,
                imageBase64 = image
            )
        }

        return result.sortedWith(
            compareByDescending<Friend> { it.presenceStatus }
                .thenBy { it.username.lowercase() }
        )
    }
}
