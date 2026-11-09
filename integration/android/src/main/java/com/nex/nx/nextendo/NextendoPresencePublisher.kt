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

    /** Publish offline once with the OAuth presence permission, then stop the heartbeat. */
    fun stop(context: Context) {
        val appContext = context.applicationContext
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

        executor.execute {
            val result = runCatching {
                val session = NextendoSessionRefresher.getValidSession(
                    appContext,
                    TOKEN_REFRESH_MARGIN_SECONDS
                )
                require("presence" in session.grantedScopes) {
                    NextendoRequestDiagnostic.missingScope("presence")
                }
                NextendoAuthClient.postPresence(
                    accessToken = session.accessToken,
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

    private fun publishCurrentPresence() {
        if (!enabled) return
        val context = synchronized(lock) { applicationContext } ?: run {
            lastPresenceDiagnostic = "not_started"
            return
        }
        val sessionResult = runCatching {
            NextendoSessionRefresher.getValidSession(context, TOKEN_REFRESH_MARGIN_SECONDS)
        }
        val session = sessionResult.getOrElse { error ->
            lastPresenceDiagnostic = describeFailure(error)
            return
        }

        // The Developer API contract uses OAuth "presence" scope for the heartbeat.
        // The separate nx2. game token is only for game authentication and the BAAS nnex claim.
        if ("presence" !in session.grantedScopes) {
            lastPresenceDiagnostic = NextendoRequestDiagnostic.missingScope("presence")
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
                accessToken = session.accessToken,
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

        // Friend graph access is separate and still requires a valid nx2. game token.
        // Presence itself must not fetch or synthesize a game token.
        val gameTokenNow = session.nexGameToken.takeIf {
            it.startsWith("nx2.") &&
                session.nexGameTokenExpiresAtEpochSeconds > System.currentTimeMillis() / 1000L
        }
        if (presenceResult.isSuccess && gameTokenNow != null) {
            runCatching {
                NextendoAuthClient.fetchGameFriends(gameTokenNow)
            }.onSuccess(NextendoNativeBridge::replaceFriendCache)
        }
    }
}
