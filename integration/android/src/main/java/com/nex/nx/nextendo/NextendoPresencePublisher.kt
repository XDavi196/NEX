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
 * 90 seconds. NEX sends one every 60 seconds, but only with an unexpired OAuth
 * access token that includes the player's presence scope. The nx2. game token
 * is never used for account-presence publishing. The Eden overlay calls
 * setGameActivity() from EmulationActivity using the current title-ID bridge.
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

    /** Start the heartbeat scheduler; publishing requires the OAuth presence scope. */
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
            ""
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
        val nowEpochSeconds = System.currentTimeMillis() / 1000L
        val accessToken = runCatching {
            NextendoSessionStore.load(appContext)?.let { session ->
                NextendoPresenceAuthorization.usableAccessToken(
                    accessToken = session.accessToken,
                    grantedScopes = session.grantedScopes,
                    expiresAtEpochSeconds = session.accessTokenExpiresAtEpochSeconds,
                    nowEpochSeconds = nowEpochSeconds
                )
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

        // Follow the documented OAuth presence scope. The public account-server
        // source currently disagrees and may reject this until its deployed contract
        // is aligned; do not fall back to the game token.
        val accessToken = NextendoPresenceAuthorization.usableAccessToken(
            accessToken = session.accessToken,
            grantedScopes = session.grantedScopes,
            expiresAtEpochSeconds = session.accessTokenExpiresAtEpochSeconds,
            nowEpochSeconds = System.currentTimeMillis() / 1000L
        ) ?: return

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

        // Friend data is a separate game-token contract. Never pass the OAuth
        // access token to /api/friends; keep the last validated snapshot on failure.
        val nowEpochSeconds = System.currentTimeMillis() / 1000L
        val gameToken = session.nexGameToken.takeIf {
            it.startsWith("nx2.") &&
                session.nexGameTokenExpiresAtEpochSeconds > nowEpochSeconds
        }
        if (gameToken != null) {
            runCatching {
                NextendoAuthClient.fetchGameFriends(gameToken)
            }.onSuccess(NextendoNativeBridge::replaceFriendCache)
        }

        // Network failures are retried on the next heartbeat; never log tokens.
    }
}
