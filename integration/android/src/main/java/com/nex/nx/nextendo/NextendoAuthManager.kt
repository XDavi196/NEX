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
                        fetchNexGameTokenWithRecovery(context, tokens.accessToken)
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
                    onCheckOnlineAccess = { checkOnlineAccess(activity) },
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

    /**
     * Checks the read-only account gate using this NEX session's valid nx2. token.
     * It does not create a play session or bypass an online restriction.
     */
    fun checkOnlineAccess(activity: Activity) {
        val stored = runCatching { NextendoSessionStore.load(activity) }.getOrNull()
        if (stored == null) {
            startLogin(activity)
            return
        }

        executor.execute {
            val result = runCatching {
                val session = refreshSessionIfNeeded(activity)
                if (!hasValidGameToken(session)) throw MissingOnlineGameTokenException()
                NextendoAuthClient.fetchOnlineAccessStatus(session.nexGameToken)
            }

            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.fold(
                    onSuccess = { status ->
                        val title = if (status.allow) {
                            "Elegibilidade da conta aprovada"
                        } else {
                            "A Nextenda bloqueou o acesso online"
                        }
                        val message = if (status.allow) {
                            "A Nextendo não encontrou bloqueios de conta para este NEX/PID. Isso não confirma BAAS/LoginEx, conexão segura nem matchmaking funcional no Super Mario Maker 2."
                        } else {
                            when (status.reason) {
                                "unknown" ->
                                    "O PID do token nx2. não corresponde a uma conta reconhecida pela Nextendo. Atualize a conta NEX e solicite novamente a credencial de jogo."
                                "disabled" ->
                                    "A conta Nextendo está desativada. Resolva o estado da conta com o suporte Nextendo."
                                "unverified" ->
                                    "O e-mail da conta Nextendo ainda não foi verificado. Confirme o e-mail e tente novamente."
                                "discord_unlinked" ->
                                    "A conta não está vinculada ao Discord exigido pela política atual. Faça o vínculo pelo fluxo oficial e permaneça no servidor Nextendo."
                                "elsewhere" ->
                                    "A mesma conta aparece jogando em outro dispositivo/emulador. Feche a sessão ativa nesse outro aparelho e tente novamente."
                                else ->
                                    "A Nextendo recusou o acesso por um motivo que esta versão do NEX não reconhece."
                            }
                        }
                        AlertDialog.Builder(activity)
                            .setTitle(title)
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show()
                    },
                    onFailure = { error ->
                        val message = when (error) {
                            is MissingOnlineGameTokenException ->
                                "O NEX ainda não possui um nx2. válido. Verifique se a autorização concedeu game.matchmaking e atualize a sessão da conta."
                            is NextendoAuthClient.NextendoException ->
                                if (error.httpStatus == 401) {
                                    "O servidor recusou o token nx2. O HTTP 401 desta rota não diferencia token inválido/expirado de conta indisponível ou desativada. Reconecte a conta NEX; se persistir, verifique o estado da conta com a Nextendo. Isso não é um diagnóstico do matchmaking."
                                } else {
                                    NextendoRequestDiagnostic.fromFailure(error)
                                }
                            else -> NextendoRequestDiagnostic.fromFailure(error)
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("Não foi possível verificar o acesso online")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                )
            }
        }
    }

    private class MissingOnlineGameTokenException : Exception()

    /** Displays accepted friends and incoming requests returned by the game-facing API. */
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
                val gameFriendList = if (hasValidGameToken(session)) {
                    runCatching {
                        val snapshot = NextendoAuthClient.fetchGameFriendSnapshot(session.nexGameToken)
                        NextendoNativeBridge.replaceFriendCache(snapshot.friends)
                        NextendoSocialApi.toFriendList(snapshot)
                    }.onFailure {
                        // A failed game-facing route must not leave stale friends
                        // from a previous account in the native HLE cache.
                        NextendoNativeBridge.clearFriendCache()
                    }.getOrNull()
                } else {
                    NextendoNativeBridge.clearFriendCache()
                    null
                }

                gameFriendList ?: NextendoSocialApi.fetchFriendList(session.accessToken)
            }

            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post

                result.fold(
                    onSuccess = { friendList ->
                        NextendoFriendsDialog.show(
                            activity = activity,
                            result = friendList,
                            onRefresh = { openFriends(activity) },
                            onAddFriend = { showAddFriendDialog(activity) },
                            onAcceptFriend = { pid ->
                                runGameFriendAction(activity, "Pedido de amizade aceito.") { token ->
                                    NextendoSocialApi.acceptFriendRequest(token, pid)
                                }
                            },
                            onDeclineFriend = { pid ->
                                runGameFriendAction(activity, "Pedido de amizade recusado.") { token ->
                                    NextendoSocialApi.declineFriendRequest(token, pid)
                                }
                            },
                            onRemoveFriend = { pid ->
                                runGameFriendAction(activity, "Amigo removido.") { token ->
                                    NextendoSocialApi.removeFriend(token, pid)
                                }
                            }
                        )
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

    private fun showAddFriendDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val codeInput = EditText(activity).apply {
            hint = "SW-1234-5678-9012"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(activity)
            .setTitle("Adicionar amigo Nextendo")
            .setMessage("Digite o código de amigo. O pedido será enviado pela sua própria conta NEX.")
            .setView(codeInput)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Enviar pedido") { _, _ ->
                val code = codeInput.text.toString().trim()
                runGameFriendAction(activity, "Pedido de amizade enviado.") { token ->
                    NextendoSocialApi.addFriendByCode(token, code)
                }
            }
            .show()
    }

    private fun runGameFriendAction(
        activity: Activity,
        successMessage: String,
        action: (String) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        executor.execute {
            val result = runCatching {
                val session = refreshSessionIfNeeded(activity)
                if (!hasValidGameToken(session)) throw MissingOnlineGameTokenException()
                action(session.nexGameToken)
            }
            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.fold(
                    onSuccess = {
                        Toast.makeText(activity, successMessage, Toast.LENGTH_SHORT).show()
                        openFriends(activity)
                    },
                    onFailure = { error ->
                        val message = if (error is MissingOnlineGameTokenException) {
                            "O NEX precisa de um token de jogo nx2. válido para gerenciar os amigos. Reconecte a conta ou confira a permissão game.matchmaking."
                        } else {
                            error.message ?: "A Nextendo não conseguiu concluir esta ação."
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("Ação de amizade não concluída")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
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
        val primaryNncsPortInput = EditText(activity).apply {
            hint = "Porta NNCS do ponto principal (oficial)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(saved?.primaryNncsPort?.toString().orEmpty())
        }
        val secondaryNncsPortInput = EditText(activity).apply {
            hint = "Porta NNCS do segundo ponto (oficial)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(saved?.secondaryNncsPort?.toString().orEmpty())
        }

        fun readConfig(): NextendoNetworkConfigStore.Config {
            val primaryText = primaryNncsPortInput.text.toString().trim()
            val secondaryText = secondaryNncsPortInput.text.toString().trim()
            val primaryPort = primaryText.takeIf { it.isNotEmpty() }?.toIntOrNull()
            val secondaryPort = secondaryText.takeIf { it.isNotEmpty() }?.toIntOrNull()
            require(primaryText.isEmpty() || primaryPort != null) {
                "A porta NNCS principal precisa ser um número entre 1 e 65535."
            }
            require(secondaryText.isEmpty() || secondaryPort != null) {
                "A segunda porta NNCS precisa ser um número entre 1 e 65535."
            }
            return NextendoNetworkConfigStore.Config(
                serverIpv4 = serverInput.text.toString().trim(),
                natIpv4 = natInput.text.toString().trim(),
                primaryNncsPort = primaryPort,
                secondaryNncsPort = secondaryPort
            )
        }

        val probeNatButton = Button(activity).apply {
            text = "Testar NAT e registrar IPv4 externo"
            setOnClickListener {
                val config = runCatching { readConfig() }.getOrElse { error ->
                    Toast.makeText(
                        activity,
                        error.message ?: "Configure os dois pontos NNCS.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                val primaryPort = config.primaryNncsPort
                val secondaryPort = config.secondaryNncsPort
                if (primaryPort == null || secondaryPort == null) {
                    Toast.makeText(
                        activity,
                        "Informe as duas portas NNCS fornecidas oficialmente pela Nextenda.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                val savedConfig = runCatching {
                    NextendoNetworkConfigStore.save(activity, config)
                }
                if (savedConfig.isFailure) {
                    Toast.makeText(
                        activity,
                        savedConfig.exceptionOrNull()?.message
                            ?: "Não foi possível salvar a configuração NNCS.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                // Never keep an old external mapping when a new probe is requested.
                NextendoNativeBridge.clearObservedExternalIpv4()
                Toast.makeText(
                    activity,
                    "Testando os dois pontos NNCS pela mesma porta UDP local...",
                    Toast.LENGTH_SHORT
                ).show()
                executor.execute {
                    val result = runCatching {
                        val probe = NextendoNatProbeClient(
                            endpoints = listOf(
                                NextendoNatProbeClient.Endpoint(
                                    "primary",
                                    config.serverIpv4,
                                    primaryPort
                                ),
                                NextendoNatProbeClient.Endpoint(
                                    "secondary",
                                    config.natIpv4,
                                    secondaryPort
                                )
                            )
                        ).probe()
                        if (probe.classification.type == NextendoNatProbeTracker.NatType.UNKNOWN) {
                            throw IllegalStateException(
                                "As duas respostas NNCS não identificaram dois pontos válidos. " +
                                    "O NAT rewrite permaneceu desativado."
                            )
                        }
                        val externalIpv4 = probe.classification.externalAddress?.hostAddress
                            ?: throw IllegalStateException(
                                "Os dois pontos NNCS não concordaram no IPv4 externo. " +
                                    "O endereço não foi aplicado ao tráfego P2P."
                            )
                        NextendoNativeBridge.updateObservedExternalIpv4(externalIpv4)
                        probe
                    }
                    mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        result.fold(
                            onSuccess = { probe ->
                                val message = "NNCS validado (" +
                                    probe.classification.type.name + "). " +
                                    "IPv4 externo registrado para a correção das Station URLs."
                                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                            },
                            onFailure = { error ->
                                Toast.makeText(
                                    activity,
                                    error.message
                                        ?: "Falha no teste NNCS. O NAT rewrite ficou desativado.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                }
            }
        }

        val exportBackup = Button(activity).apply {
            text = "Exportar backup criptografado da conta"
            setOnClickListener { showExportSessionBackupDialog(activity) }
        }
        val importBackup = Button(activity).apply {
            text = "Importar backup criptografado"
            setOnClickListener { showImportSessionBackupDialog(activity) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset / 2, inset, 0)
            listOf(
                serverInput,
                natInput,
                primaryNncsPortInput,
                secondaryNncsPortInput,
                probeNatButton,
                exportBackup,
                importBackup
            ).forEach { view ->
                addView(
                    view,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Servidores e backup da conta")
            .setMessage(
                "Use somente os dois IPv4 e as portas NNCS fornecidos oficialmente pela Nextenda. " +
                    "O teste usa a mesma porta UDP local para consultar os dois pontos; somente " +
                    "uma resposta externa coerente habilita a correção de Station URLs. " +
                    "Não há portas padrão presumidas. O backup da conta contém credenciais de sessão, " +
                    "é criptografado e não é compatível com arquivos de outros clientes. " +
                    "Este teste não substitui a autorização BAAS/LoginEx nem confirma matchmaking."
            )
            .setView(content)
            .setPositiveButton("Salvar e aplicar") { _, _ ->
                runCatching {
                    val config = readConfig()
                    NextendoNetworkConfigStore.save(activity, config)
                    NextendoNetworkRouting.apply(config)
                    // Any endpoint change requires a fresh two-vantage probe.
                    NextendoNativeBridge.clearObservedExternalIpv4()
                }.onSuccess {
                    Toast.makeText(
                        activity,
                        "Rotas DNS Nextendo aplicadas. Configure as duas portas oficiais para testar NAT/P2P.",
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
                NextendoNetworkConfigStore.clear(activity)
                NextendoNativeBridge.clearObservedExternalIpv4()
                Toast.makeText(
                    activity,
                    "Rotas e endereço externo NAT removidos.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showExportSessionBackupDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val passwordInput = EditText(activity).apply {
            hint = "Senha do backup (mínimo 12 caracteres)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val confirmInput = EditText(activity).apply {
            hint = "Confirmar senha"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, 0, inset, 0)
            addView(passwordInput)
            addView(confirmInput)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Exportar conta NEX")
            .setMessage(
                "O arquivo conterá tokens de sessão criptografados. Defina uma senha forte, " +
                    "guarde-a separadamente e nunca envie o arquivo ou a senha para outras pessoas."
            )
            .setView(content)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Exportar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = CharArray(passwordInput.text.length) { index ->
                    passwordInput.text[index]
                }
                val confirmation = CharArray(confirmInput.text.length) { index ->
                    confirmInput.text[index]
                }
                if (password.size < NextendoSessionBackupCodec.MIN_PASSPHRASE_LENGTH) {
                    passwordInput.error = "Use pelo menos 12 caracteres."
                    password.fill('\u0000')
                    confirmation.fill('\u0000')
                    return@setOnClickListener
                }
                if (!password.contentEquals(confirmation)) {
                    confirmInput.error = "As senhas não coincidem."
                    password.fill('\u0000')
                    confirmation.fill('\u0000')
                    return@setOnClickListener
                }
                confirmation.fill('\u0000')
                dialog.dismiss()
                passwordInput.text.clear()
                confirmInput.text.clear()
                executor.execute {
                    val result = runCatching {
                        NextendoSessionStore.exportEncryptedBackup(activity, password)
                    }
                    password.fill('\u0000')
                    mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        result.fold(
                            onSuccess = { encryptedBackup ->
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "NEX encrypted Nextendo session backup")
                                    putExtra(Intent.EXTRA_TEXT, encryptedBackup)
                                }
                                runCatching {
                                    activity.startActivity(
                                        Intent.createChooser(shareIntent, "Salvar backup criptografado")
                                    )
                                }.onFailure {
                                    Toast.makeText(
                                        activity,
                                        "Não foi possível abrir o compartilhamento do Android.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            onFailure = { error ->
                                Toast.makeText(
                                    activity,
                                    error.message ?: "Não foi possível exportar a conta.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showImportSessionBackupDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val backupInput = EditText(activity).apply {
            hint = "Cole aqui o conteúdo do backup NEX criptografado"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 5
            maxLines = 9
            setHorizontallyScrolling(false)
        }
        val passwordInput = EditText(activity).apply {
            hint = "Senha do backup"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, 0, inset, 0)
            addView(
                backupInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (180 * resources.displayMetrics.density).toInt()
                )
            )
            addView(passwordInput)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Importar conta NEX")
            .setMessage(
                "A restauração substituirá a sessão Nextendo atualmente salva neste NEX. " +
                    "Só são aceitos backups criptografados exportados pelo próprio NEX; " +
                    "o arquivo nextendo_account.txt de DEN/Citron não é compatível."
            )
            .setView(content)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Importar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val backup = backupInput.text.toString().trim()
                val password = CharArray(passwordInput.text.length) { index ->
                    passwordInput.text[index]
                }
                if (backup.isBlank()) {
                    backupInput.error = "Cole o conteúdo do backup."
                    password.fill('\u0000')
                    return@setOnClickListener
                }
                if (password.size < NextendoSessionBackupCodec.MIN_PASSPHRASE_LENGTH) {
                    passwordInput.error = "A senha precisa ter pelo menos 12 caracteres."
                    password.fill('\u0000')
                    return@setOnClickListener
                }
                dialog.dismiss()
                backupInput.text.clear()
                passwordInput.text.clear()
                executor.execute {
                    val result = runCatching {
                        NextendoSessionStore.importEncryptedBackup(activity, backup, password)
                        NextendoBaasRuntime.clear()
                        NextendoNativeBridge.clearFriendCache()
                        NextendoNativeBridge.clearIdentity()
                        NextendoNativeBridge.clearPrudpDiagnostics()
                    }
                    password.fill('\u0000')
                    mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        result.fold(
                            onSuccess = {
                                Toast.makeText(
                                    activity,
                                    "Backup restaurado. Abra a conta NEX para validar a sessão com a Nextendo.",
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            onFailure = { error ->
                                Toast.makeText(
                                    activity,
                                    error.message ?: "Não foi possível importar o backup.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                }
            }
        }
        dialog.show()
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
     * Mirrors the safe recovery behavior used by the DEN reference client:
     * if the game-token endpoint rejects the current OAuth access token with
     * HTTP 401, rotate the NEX refresh token once and retry with the new token.
     * No other HTTP error is retried, and no DEN credential is involved.
     */
    private fun fetchNexGameTokenWithRecovery(
        context: Context,
        accessToken: String
    ): NextendoAuthClient.NexGameToken {
        return try {
            NextendoAuthClient.fetchNexGameToken(accessToken)
        } catch (error: NextendoAuthClient.NextendoException) {
            if (error.httpStatus != 401) throw error

            val refreshed = NextendoSessionRefresher.refreshAfterUnauthorized(
                context = context,
                rejectedAccessToken = accessToken
            )
            if (refreshed.accessToken == accessToken) {
                throw error
            }
            NextendoAuthClient.fetchNexGameToken(refreshed.accessToken)
        }
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
                fetchNexGameTokenWithRecovery(context, session.accessToken)
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
                        fetchNexGameTokenWithRecovery(context, latest.accessToken)
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
