package com.nex.nx.nextendo

import android.content.Context

/**
 * Boundary for a future Nextendo-approved game-authentication provider.
 *
 * The public contract documents the server-issued nx2. token and its nnex claim,
 * but does not authorize a client-generated BAAS signer for NEX. No implementation
 * is installed until Nextendo supplies a supported NEX-specific flow.
 */
internal fun interface NextendoBaasTokenProvider {
    fun createLoginIdToken(session: NextendoSessionStore.Session): String
}

internal object NextendoBaasRuntime {
    @Volatile
    var diagnostic: String = "nex_baas_contract_not_provisioned"
        private set

    /** Intentionally null until Nextendo provides a supported NEX game-auth contract. */
    private fun currentProvider(): NextendoBaasTokenProvider? = null

    fun refresh(context: Context, session: NextendoSessionStore.Session?) {
        val cleanup = runCatching {
            NextendoSessionStore.clearLegacyBaasSigningKey(context.applicationContext)
        }
        NextendoNativeBridge.clearBaasIdToken()

        val now = System.currentTimeMillis() / 1000L
        diagnostic = when {
            cleanup.isFailure -> "legacy_baas_key_cleanup_failed"
            session == null -> "account_not_connected"
            !session.nexGameToken.startsWith("nx2.") ||
                session.nexGameToken.length <= 4 ||
                session.nexGameTokenExpiresAtEpochSeconds <= now ->
                "game_token_unavailable_or_expired"
            currentProvider() == null -> "nex_baas_contract_not_provisioned"
            else -> "nex_baas_provider_not_authorized"
        }
    }

    fun clear() {
        NextendoNativeBridge.clearBaasIdToken()
        diagnostic = "account_not_connected"
    }

    fun removeProvider() {
        NextendoNativeBridge.clearBaasIdToken()
        diagnostic = "nex_baas_provider_not_installed"
    }
}
