package com.nex.nx.nextendo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsIntent
import java.util.concurrent.Executors

/**
 * Activity-facing Nextendo login coordinator.
 *
 * The PKCE verifier/state are persisted encrypted before the browser opens so
 * the flow survives the Android process being reclaimed while the user signs in.
 */
object NextendoAuthManager {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class LoginOutcome(
        val pid: Long,
        val username: String,
        val friendCode: String,
        val gameTokenReady: Boolean
    )

    fun startLogin(activity: Activity) {
        val pending = NextendoAuthClient.beginAuthorization()
        NextendoSessionStore.savePendingAuthorization(
            activity,
            pending
        )

        val customTab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        try {
            customTab.launchUrl(activity, pending.authorizationUri)
        } catch (_: Exception) {
            // A browser intent is a safe fallback on devices without Custom Tabs.
            activity.startActivity(Intent(Intent.ACTION_VIEW, pending.authorizationUri))
        }
    }

    fun completeLogin(
        context: Context,
        callbackUri: Uri?,
        onComplete: (Result<LoginOutcome>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                require(callbackUri != null) { "O retorno do Nextendo está vazio." }
                require(
                    callbackUri.scheme == "com.nex.nx" &&
                        callbackUri.host == "oauth" &&
                        callbackUri.path == "/callback"
                ) { "O endereço de retorno não corresponde ao NEX." }

                callbackUri.getQueryParameter("error")?.let { error ->
                    throw IllegalStateException(
                        "O Nextendo não autorizou o acesso (erro: $error)."
                    )
                }

                val code = callbackUri.getQueryParameter("code")
                val returnedState = callbackUri.getQueryParameter("state")
                require(!code.isNullOrBlank()) {
                    "O Nextendo não devolveu o código de autorização."
                }

                val savedPending = NextendoSessionStore.loadPendingAuthorization(context)
                    ?: throw IllegalStateException(
                        "A autorização expirou. Inicie o login novamente."
                    )

                val pending = NextendoAuthClient.PendingAuthorization(
                    codeVerifier = savedPending.codeVerifier,
                    state = savedPending.state,
                    authorizationUri = Uri.EMPTY
                )

                val tokens = NextendoAuthClient.exchangeAuthorizationCode(
                    code = code,
                    returnedState = returnedState,
                    pending = pending
                )

                // Persist the account session before requesting the separate game token.
                NextendoSessionStore.saveOAuthTokens(context, tokens)
                NextendoSessionStore.clearPendingAuthorization(context)

                val gameToken = runCatching {
                    NextendoAuthClient.fetchNexGameToken(tokens.accessToken)
                }.getOrNull()

                if (gameToken != null) {
                    NextendoSessionStore.saveNexGameToken(context, gameToken)
                }

                LoginOutcome(
                    pid = gameToken?.pid ?: 0L,
                    username = gameToken?.username.orEmpty(),
                    friendCode = gameToken?.friendCode.orEmpty(),
                    gameTokenReady = gameToken != null
                )
            }

            if (result.isFailure) {
                // Authorization codes are single-use; force a fresh transaction.
                runCatching { NextendoSessionStore.clearPendingAuthorization(context) }
            }

            mainHandler.post { onComplete(result) }
        }
    }

    fun refreshSession(
        context: Context,
        onComplete: (Result<LoginOutcome>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val current = NextendoSessionStore.load(context)
                    ?: throw IllegalStateException("Nenhuma conta Nextendo está conectada.")

                val refreshed = NextendoAuthClient.refreshAccessToken(current.refreshToken)
                NextendoSessionStore.saveOAuthTokens(context, refreshed)

                val latest = NextendoSessionStore.load(context)
                    ?: throw IllegalStateException("Não foi possível recuperar a sessão Nextendo.")

                val gameToken = if (
                    latest.nexGameToken.isBlank() ||
                    latest.nexGameTokenExpiresAtEpochSeconds <=
                        (System.currentTimeMillis() / 1000L) + 300L
                ) {
                    NextendoAuthClient.fetchNexGameToken(refreshed.accessToken).also {
                        NextendoSessionStore.saveNexGameToken(context, it)
                    }
                } else {
                    null
                }

                val session = NextendoSessionStore.load(context)
                LoginOutcome(
                    pid = gameToken?.pid ?: session?.pid ?: 0L,
                    username = gameToken?.username ?: session?.username.orEmpty(),
                    friendCode = gameToken?.friendCode ?: session?.friendCode.orEmpty(),
                    gameTokenReady = session?.nexGameToken?.startsWith("nx2.") == true
                )
            }

            mainHandler.post { onComplete(result) }
        }
    }
}
