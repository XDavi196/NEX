package com.nex.nx.nextendo

/** Selects only an unexpired OAuth credential explicitly granted the presence scope. */
internal object NextendoPresenceAuthorization {
    fun usableAccessToken(
        accessToken: String,
        grantedScopes: Set<String>,
        expiresAtEpochSeconds: Long,
        nowEpochSeconds: Long
    ): String? {
        if (accessToken.isBlank() || accessToken.startsWith("nx2.")) return null
        if ("presence" !in grantedScopes) return null
        if (expiresAtEpochSeconds <= nowEpochSeconds) return null
        return accessToken
    }
}
