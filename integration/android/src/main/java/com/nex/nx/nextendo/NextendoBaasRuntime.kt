package com.nex.nx.nextendo

import kotlinx.serialization.json.JsonElement

/**
 * Owns the boundary between a server-issued nx2. credential and Eden's ACC
 * id-token cache. No signer is installed by default: a provisioned NEX
 * BAAS signing provider must be supplied before any JWT can reach the guest.
 */
internal interface NextendoBaasProvisioningProvider {
    val signer: NextendoBaasSigner

    /** Supplies the NEX-registered iss/aud/sub/hm and other server-required claims. */
    fun claimsFor(session: NextendoSessionStore.Session): Map<String, JsonElement>
}

internal object NextendoBaasRuntime {
    @Volatile
    private var provisioningProvider: NextendoBaasProvisioningProvider? = null

    @Volatile
    var diagnostic: String = "signer_not_provisioned"
        private set

    /** Called only by a future NEX provisioning component with its own approved signer. */
    fun installProvider(provider: NextendoBaasProvisioningProvider) {
        provisioningProvider = provider
        diagnostic = "signer_installed_waiting_for_game_token"
    }

    fun removeProvider() {
        provisioningProvider = null
        clearCache("signer_not_provisioned")
    }

    fun refresh(session: NextendoSessionStore.Session?) {
        if (session == null) {
            clearCache("account_not_connected")
            return
        }
        val now = System.currentTimeMillis() / 1000L
        if (!session.nexGameToken.startsWith("nx2.") ||
            session.nexGameToken.length <= 4 ||
            session.nexGameTokenExpiresAtEpochSeconds <= now
        ) {
            clearCache("game_token_unavailable_or_expired")
            return
        }

        val provider = provisioningProvider
        if (provider == null) {
            clearCache("signer_not_provisioned")
            return
        }

        runCatching {
            val jwt = NextendoBaasIdTokenBuilder.build(
                claims = provider.claimsFor(session),
                gameToken = session.nexGameToken,
                signer = provider.signer
            )
            // Validate the exact AuthenticationInfo/DataHolder contract before
            // making the compact JWT visible through ACC's LoadIdTokenCache.
            NextendoBaasAuthenticationInfo.encodeLoginExtraData(
                idToken = jwt,
                expectedGameToken = session.nexGameToken
            )
            NextendoNativeBridge.updateBaasIdToken(jwt)
        }.onSuccess {
            diagnostic = "baas_id_token_cached"
        }.onFailure {
            NextendoNativeBridge.clearBaasIdToken()
            diagnostic = when (it) {
                is IllegalArgumentException -> "baas_provisioning_invalid"
                else -> "baas_signing_failed"
            }
        }
    }

    fun clear() = clearCache("account_not_connected")

    private fun clearCache(reason: String) {
        NextendoNativeBridge.clearBaasIdToken()
        diagnostic = reason
    }
}
