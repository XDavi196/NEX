package com.nex.nx.nextendo

import org.yuzu.yuzu_emu.NativeLibrary

/**
 * Transfers the Nextendo game-token friend snapshot to Eden's in-process HLE.
 * OAuth-only records are intentionally not accepted by this bridge.
 */
internal object NextendoNativeBridge {
    fun replaceFriendCache(friends: List<NextendoGameFriend>) {
        val normalized = friends.asSequence()
            .filter { it.pid > 0L && it.username.isNotBlank() && it.presenceStatus in 0..2 }
            .distinctBy { it.pid }
            .take(300)
            .toList()

        NativeLibrary.updateNextendoFriendCache(
            normalized.map { it.pid }.toLongArray(),
            normalized.map { it.networkServiceAccountId }.toLongArray(),
            normalized.map { it.appIdValue }.toLongArray(),
            normalized.map { it.username }.toTypedArray(),
            normalized.map { it.presenceStatus }.toIntArray(),
            normalized.map { it.appField }.toTypedArray()
        )
    }

    fun clearFriendCache() {
        runCatching { NativeLibrary.clearNextendoFriendCache() }
    }

    /** Binds games' ACC NetworkServiceAccountId to the authenticated NEX PID. */
    fun updateIdentity(pid: Long) {
        if (pid > 0L) {
            runCatching { NativeLibrary.updateNextendoIdentity(pid) }
        } else {
            clearIdentity()
        }
    }

    fun clearIdentity() {
        runCatching { NativeLibrary.clearNextendoIdentity() }
    }

    /** Makes a previously validated, provisioned BAAS JWT available to Eden ACC. */
    fun updateBaasIdToken(idToken: String) {
        require(idToken.isNotBlank()) { "BAAS id_token is empty." }
        NativeLibrary.updateNextendoBaasIdToken(idToken.toByteArray(Charsets.US_ASCII))
    }

    fun clearBaasIdToken() {
        runCatching { NativeLibrary.clearNextendoBaasIdToken() }
    }
}
