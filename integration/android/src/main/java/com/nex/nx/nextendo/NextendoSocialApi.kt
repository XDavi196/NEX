package com.nex.nx.nextendo

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only social API based on the documented OAuth userinfo endpoint.
 *
 * Friend mutations are intentionally not implemented here: the public docs
 * expose friend data through userinfo, while the exact OAuth contract for
 * mutation endpoints must be confirmed before enabling those actions.
 */
object NextendoSocialApi {
    data class Friend(
        val pid: Long,
        val username: String,
        val friendCode: String,
        val presenceStatus: Int?,
        val appId: String,
        val appDetail: String
    ) {
        fun presenceLabel(): String = when (presenceStatus) {
            2 -> {
                val activity = appDetail.ifBlank { appId }
                if (activity.isBlank()) "In game" else "In game · $activity"
            }
            1 -> "Online"
            0 -> "Offline"
            else -> "Status unavailable"
        }
    }

    data class UserInfo(
        val pid: Long,
        val username: String,
        val friendCode: String,
        val friends: List<Friend>
    )

    fun fetchUserInfo(accessToken: String): UserInfo {
        val json = NextendoAuthClient.getUserInfo(accessToken)
        val friendsArray = json.optJSONArray("friends") ?: JSONArray()
        val friends = ArrayList<Friend>(friendsArray.length())

        for (index in 0 until friendsArray.length()) {
            val item = friendsArray.optJSONObject(index) ?: continue
            val presence = item.optJSONObject("presence")
            val status = when {
                presence?.has("status") == true -> presence.optInt("status")
                item.has("status") -> item.optInt("status")
                else -> null
            }

            val appId = presence?.optString("app_id")
                ?.ifBlank { item.optString("app_id") }.orEmpty()
            val appDetail = presence?.optString("app_detail")
                ?.ifBlank { item.optString("app_detail") }.orEmpty()

            friends += Friend(
                pid = item.optLong("pid", 0L),
                username = item.optString("username")
                    .ifBlank { item.optString("display_name") },
                friendCode = item.optString("friend_code"),
                presenceStatus = status,
                appId = appId,
                appDetail = appDetail
            )
        }

        return UserInfo(
            pid = json.optLong("pid", 0L),
            username = json.optString("username")
                .ifBlank { json.optString("display_name") },
            friendCode = json.optString("friend_code"),
            friends = friends.sortedBy { it.username.lowercase() }
        )
    }

    fun fetchFriends(accessToken: String): List<Friend> =
        fetchUserInfo(accessToken).friends
}
