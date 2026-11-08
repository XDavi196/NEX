package com.nex.nx.nextendo

/** Read-only profile fields exposed to a public OAuth client through userinfo. */
object NextendoAccountApi {
    data class PlayerProfile(
        val pid: Long,
        val username: String,
        val friendCode: String,
        val avatarUrl: String
    )

    fun fetchOwnProfile(accessToken: String, pid: Long): PlayerProfile {
        require(accessToken.isNotBlank()) { "A conta Nextendo não está autenticada." }
        require(pid > 0L) { "O PID do jogador ainda não está disponível." }

        val json = NextendoAuthClient.getUserInfo(accessToken)
        val returnedPid = json.optLong("pid", 0L)
        require(returnedPid == pid) {
            "O Nextendo devolveu uma identidade diferente da conta conectada."
        }

        return PlayerProfile(
            pid = returnedPid,
            username = json.optString("display_name")
                .ifBlank { json.optString("username") },
            friendCode = json.optString("friend_code"),
            // The published OAuth userinfo response does not expose an avatar URL.
            avatarUrl = ""
        )
    }
}
