// SPDX-License-Identifier: GPL-3.0-or-later
// NEX adaptation of DEN's foreground unread-message notifications.
// Upstream: https://github.com/Dmr77680/Den2 (GPL-3.0-or-later).

package com.nex.nx.nextendo

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground-only unread-message polling, enabled only after explicit chat consent. */
internal object NextendoChatNotifications {
    private const val REFRESH_INTERVAL_MS = 20_000L
    private const val NAMES_REFRESH_INTERVAL_MS = 5 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val names = ConcurrentHashMap<Long, String>()
    private val checking = AtomicBoolean(false)
    private val seen = HashMap<Long, Long>()

    @Volatile private var hostActivity: Activity? = null
    @Volatile private var polling = false
    private var firstCheck = true
    private var lastNamesRefreshAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val activity = hostActivity
            if (!polling || activity == null) return
            if (checking.compareAndSet(false, true)) {
                Thread {
                    try {
                        checkUnread(activity)
                    } finally {
                        checking.set(false)
                    }
                }.start()
            }
            main.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    fun rememberFriends(friends: List<NextendoSocialApi.Friend>) {
        friends.forEach { friend ->
            if (friend.pid > 0L && friend.username.isNotBlank()) {
                names[friend.pid] = friend.username
            }
        }
    }

    fun start(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val session = runCatching { NextendoSessionStore.load(activity) }.getOrNull() ?: return
        if (session.pid <= 0L || !NextendoChatIdentityStore.hasConsent(activity, session.pid)) return

        hostActivity = activity
        if (polling) return
        polling = true
        firstCheck = true
        main.postDelayed(ticker, 4_000L)
    }

    fun stop(activity: Activity? = null) {
        val current = hostActivity
        if (activity != null && current != null && current !== activity) return
        polling = false
        hostActivity = null
        main.removeCallbacks(ticker)
    }

    private fun checkUnread(activity: Activity) {
        if (!isCurrent(activity)) return
        val session = runCatching { NextendoSessionStore.load(activity) }.getOrNull() ?: return
        if (session.pid <= 0L || !NextendoChatIdentityStore.hasConsent(activity, session.pid)) {
            stop(activity)
            return
        }

        val response = NextendoChatClient.unread(activity)
        if (response.code !in 200..299) return
        val unread = response.json?.optJSONArray("unread") ?: return

        val now = System.currentTimeMillis()
        val needsNames = (0 until unread.length()).any { index ->
            val entry = unread.optJSONObject(index)
            entry != null && entry.optLong("pid") > 0L &&
                !names.containsKey(entry.optLong("pid"))
        }
        if (needsNames && now - lastNamesRefreshAt >= NAMES_REFRESH_INTERVAL_MS) {
            lastNamesRefreshAt = now
            runCatching {
                rememberFriends(NextendoSocialApi.fetchFriendList(session.accessToken).friends)
            }
        }

        val newMessages = ArrayList<String>()
        for (index in 0 until unread.length()) {
            val entry = unread.optJSONObject(index) ?: continue
            val friendPid = entry.optLong("pid")
            val lastMessageId = entry.optLong("last_id")
            if (friendPid <= 0L || lastMessageId <= 0L) continue
            val previous = seen[friendPid] ?: 0L
            seen[friendPid] = lastMessageId
            if (lastMessageId > previous &&
                !NextendoChatDialog.isConversationOpenFor(friendPid)
            ) {
                newMessages.add(names[friendPid] ?: "um amigo")
            }
        }

        val wasFirstCheck = firstCheck
        firstCheck = false
        if (newMessages.isEmpty() || !isCurrent(activity)) return

        val people = newMessages.distinct().joinToString(", ")
        main.post {
            if (!isCurrent(activity)) return@post
            val message = if (wasFirstCheck) {
                "Você tem mensagens não lidas de $people."
            } else {
                "Nova mensagem de $people."
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun isCurrent(activity: Activity): Boolean =
        polling && hostActivity === activity && !activity.isFinishing && !activity.isDestroyed
}
