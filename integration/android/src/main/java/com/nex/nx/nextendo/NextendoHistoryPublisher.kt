package com.nex.nx.nextendo

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** Pushes the real Eden playtime database total to Nextendo after a game session ends. */
internal object NextendoHistoryPublisher {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NEX-Nextendo-History").apply { isDaemon = true }
    }

    @Volatile
    private var activeTitleId: Long = 0L

    fun onGameStarted(titleId: Long) {
        activeTitleId = titleId.takeIf { it > 0L } ?: 0L
    }

    /**
     * readPlayTime is Eden's native PlayTimeManager accessor. The total is read
     * before the network worker starts; the server merges this one changed title.
     */
    fun onGameStopped(
        context: Context,
        readPlayTime: (String) -> Long
    ) {
        val titleId = activeTitleId
        activeTitleId = 0L
        if (titleId <= 0L) return

        val seconds = runCatching { readPlayTime(titleId.toString()) }.getOrDefault(0L)
        if (seconds <= 0L) return

        val titleIdHex = java.lang.String.format(Locale.ROOT, "%016X", titleId)
        val lastPlayed = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.US
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                val session = NextendoSessionRefresher.getValidSession(appContext, 60L)
                NextendoAccountExtrasApi.syncHistory(
                    accessToken = session.accessToken,
                    entries = listOf(
                        NextendoAccountExtrasApi.HistoryEntry(
                            titleId = titleIdHex,
                            name = "",
                            iconBase64 = "",
                            seconds = seconds,
                            lastPlayed = lastPlayed
                        )
                    )
                )
            }
        }
    }

    fun clear() {
        activeTitleId = 0L
    }
}
