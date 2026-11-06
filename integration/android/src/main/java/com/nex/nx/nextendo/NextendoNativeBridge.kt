package com.nex.nx.nextendo

import org.yuzu.yuzu_emu.NativeLibrary

/**
 * Transfers only player-consented identity/friend data to Eden's in-process HLE cache.
 * This is not a NEX/PRUDP session, game token, attestation or presence source.
 */
internal object NextendoNativeBridge {
    fun replaceFriendCache(friends: List<NextendoSocialApi.Friend>) {
        val normalized = friends.asSequence()
            .filter { it.pid > 0L && it.username.isNotBlank() }
            .distinctBy { it.pid }
            .take(300)
            .toList()

        NativeLibrary.updateNextendoFriendCache(
            normalized.map { it.pid }.toLongArray(),
            normalized.map { it.username }.toTypedArray()
        )
    }

    fun clearFriendCache() {
        NativeLibrary.clearNextendoFriendCache()
    }
}
