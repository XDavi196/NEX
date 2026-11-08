package com.nex.nx.nextendo

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the Citron-compatible BAAS id_token boundary between the encrypted NEX
 * account session and Eden's ACC cache. A signer is activated only after its
 * public key exactly matches the live Nextendo BAAS JWKS.
 */
internal interface NextendoBaasProvisioningProvider {
    val signer: NextendoBaasSigner
    fun claimsFor(session: NextendoSessionStore.Session): Map<String, kotlinx.serialization.json.JsonElement>
}

internal object NextendoBaasRuntime {
    @Volatile
    private var provisioningProvider: NextendoBaasProvisioningProvider? = null

    @Volatile
    var diagnostic: String = "citron_signing_key_missing"
        private set

    private val activationInProgress = AtomicBoolean(false)
    private val activationExecutor = Executors.newSingleThreadExecutor()

    /**
     * Imports and validates the fixed Nextendo/Citron signer key. The supplied
     * PEM is persisted only after the matching public JWK is found at the
     * configured HTTPS JWKS endpoint.
     */
    fun installCitronSigningKey(context: Context, pem: String): String {
        val appContext = context.applicationContext
        val provider = createCitronProvider(appContext, pem)
        NextendoSessionStore.saveBaasSigningKeyPem(appContext, pem)
        provisioningProvider = provider
        diagnostic = "citron_baas_key_verified"

        val session = runCatching { NextendoSessionStore.load(appContext) }.getOrNull()
        refresh(appContext, session)
        return diagnostic
    }

    private fun createCitronProvider(
        context: Context,
        pem: String
    ): NextendoBaasProvisioningProvider {
        val signer = NextendoCitronBaasSigner.fromPem(pem)
        val client = NextendoNetworkRouting.createHttpClient(context.applicationContext)
        val registration = NextendoBaasJwksVerifier.fetchAndVerify(
            jwksUrl = NextendoCitronBaasProfile.JWKS_URL,
            expected = signer.publicJwk,
            client = client
        )
        require(registration.matches(signer.publicJwk)) {
            "A chave pública do PEM não corresponde ao kid publicado no JWKS da Nextendo."
        }
        return NextendoCitronBaasProvider(signer)
    }

    /**
     * Restores a saved signer without blocking the emulator UI. No random
     * per-installation key or DEN OAuth-token fallback is ever generated.
     */
    fun refresh(context: Context, session: NextendoSessionStore.Session?) {
        val appContext = context.applicationContext
        if (session == null) {
            clearCache("account_not_connected")
            return
        }

        val now = System.currentTimeMillis() / 1000L
        if (
            !session.nexGameToken.startsWith("nx2.") ||
            session.nexGameToken.length <= 4 ||
            session.nexGameTokenExpiresAtEpochSeconds <= now
        ) {
            clearCache("game_token_unavailable_or_expired")
            return
        }

        var provider = provisioningProvider
        if (provider == null) {
            val pem = runCatching {
                NextendoSessionStore.loadBaasSigningKeyPem(appContext)
            }.getOrNull()
            if (pem.isNullOrBlank()) {
                clearCache("citron_signing_key_missing")
                return
            }

            diagnostic = "citron_jwks_validation_pending"
            if (activationInProgress.compareAndSet(false, true)) {
                activationExecutor.execute {
                    val activated = runCatching { createCitronProvider(appContext, pem) }
                    activationInProgress.set(false)
                    activated.fold(
                        onSuccess = { restoredProvider ->
                            provisioningProvider = restoredProvider
                            diagnostic = "citron_baas_key_verified"
                            // A session may have changed while the JWKS request
                            // was in flight; never cache a token for the old player.
                            val latest = runCatching {
                                NextendoSessionStore.load(appContext)
                            }.getOrNull()
                            if (
                                latest != null &&
                                latest.pid == session.pid &&
                                latest.nexGameToken == session.nexGameToken
                            ) {
                                refresh(appContext, latest)
                            } else {
                                clearCache("account_changed_during_baas_restore")
                            }
                        },
                        onFailure = {
                            provisioningProvider = null
                            clearCache("citron_jwks_mismatch_or_unavailable")
                        }
                    )
                }
            }
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
            diagnostic = "citron_baas_id_token_cached"
        }.onFailure {
            NextendoNativeBridge.clearBaasIdToken()
            diagnostic = when (it) {
                is IllegalArgumentException -> "citron_baas_provisioning_invalid"
                else -> "citron_baas_signing_failed"
            }
        }
    }

    /** Clears only the guest-visible account token; the authorized signer remains installed. */
    fun clear() = clearCache("account_not_connected")

    fun removeProvider() {
        provisioningProvider = null
        clearCache("citron_signer_not_installed")
    }

    private fun clearCache(reason: String) {
        NextendoNativeBridge.clearBaasIdToken()
        diagnostic = reason
    }
}
