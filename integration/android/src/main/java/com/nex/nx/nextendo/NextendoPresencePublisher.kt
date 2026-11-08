package com.nex.nx.nextendo

import android.content.Context
import android.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Publishes NEX app/game presence through the Nextendo account API.
 *
 * The deployed account-server's accountFromBearer path accepts the web-session
 * credential or the server-issued nx2. token. OAuth access tokens are used only
 * to obtain/refresh the nx2. token and must not be sent to /api/presence.
 */
object NextendoPresencePublisher {
    private const val HEARTBEAT_SECONDS = 60L
    private const val TOKEN_REFRESH_MARGIN_SECONDS = 90L
    private const val HOME_MENU_TITLE_ID = "0100000000001000"
    private val lock = Any()
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "NEX-Nextendo-Presence").apply { isDaemon = true }
        }

    @Volatile
    private var gameTitleId: String = ""

    @Volatile
    private var gameDetail: String = ""

    @Volatile
    private var appFieldBase64: String = ""

    private var applicationContext: Context? = null
    private var heartbeat: ScheduledFuture<*>? = null

    @Volatile
    private var enabled = false

    @Volatile
    private var lastPresenceDiagnostic: String = "ainda não testado"

    /** Safe, token-free result of the last presence attempt. */
    fun diagnostic(): String = lastPresenceDiagnostic

    private fun describeFailure(error: Throwable): String = when (error) {
        is NextendoAuthClient.NextendoException ->
            NextendoRequestDiagnostic.fromHttpStatus(error.httpStatus)
        is java.io.IOException -> "network_error"
        is IllegalStateException -> "session_unavailable"
        else -> "request_error"
    }

    fun start(context: Context) {
        synchronized(lock) {
            applicationContext = context.applicationContext
            enabled = true
            if (heartbeat?.isCancelled == false && heartbeat?.isDone == false) return

            heartbeat = executor.scheduleAtFixedRate(
                { publishCurrentPresence() },
                0L,
                HEARTBEAT_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    /**
     * Set in-game presence from the emulator lifecycle.
     * appFieldBase64 must be the real serialized 0xC0 game field if a title
     * requires one; it remains empty until the HLE implementation supplies it.
     */
    fun setGameActivity(
        context: Context,
        titleId: String,
        detail: String,
        appFieldBase64: String = ""
    ) {
        require(titleId.isNotBlank()) { "A real game title ID is required for in-game presence." }
        NextendoNativeBridge.clearLocalPresence()
        gameTitleId = titleId
        gameDetail = detail
        this.appFieldBase64 = appFieldBase64
        start(context)
        executor.execute { publishCurrentPresence() }
    }

    fun setHomeMenuActivity(context: Context) {
        setGameActivity(context, HOME_MENU_TITLE_ID, "HOME", "")
    }

    fun clearGameActivity(context: Context) {
        gameTitleId = ""
        gameDetail = ""
        appFieldBase64 = ""
        NextendoNativeBridge.clearLocalPresence()
        start(context)
        executor.execute { publishCurrentPresence() }
    }

    /** Publish offline once, then stop the heartbeat. */
    fun stop(context: Context) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis() / 1000L
        val gameToken = runCatching {
            NextendoSessionStore.load(appContext)?.let { session ->
                session.nexGameToken.takeIf {
                    it.startsWith("nx2.") &&
                        session.nexGameTokenExpiresAtEpochSeconds > now
                }
            }
        }.getOrNull()

        synchronized(lock) {
            enabled = false
            heartbeat?.cancel(false)
            heartbeat = null
            applicationContext = appContext
        }

        gameTitleId = ""
        gameDetail = ""
        appFieldBase64 = ""
        NextendoNativeBridge.clearLocalPresence()
        if (gameToken.isNullOrBlank()) {
            lastPresenceDiagnostic = "offline_status_not_sent_game_token_unavailable"
            return
        }

        executor.execute {
            val result = runCatching {
                NextendoAuthClient.postPresence(
                    gameToken = gameToken,
                    status = 0,
                    appField = "",
                    appId = "",
                    appDetail = ""
                )
            }
            lastPresenceDiagnostic = result.fold(
                onSuccess = { "HTTP 2xx (offline accepted)" },
                onFailure = ::describeFailure
            )
        }
    }

    private fun fetchGameTokenWithRecovery(
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
            if (refreshed.accessToken == accessToken) throw error
            NextendoAuthClient.fetchNexGameToken(refreshed.accessToken)
        }
    }

    private fun publishCurrentPresence() {
        if (!enabled) return
        val context = synchronized(lock) { applicationContext } ?: run {
            lastPresenceDiagnostic = "not_started"
            return
        }
        val sessionResult = runCatching {
            NextendoSessionRefresher.getValidSession(context, TOKEN_REFRESH_MARGIN_SECONDS)
        }
        var session = sessionResult.getOrElse { error ->
            lastPresenceDiagnostic = describeFailure(error)
            return
        }

        val now = System.currentTimeMillis() / 1000L
        var gameToken = session.nexGameToken.takeIf {
            it.startsWith("nx2.") && session.nexGameTokenExpiresAtEpochSeconds > now
        }
        if (gameToken == null) {
            if ("game.matchmaking" !in session.grantedScopes) {
                lastPresenceDiagnostic = NextendoRequestDiagnostic.missingScope("game.matchmaking")
                return
            }
            val tokenResult = runCatching {
                fetchGameTokenWithRecovery(context, session.accessToken)
            }
            val fetched = tokenResult.getOrElse { error ->
                lastPresenceDiagnostic = describeFailure(error)
                return
            }
            NextendoSessionStore.saveNexGameToken(context, fetched)
            session = runCatching { NextendoSessionStore.load(context) }.getOrNull() ?: session
            gameToken = session.nexGameToken.takeIf {
                it.startsWith("nx2.") &&
                    session.nexGameTokenExpiresAtEpochSeconds > System.currentTimeMillis() / 1000L
            }
        }
        val validGameToken = gameToken ?: run {
            lastPresenceDiagnostic = "game_token_unavailable_or_expired"
            return
        }

        val titleId = gameTitleId
        val inGame = titleId.isNotBlank()
        val guestPresence = if (inGame) {
            NextendoNativeBridge.guestPresenceSnapshot()
        } else {
            null
        }
        val rawGuestAppField = guestPresence?.appField
        val encodedGuestAppField = rawGuestAppField
            ?.takeIf { bytes -> bytes.any { it != 0.toByte() } }
            ?.let { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) }
        val presenceStatus = if (inGame) {
            maxOf(1, guestPresence?.status?.coerceIn(1, 2) ?: 1)
        } else {
            1
        }
        val presenceResult = runCatching {
            NextendoAuthClient.postPresence(
                gameToken = validGameToken,
                status = presenceStatus,
                appField = if (inGame) encodedGuestAppField ?: appFieldBase64 else "",
                appId = if (inGame) titleId else "",
                appDetail = if (inGame) gameDetail else ""
            )
        }
        lastPresenceDiagnostic = presenceResult.fold(
            onSuccess = { "HTTP 2xx (accepted)" },
            onFailure = ::describeFailure
        )

        // Friends and presence share the same nx2 account-service contract.
        if (presenceResult.isSuccess) {
            runCatching {
                NextendoAuthClient.fetchGameFriends(validGameToken)
            }.onSuccess(NextendoNativeBridge::replaceFriendCache)
        }
    }
}
