package com.nex.nx.nextendo

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
                NextendoNativeBridge.clearPrudpDiagnostics()

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
            val prudpDiagnostics = NextendoNativeBridge.prudpDiagnostics().takeLast(8)
            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post

                val username = profile?.username?.ifBlank { displaySession.username }
                    ?: displaySession.username.ifBlank { "Conta conectada" }
                val friendCode = profile?.friendCode?.ifBlank { displaySession.friendCode }
                    ?: displaySession.friendCode
                val pid = profile?.pid?.takeIf { it > 0L } ?: displaySession.pid
                val gameTokenReady = hasValidGameToken(displaySession)

                NextendoDashboardDialog.show(
                    activity = activity,
                    snapshot = NextendoDashboardDialog.Snapshot(
                        username = username,
                        friendCode = friendCode,
                        pid = pid,
                        gameTokenReady = gameTokenReady,
                        gameTokenDiagnostic = displaySession.gameTokenDiagnostic,
                        presenceDiagnostic = NextendoPresencePublisher.diagnostic(),
                        baasDiagnostic = NextendoBaasRuntime.diagnostic,
                        routeDiagnostic = NextendoNetworkRouting.diagnostic,
                        installedRouteCount = NextendoNetworkRouting.installedRouteCount,
                        prudpDiagnostics = prudpDiagnostics,
                        onlinePlayers = networkStats?.onlinePlayers,
                        registeredAccounts = networkStats?.totalRegistered,
                        gamesOnline = networkStats?.gamesOnline,
                        profileRefreshFailed = profile == null && pid > 0L
                    ),
                    onFriends = { openFriends(activity) },
                    onNetworkSettings = { openNetworkSettings(activity) },
                    onCopyFriendCode = {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("NEX Friend Code", friendCode)
                        )
                        Toast.makeText(
                            activity,
                            "Friend Code copiado.",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onDisconnect = {
                        NextendoPresencePublisher.stop(activity)
                        NextendoNativeBridge.clearFriendCache()
                        NextendoNativeBridge.clearIdentity()
                        NextendoNativeBridge.clearPrudpDiagnostics()
                        NextendoBaasRuntime.clear()
                        runCatching { NextendoSessionStore.clear(activity) }
                        Toast.makeText(
                            activity,
                            "Conta Nextendo desconectada.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
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

    /**
     * Configures the two distinct public IPv4 addresses used by a Nextendo
     * deployment. These are intentionally not guessed or defaulted.
     */
    fun openNetworkSettings(activity: Activity) {
        val saved = NextendoNetworkConfigStore.load(activity)
        val serverInput = EditText(activity).apply {
            hint = "IP principal do servidor (SERVER_IP)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setText(saved?.serverIpv4.orEmpty())
        }
        val natInput = EditText(activity).apply {
            hint = "IP do segundo ponto NAT (NAT_IP)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setText(saved?.natIpv4.orEmpty())
        }
        val jwksUrlInput = EditText(activity).apply {
            hint = "HTTPS BAAS JWKS URL fornecida pela Nextendo"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        val exportPublicKey = Button(activity)
        exportPublicKey.text = "Copiar solicitação de registro BAAS"
        exportPublicKey.setOnClickListener {
            runCatching { NextendoBaasRuntime.exportAndroidKeystoreRegistrationRequest() }
                .onSuccess { registrationRequest ->
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "NEX BAAS public-key registration request",
                            registrationRequest
                        )
                    )
                    Toast.makeText(
                        activity,
                        "Solicitação pública copiada. Envie à equipe Nextendo. Isso não registra a chave automaticamente; a chave privada não é exportada.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure { error ->
                    Toast.makeText(
                        activity,
                        error.message ?: "Não foi possível gerar a solicitação BAAS.",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
        val verifyJwksPublication = Button(activity)
        verifyJwksPublication.text = "Verificar publicação da JWK"
        verifyJwksPublication.setOnClickListener {
            val jwksUrl = jwksUrlInput.text.toString().trim()
            if (jwksUrl.isBlank()) {
                Toast.makeText(
                    activity,
                    "Informe primeiro a URL HTTPS da JWKS fornecida pela Nextendo.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                verifyJwksPublication.isEnabled = false
                executor.execute {
                    val result = runCatching {
                        NextendoBaasRuntime.verifyAndroidKeystoreJwksPublication(jwksUrl)
                    }
                    mainHandler.post {
                        verifyJwksPublication.isEnabled = true
                        result.fold(
                            onSuccess = {
                                Toast.makeText(
                                    activity,
                                    "A JWKS publica a chave exata deste dispositivo. Ainda falta a confirmação das claims BAAS pela Nextendo.",
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            onFailure = { error ->
                                Toast.makeText(
                                    activity,
                                    error.message ?: "Não foi possível confirmar a publicação da JWK.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                }
            }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset / 2, inset, 0)
            addView(
                serverInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                natInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                exportPublicKey,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                jwksUrlInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                verifyJwksPublication,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(activity)
            .setTitle("Servidores e BAAS Nextendo")
            .setMessage(
                "Informe os dois IPv4 fornecidos pela Nextendo. Eles precisam ser diferentes. " +
                    "A verificação BAAS usa somente a URL HTTPS de JWKS confirmada pela equipe Nextendo. " +
                    "Copiar a solicitação não registra a chave."
            )
            .setView(content)
            .setPositiveButton("Salvar e aplicar") { _, _ ->
                runCatching {
                    val config = NextendoNetworkConfigStore.Config(
                        serverIpv4 = serverInput.text.toString().trim(),
                        natIpv4 = natInput.text.toString().trim()
                    )
                    NextendoNetworkConfigStore.save(activity, config)
                    NextendoNetworkRouting.apply(config)
                }.onSuccess {
                    Toast.makeText(
                        activity,
                        "Rotas DNS Nextendo aplicadas. O NAT/P2P ainda precisa da configuração específica do jogo.",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        activity,
                        error.message ?: "Não foi possível aplicar os servidores.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNeutralButton("Remover configuração") { _, _ ->
                NextendoNetworkRouting.clear(activity)
                Toast.makeText(
                    activity,
                    "Rotas Nextendo removidas.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
        // DNS routing is independent from the OAuth account session; restore it
        // before a game starts so guest sockets resolve the original NEX hosts.
        runCatching { NextendoNetworkRouting.restore(context) }
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
