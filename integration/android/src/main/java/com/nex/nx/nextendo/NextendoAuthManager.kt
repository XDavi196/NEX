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
 * PKCE verifier/state are persisted encrypted before the browser opens so the
 * flow survives Android reclaiming the process during browser authentication.
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
        NextendoSessionStore.savePendingAuthorization(activity, pending)

        val customTab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        try {
            customTab.launchUrl(activity, pending.authorizationUri)
        } catch (_: Exception) {
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

                NextendoSessionStore.saveOAuthTokens(context, tokens)
                NextendoSessionStore.clearPendingAuthorization(context)

                val gameToken = runCatching {
                    NextendoAuthClient.fetchNexGameToken(tokens.accessToken)
                }.getOrNull()

                if (gameToken != null) {
                    NextendoSessionStore.saveNexGameToken(context, gameToken)
                }

                // App-level online presence is independent from game login.
                NextendoPresencePublisher.start(context)

                val savedSession = NextendoSessionStore.load(context)
                LoginOutcome(
                    pid = gameToken?.pid ?: savedSession?.pid ?: 0L,
                    username = gameToken?.username ?: savedSession?.username.orEmpty(),
                    friendCode = gameToken?.friendCode ?: savedSession?.friendCode.orEmpty(),
                    gameTokenReady = savedSession?.nexGameToken?.startsWith("nx2.") == true
                )
            }

            if (result.isFailure) {
                runCatching { NextendoSessionStore.clearPendingAuthorization(context) }
            }

            mainHandler.post { onComplete(result) }
        }
    }

    /** Opens the NEX account panel. */
    fun openAccount(activity: Activity) {
        val stored = runCatching { NextendoSessionStore.load(activity) }.getOrNull()
        if (stored == null) {
            startLogin(activity)
            return
        }
        NextendoPresencePublisher.start(activity)

        executor.execute {
            val activeSession = refreshSessionIfNeeded(activity, stored)
            val profile = if (activeSession.pid > 0L) {
                runCatching {
                    NextendoAccountApi.fetchOwnProfile(activeSession.accessToken, activeSession.pid)
                }.getOrNull()
            } else {
                null
            }

            // Immutable snapshot avoids the Kotlin smart-cast CI error caused by
            // capturing the mutable session variable inside this UI closure.
            val displaySession = activeSession
            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post

                val username = profile?.username?.ifBlank { displaySession.username }
                    ?: displaySession.username.ifBlank { "Conta conectada" }
                val friendCode = profile?.friendCode?.ifBlank { displaySession.friendCode }
                    ?: displaySession.friendCode.ifBlank { "Ainda não disponível" }
                val pid = profile?.pid?.takeIf { it > 0L } ?: displaySession.pid
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
                    .setPositiveButton("Amigos") { _, _ -> openFriends(activity) }
                    .setNeutralButton("Desconectar") { _, _ ->
                        NextendoPresencePublisher.stop(activity)
                        runCatching { NextendoSessionStore.clear(activity) }
                        Toast.makeText(
                            activity,
                            "Conta Nextendo desconectada.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("Fechar", null)
                    .show()
            }
        }
    }

    /** Displays the consented friend list returned by /api/oauth/userinfo. */
    fun openFriends(activity: Activity) {
        val stored = runCatching { NextendoSessionStore.load(activity) }.getOrNull()
        if (stored == null) {
            startLogin(activity)
            return
        }

        executor.execute {
            val result = runCatching {
                val session = refreshSessionIfNeeded(activity, stored)
                NextendoSocialApi.fetchFriends(session.accessToken)
            }

            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post

                result.fold(
                    onSuccess = { friends ->
                        val rows = if (friends.isEmpty()) {
                            arrayOf("Nenhum amigo retornado pela Nextendo.")
                        } else {
                            friends.map { item ->
                                val name = item.username.ifBlank {
                                    if (item.pid > 0L) "PID " + item.pid else "Jogador"
                                }
                                buildString {
                                    append(name)
                                    if (item.friendCode.isNotBlank()) {
                                        append(" · ")
                                        append(item.friendCode)
                                    }
                                    append("\n")
                                    append(item.presenceLabel())
                                }
                            }.toTypedArray()
                        }

                        AlertDialog.Builder(activity)
                            .setTitle("Amigos Nextendo (" + friends.size + ")")
                            .setItems(rows, null)
                            .setPositiveButton("Atualizar") { _, _ -> openFriends(activity) }
                            .setNegativeButton("Fechar", null)
                            .show()
                    },
                    onFailure = { error ->
                        AlertDialog.Builder(activity)
                            .setTitle("Não foi possível carregar os amigos")
                            .setMessage(error.message ?: "Falha ao consultar a Nextendo.")
                            .setPositiveButton("Tentar novamente") { _, _ -> openFriends(activity) }
                            .setNegativeButton("Fechar", null)
                            .show()
                    }
                )
            }
        }
    }

    /**
     * Refreshes the rotating OAuth session when it is close to expiry.
     * The new refresh token is persisted immediately.
     */
    private fun refreshSessionIfNeeded(
        context: Context,
        stored: NextendoSessionStore.Session
    ): NextendoSessionStore.Session {
        if (stored.accessTokenExpiresAtEpochSeconds >
            (System.currentTimeMillis() / 1000L) + 60L
        ) {
            return stored
        }

        return runCatching {
            val refreshed = NextendoAuthClient.refreshAccessToken(stored.refreshToken)
            NextendoSessionStore.saveOAuthTokens(context, refreshed)
            NextendoSessionStore.load(context)
        }.getOrNull() ?: stored
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

                NextendoPresencePublisher.start(context)
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
