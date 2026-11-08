package com.nex.nx.nextendo

import android.content.Context

/**
 * Serializes OAuth refresh-token rotation for the Android app.
 *
 * The account screen, presence heartbeat and game-token recovery share the
 * same rotating refresh token. A single synchronized path prevents multiple
 * workers from redeeming the same refresh token concurrently.
 */
object NextendoSessionRefresher {
    @Synchronized
    fun getValidSession(
        context: Context,
        minimumValiditySeconds: Long = 60L
    ): NextendoSessionStore.Session {
        require(minimumValiditySeconds >= 0L) {
            "minimumValiditySeconds cannot be negative."
        }

        val appContext = context.applicationContext
        val current = NextendoSessionStore.load(appContext)
            ?: throw IllegalStateException("Nenhuma conta Nextendo está conectada.")

        val now = System.currentTimeMillis() / 1000L
        if (current.accessTokenExpiresAtEpochSeconds > now + minimumValiditySeconds) {
            return current
        }

        return refreshCurrentSession(appContext, current)
    }

    /**
     * Recovers from an API 401 by refreshing only if the rejected access token
     * is still the one persisted. If another worker already rotated it, this
     * returns the newer session instead of redeeming the old refresh token.
     */
    @Synchronized
    fun refreshAfterUnauthorized(
        context: Context,
        rejectedAccessToken: String
    ): NextendoSessionStore.Session {
        require(rejectedAccessToken.isNotBlank()) {
            "The rejected OAuth access token is empty."
        }

        val appContext = context.applicationContext
        val current = NextendoSessionStore.load(appContext)
            ?: throw IllegalStateException("Nenhuma conta Nextendo está conectada.")

        if (current.accessToken != rejectedAccessToken) {
            return current
        }

        return refreshCurrentSession(appContext, current)
    }

    /**
     * The synchronized caller holds the lock through token exchange and
     * persistence. The server may rotate refresh tokens, so save the complete
     * response before another worker can read the session.
     */
    private fun refreshCurrentSession(
        context: Context,
        current: NextendoSessionStore.Session
    ): NextendoSessionStore.Session {
        if (current.refreshToken.isBlank()) {
            throw IllegalStateException(
                "A sessão Nextendo expirou ou foi recusada e não há refresh_token disponível. Entre novamente na sua conta."
            )
        }

        val refreshed = NextendoAuthClient.refreshAccessToken(current.refreshToken)
        NextendoSessionStore.saveOAuthTokens(context, refreshed)

        return NextendoSessionStore.load(context)
            ?: throw IllegalStateException(
                "Não foi possível recuperar a sessão Nextendo atualizada."
            )
    }
}
