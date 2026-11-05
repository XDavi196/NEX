package com.nex.nx.nextendo

import android.content.Context

/**
 * Serializes OAuth refresh-token rotation for the Android app.
 *
 * The account screen and presence heartbeat share the same rotating refresh
 * token. A single synchronized path prevents both workers from trying to
 * redeem the same refresh token at the same time.
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

        // Keep the synchronized section through the network exchange and token
        // persistence: refresh tokens rotate, so the old value must not be reused.
        val refreshed = NextendoAuthClient.refreshAccessToken(current.refreshToken)
        NextendoSessionStore.saveOAuthTokens(appContext, refreshed)

        return NextendoSessionStore.load(appContext)
            ?: throw IllegalStateException("Não foi possível recuperar a sessão Nextendo atualizada.")
    }
}
