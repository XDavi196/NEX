package com.nex.nx.nextendo

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Publishes the signed-in player's app/game presence.
 *
 * Nextendo expires presence unless the app sends a heartbeat at least every
 * 90 seconds. NEX sends one every 60 seconds, but only after a valid, unexpired
 * NEX-issued nx2. game token is available. OAuth access tokens are never sent
 * to the game-presence endpoint. The Eden overlay calls setGameActivity()
 * from EmulationActivity using Eden's current title-ID/playtime bridge.
 */
object NextendoPresencePublisher {
    private const val HEARTBEAT_SECONDS = 60L
    private const val TOKEN_REFRESH_MARGIN_SECONDS = 90L
    private const val HOME_MENU_TITLE_ID = "0100000000001000"
    private val HOME_MENU_APP_FIELD = Base64.encodeToString(
        "sys_description\u0000HOME Menu · NEX\u0000".toByteArray(StandardCharsets.UTF_8),
        Base64.NO_WRAP
    )

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

    /** Start the heartbeat scheduler; publishing remains gated on a valid nx2. token. */
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

    /** Publish the actual Switch HOME Menu identity while qLaunch is active. */
    fun setHomeMenuActivity(context: Context) {
        setGameActivity(
            context,
            HOME_MENU_TITLE_ID,
            "HOME",
            HOME_MENU_APP_FIELD
        )
    }

    /** Return to ordinary online status when no game or qLaunch is active. */
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
            NextendoSessionStore.load(appContext)?.let { session ->
                session.nexGameToken.takeIf {
                    it.startsWith("nx2.") &&
                        session.nexGameTokenExpiresAtEpochSeconds >
                        System.currentTimeMillis() / 1000L
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
        if (!enabled) return
        val context = synchronized(lock) { applicationContext } ?: return
        val session = runCatching {
            NextendoSessionRefresher.getValidSession(context, TOKEN_REFRESH_MARGIN_SECONDS)
        }.getOrNull() ?: return

        // The published account service validates web-session or nx2. game tokens
        // in /api/presence. Public OAuth access tokens are a separate token class.
        val accessToken = session.nexGameToken
        val nowEpochSeconds = System.currentTimeMillis() / 1000L
        if (!accessToken.startsWith("nx2.") ||
            session.nexGameTokenExpiresAtEpochSeconds <= nowEpochSeconds
        ) return

        val titleId = gameTitleId
        val inGame = titleId.isNotBlank()
        runCatching {
            NextendoAuthClient.postPresence(
                accessToken = accessToken,
                status = if (inGame) 2 else 1,
                appField = if (inGame) appFieldBase64 else "",
                appId = if (inGame) titleId else "",
                appDetail = if (inGame) gameDetail else ""
            )
        }

        // Keep qLaunch's in-process friends cache in sync while the menu stays open.
        // Failed network refreshes leave the last validated snapshot in place.
        runCatching {
            NextendoAuthClient.fetchGameFriends(accessToken)
        }.onSuccess(NextendoNativeBridge::replaceFriendCache)

        // Network failures are retried on the next heartbeat; never log tokens.
    }
}
