package com.nex.nx.nextendo

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Publishes the signed-in player's app/game presence.
 *
 * Nextendo expires presence unless the app sends a heartbeat at least every
 * 90 seconds. NEX sends one every 60 seconds. The current Android wiring
 * reports account-online status; a native emulation callback must call
 * setGameActivity() to publish a real title ID and game detail.
 */
object NextendoPresencePublisher {
    private const val HEARTBEAT_SECONDS = 60L
    private const val TOKEN_REFRESH_MARGIN_SECONDS = 90L

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

    /** Start online presence after a valid OAuth session has been saved. */
    fun start(context: Context) {
        synchronized(lock) {
            applicationContext = context.applicationContext
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
     *
     * appFieldBase64 must be the real serialized game field when a title
     * requires one. Leave it empty until the HLE implementation can supply it;
     * do not fabricate Nintendo's binary AppKeyValueStorage data.
     */
    fun setGameActivity(
        context: Context,
        titleId: String,
        detail: String,
        appFieldBase64: String = ""
    ) {
        require(titleId.isNotBlank()) { "A real game title ID is required for in-game presence." }
        gameTitleId = titleId
        gameDetail = detail
        this.appFieldBase64 = appFieldBase64
        start(context)
        executor.execute { publishCurrentPresence() }
    }

    /** Return to ordinary online status when no game is running. */
    fun clearGameActivity(context: Context) {
        gameTitleId = ""
        gameDetail = ""
        appFieldBase64 = ""
        start(context)
        executor.execute { publishCurrentPresence() }
    }

    /**
     * Publish offline once, then stop the heartbeat. Call this when the user
     * disconnects the Nextendo account.
     */
    fun stop(context: Context) {
        val appContext = context.applicationContext
        // Capture the token before the caller clears the local session. Otherwise
        // the queued offline request could run after logout and lose its credential.
        val accessToken = runCatching {
            NextendoSessionStore.load(appContext)?.accessToken
        }.getOrNull()

        synchronized(lock) {
            heartbeat?.cancel(false)
            heartbeat = null
            applicationContext = appContext
        }

        gameTitleId = ""
        gameDetail = ""
        appFieldBase64 = ""
        if (accessToken.isNullOrBlank()) return

        executor.execute {
            runCatching {
                NextendoAuthClient.postPresence(
                    accessToken = accessToken,
                    status = 0,
                    appField = "",
                    appId = "",
                    appDetail = ""
                )
            }
        }
    }

    private fun publishCurrentPresence() {
        val context = synchronized(lock) { applicationContext } ?: return
        var session = runCatching { NextendoSessionStore.load(context) }.getOrNull() ?: return

        val now = System.currentTimeMillis() / 1000L
        if (session.accessTokenExpiresAtEpochSeconds <= now + TOKEN_REFRESH_MARGIN_SECONDS) {
            session = runCatching {
                val refreshed = NextendoAuthClient.refreshAccessToken(session.refreshToken)
                NextendoSessionStore.saveOAuthTokens(context, refreshed)
                NextendoSessionStore.load(context)
            }.getOrNull() ?: return
        }

        val titleId = gameTitleId
        val inGame = titleId.isNotBlank()
        runCatching {
            NextendoAuthClient.postPresence(
                accessToken = session.accessToken,
                status = if (inGame) 2 else 1,
                appField = if (inGame) appFieldBase64 else "",
                appId = if (inGame) titleId else "",
                appDetail = if (inGame) gameDetail else ""
            )
        }
        // Network failures are retried on the next heartbeat; never log tokens.
    }
}
