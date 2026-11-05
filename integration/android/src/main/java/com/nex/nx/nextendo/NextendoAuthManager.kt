package com.nex.nx.nextendo

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
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

    /**
     * Opens the NEX account panel. Profile data is requested using the user's
     * OAuth token and PID; no bot token or client secret is used.
     */
    fun openAccount(activity: Activity) {
        val stored = runCatching { NextendoSessionStore.load(activity) }.getOrNull()
        if (stored == null) {
            startLogin(activity)
            return
        }

        executor.execute {
            var activeSession = stored

            // Refresh the account session before profile calls when it is near expiry.
            if (activeSession.accessTokenExpiresAtEpochSeconds <=
                (System.currentTimeMillis() / 1000L) + 60L
            ) {
                runCatching {
                    NextendoAuthClient.refreshAccessToken(activeSession.refreshToken).also {
                        NextendoSessionStore.saveOAuthTokens(activity, it)
                        activeSession = NextendoSessionStore.load(activity) ?: activeSession
                    }
                }
            }

            // Obtain the separate game token if the account panel has no valid PID yet.
            if (activeSession.pid <= 0L ||
                activeSession.nexGameTokenExpiresAtEpochSeconds <=
                (System.currentTimeMillis() / 1000L) + 300L
            ) {
                runCatching {
                    NextendoAuthClient.fetchNexGameToken(activeSession.accessToken).also {
                        NextendoSessionStore.saveNexGameToken(activity, it)
                        activeSession = NextendoSessionStore.load(activity) ?: activeSession
                    }
                }
            }

            val profile = if (activeSession.pid > 0L) {
                runCatching {
                    NextendoAccountApi.fetchOwnProfile(activeSession.accessToken, activeSession.pid)
                }.getOrNull()
            } else {
                null
            }

            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post

                val username = profile?.username?.ifBlank { activeSession.username }
                    ?: activeSession.username.ifBlank { "Conta conectada" }
                val friendCode = profile?.friendCode?.ifBlank { activeSession.friendCode }
                    ?: activeSession.friendCode.ifBlank { "Ainda não disponível" }
                val pid = profile?.pid?.takeIf { it > 0L } ?: activeSession.pid
                val avatar = profile?.avatarUrl.orEmpty()

                val details = buildString {
                    append("Jogador: ")
                    append(username)
                    append("\nFriend Code: ")
                    append(friendCode)
                    append("\nPID: ")
                    append(if (pid > 0L) pid.toString() else "Aguardando token de jogo")
                    if (avatar.isNotBlank()) {
                        append("\n\nAvatar: ")
                        append(avatar)
                    }
                    if (profile == null && pid > 0L) {
                        append("\n\nO perfil não pôde ser atualizado agora. Os dados salvos são exibidos.")
                    }
                }

                AlertDialog.Builder(activity)
                    .setTitle("Perfil Nextendo")
                    .setMessage(details)
                    .setPositiveButton("Reconectar") { _, _ -> startLogin(activity) }
                    .setNeutralButton("Desconectar") { _, _ ->
                        runCatching { NextendoSessionStore.clear(activity) }
                        Toast.makeText(activity, "Conta Nextendo desconectada.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Fechar", null)
                    .show()
            }
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
