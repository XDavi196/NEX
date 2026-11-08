package com.nex.nx.nextendo

/**
 * Social data exposed to a player-authorized NEX public client.
 *
 * The published OAuth userinfo route exposes accepted friends, but currently
 * does not expose incoming requests or presence. Missing presence stays unknown.
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
     * No presence or incoming-request data is inferred when the API omits it.
     */
    fun fetchFriendList(accessToken: String): FriendList =
        NextendoFriendListParser.parse(NextendoAuthClient.getUserInfoRaw(accessToken))

    fun fetchFriends(accessToken: String): List<Friend> =
        fetchFriendList(accessToken).friends
}
