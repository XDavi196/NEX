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
        val gameTokenReady: Boolean,
        val gameTokenDiagnostic: String = "not checked"
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

                NextendoSessionStore.saveFreshOAuthTokens(context, tokens)
                // A new OAuth grant may belong to a different player. Remove the
                // previous guest-visible BAAS identity before any network call.
                NextendoBaasRuntime.clear()
                NextendoSessionStore.clearPendingAuthorization(context)
                NextendoNativeBridge.clearFriendCache()
                NextendoNativeBridge.clearIdentity()

                // Populate the account identity and accepted-friend cache from the
                // OAuth-scoped userinfo contract. No presence is invented when absent.
                runCatching {
                    NextendoSocialApi.fetchUserInfo(tokens.accessToken)
                }.onSuccess { userInfo ->
                    NextendoSessionStore.saveAccountIdentity(
                        context,
                        pid = userInfo.pid,
                        username = userInfo.username,
                        friendCode = userInfo.friendCode
                    )
                }
                val gameTokenAttempt = if ("game.matchmaking" !in tokens.grantedScopes) {
                    Result.failure(IllegalStateException("scope_missing"))
                } else {
                    runCatching {
                        NextendoAuthClient.fetchNexGameToken(tokens.accessToken)
                    }
                }
                val gameToken = gameTokenAttempt.getOrNull()
                val gameTokenDiagnostic = if ("game.matchmaking" !in tokens.grantedScopes) {
                    NextendoRequestDiagnostic.missingScope("game.matchmaking")
                } else {
                    gameTokenAttempt.fold(
                        onSuccess = { "HTTP 200 (nx2 accepted)" },
                        onFailure = NextendoRequestDiagnostic::fromFailure
                    )
                }

                if (gameToken != null) {
                    NextendoSessionStore.saveNexGameToken(context, gameToken)
                }

                // App-level online presence is independent from game login.
                NextendoPresencePublisher.start(context)

                val savedSession = NextendoSessionStore.load(context)
                savedSession?.let(::syncNativeIdentity)
                NextendoBaasRuntime.refresh(savedSession)
                if (savedSession?.let(::hasValidGameToken) == true) {
                    runCatching {
                        NextendoAuthClient.fetchGameFriends(savedSession.nexGameToken)
                    }.onSuccess(NextendoNativeBridge::replaceFriendCache)
                } else {
                    NextendoNativeBridge.clearFriendCache()
                }
                LoginOutcome(
                    pid = gameToken?.pid ?: savedSession?.pid ?: 0L,
                    username = gameToken?.username ?: savedSession?.username.orEmpty(),
                    friendCode = gameToken?.friendCode ?: savedSession?.friendCode.orEmpty(),
                    gameTokenReady = savedSession?.let(::hasValidGameToken) == true,
                    gameTokenDiagnostic = gameTokenDiagnostic
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
            val activeSession = try {
                refreshSessionIfNeeded(activity)
            } catch (error: Exception) {
                mainHandler.post {
                    showSessionRecoveryDialog(activity, error.message.orEmpty())
                }
                return@execute
            }
            val profile = if (activeSession.pid > 0L) {
                runCatching {
                    NextendoAccountApi.fetchOwnProfile(activeSession.accessToken, activeSession.pid)
                }.getOrNull()
            } else {
                null
            }
            // Fetch aggregate stats off the main thread; a stats outage does not
            // block login and must never freeze the account dialog.
            val networkStats = runCatching {
                NextendoAuthClient.fetchNetworkStats()
            }.getOrNull()

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
                val gameTokenReady = hasValidGameToken(displaySession)
                val details = buildString {
                    append("Jogador: ")
                    append(username)
                    append("\nFriend Code: ")
                    append(friendCode)
                    append("\nPID: ")
                    append(if (pid > 0L) pid.toString() else "Aguardando token de jogo")
                    append("\n\nSTATUS ONLINE DO NEX")
                    append("\nConta Nextendo: conectada")
                    append("\nToken de jogo nx2.: ")
                    append(if (gameTokenReady) "disponível" else "não disponível ou expirado")
                    append("\nScopes concedidos: ")
                    append(displaySession.grantedScopes.sorted().joinToString(", ").ifBlank { "não informados" })
                    append("\nPermissão game.matchmaking: ")
                    append(if ("game.matchmaking" in displaySession.grantedScopes) "concedida" else "ausente na resposta OAuth")
                    append("\nPresença Nextendo: ")
                    append(NextendoPresencePublisher.diagnostic())
                    append("\nToken BAAS no ACC: ")
                    append(NextendoBaasRuntime.diagnostic)
                    append("\n\nESTATÍSTICAS PÚBLICAS DA REDE")
                    if (networkStats != null) {
                        append("\nJogadores online: ")
                        append(networkStats.onlinePlayers)
                        append("\nContas registradas: ")
                        append(networkStats.totalRegistered)
                        append("\nJogos online: ")
                        append(networkStats.gamesOnline)
                    } else {
                        append("\nEstatísticas indisponíveis no momento")
                    }
                    append("\n\nSuper Mario Maker 2: NÃO CONECTADO")
                    append("\nAinda falta autenticação BAAS/nnex assinada, endpoint e sessão PRUDP confirmados para o NEX.")
                    append("\nQLaunch / nn::friends: integração parcial")
                    append("\nA API pública não confirma NSA/UUID para todos os amigos; sem esses IDs, o QLaunch não pode montar uma lista de amigos válida.")
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
                        NextendoNativeBridge.clearFriendCache()
                        NextendoNativeBridge.clearIdentity()
                        NextendoBaasRuntime.clear()
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
            val session = try {
                refreshSessionIfNeeded(activity)
            } catch (error: Exception) {
                mainHandler.post {
                    showSessionRecoveryDialog(activity, error.message.orEmpty())
                }
                return@execute
            }
            val result = runCatching {
                val oauthFriendList = NextendoSocialApi.fetchFriendList(session.accessToken)
                if (hasValidGameToken(session)) {
                    runCatching {
                        NextendoAuthClient.fetchGameFriends(session.nexGameToken)
                    }.onSuccess(NextendoNativeBridge::replaceFriendCache)
                } else {
                    NextendoNativeBridge.clearFriendCache()
                }
                oauthFriendList
            }

            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post

                result.fold(
                    onSuccess = { friendList ->
                        NextendoFriendsDialog.show(activity, friendList) {
                            openFriends(activity)
                        }
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

    private fun showSessionRecoveryDialog(activity: Activity, detail: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("Não foi possível atualizar a sessão")
            .setMessage(detail.ifBlank { "Entre novamente para continuar usando sua conta." })
            .setPositiveButton("Conectar novamente") { _, _ -> startLogin(activity) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    /**
     * Refreshes the rotating OAuth session when a refresh token is available.
     * The new refresh token is persisted immediately.
     */
    private fun hasValidGameToken(session: NextendoSessionStore.Session): Boolean {
        val now = System.currentTimeMillis() / 1000L
        return session.nexGameToken.startsWith("nx2.") &&
            session.nexGameTokenExpiresAtEpochSeconds > now &&
            session.pid > 0L
    }

    /**
     * Rehydrates process-local ACC identity before a game starts.
     *
     * Android Keystore-backed session data survives app restarts, but the native
     * Eden cache does not. Restore it synchronously from the encrypted local
     * session, then refresh OAuth/game credentials and the friend snapshot off
     * the UI thread.
     */
    fun restoreCachedIdentity(context: Context) {
        val cached = runCatching { NextendoSessionStore.load(context) }.getOrNull()
        if (cached == null) {
            NextendoNativeBridge.clearIdentity()
            NextendoNativeBridge.clearFriendCache()
            NextendoBaasRuntime.clear()
            return
        }

        syncNativeIdentity(cached)
        NextendoBaasRuntime.refresh(cached)

        executor.execute {
            val active = runCatching {
                refreshSessionIfNeeded(context)
            }.getOrElse {
                cached
            }

            syncNativeIdentity(active)
            NextendoBaasRuntime.refresh(active)
            if (hasValidGameToken(active)) {
                runCatching {
                    NextendoAuthClient.fetchGameFriends(active.nexGameToken)
                }.onSuccess(NextendoNativeBridge::replaceFriendCache)
                    .onFailure { NextendoNativeBridge.clearFriendCache() }
            } else {
                NextendoNativeBridge.clearFriendCache()
            }
        }
    }

    private fun syncNativeIdentity(session: NextendoSessionStore.Session) {
        // The HOME Menu needs the linked account identity even when the game-token
        // endpoint is not yet enabled for this public client. Game authentication
        // still checks the separate nx2. token in its own path.
        if (session.pid > 0L) {
            NextendoNativeBridge.updateIdentity(session.pid)
        } else {
            NextendoNativeBridge.clearIdentity()
        }
    }

    private fun refreshSessionIfNeeded(
        context: Context
    ): NextendoSessionStore.Session {
        // Always re-read the current session in the synchronized refresher:
        // another worker may already have rotated the refresh token.
        var session = NextendoSessionRefresher.getValidSession(context, 60L)

        // Refresh the separate nx2. credential when this client was granted
        // game.matchmaking. Failure leaves account login usable but does not imply
        // that game authentication or multiplayer is ready.
        if (!hasValidGameToken(session) && "game.matchmaking" in session.grantedScopes) {
            runCatching {
                NextendoAuthClient.fetchNexGameToken(session.accessToken)
            }.onSuccess { gameToken ->
                NextendoSessionStore.saveNexGameToken(context, gameToken)
                session = NextendoSessionStore.load(context) ?: session
            }
        }
        syncNativeIdentity(session)
        NextendoBaasRuntime.refresh(session)
        return session
    }

    fun refreshSession(
        context: Context,
        onComplete: (Result<LoginOutcome>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val latest = NextendoSessionRefresher.getValidSession(context, 300L)
                if (
                    latest.nexGameToken.isBlank() ||
                    latest.nexGameTokenExpiresAtEpochSeconds <=
                        (System.currentTimeMillis() / 1000L) + 300L
                ) {
                    // OAuth session remains usable even if the public game-token
                    // endpoint is not provisioned for this client yet.
                    runCatching {
                        NextendoAuthClient.fetchNexGameToken(latest.accessToken)
                    }.onSuccess { token ->
                        NextendoSessionStore.saveNexGameToken(context, token)
                    }
                }

                NextendoPresencePublisher.start(context)
                val session = NextendoSessionStore.load(context)
                session?.let(::syncNativeIdentity)
                NextendoBaasRuntime.refresh(session)
                val gameTokenReady = session?.let(::hasValidGameToken) == true
                if (session != null && gameTokenReady) {
                    runCatching {
                        NextendoAuthClient.fetchGameFriends(session.nexGameToken)
                    }.onSuccess(NextendoNativeBridge::replaceFriendCache)
                } else {
                    NextendoNativeBridge.clearFriendCache()
                }
                LoginOutcome(
                    pid = session?.pid ?: 0L,
                    username = session?.username.orEmpty(),
                    friendCode = session?.friendCode.orEmpty(),
                    gameTokenReady = gameTokenReady
                )
            }

            mainHandler.post { onComplete(result) }
        }
    }
}
