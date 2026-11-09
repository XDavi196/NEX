package com.nex.nx.nextendo

import org.yuzu.yuzu_emu.NativeLibrary

/**
 * Transfers the Nextendo game-token friend snapshot to Eden's in-process HLE.
 * OAuth-only records are intentionally not accepted by this bridge.
 */
internal object NextendoNativeBridge {
    fun replaceFriendCache(friends: List<NextendoGameFriend>) {
        val normalized = friends.asSequence()
            .filter { it.pid > 0L && it.networkServiceAccountId > 0L && it.username.isNotBlank() && it.presenceStatus in 0..2 }
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

    data class GuestPresence(
        val status: Int,
        val appField: ByteArray
    )

    /** Actual 0xC0 AppKeyValueStorage captured by nn::friends::UpdateUserPresence. */
    fun guestPresenceSnapshot(): GuestPresence? {
        val bytes = runCatching { NativeLibrary.getNextendoLocalPresence() }.getOrNull()
            ?: return null
        if (bytes.size != 1 + 0xC0) return null
        val status = bytes[0].toInt() and 0xFF
        if (status !in 0..2) return null
        return GuestPresence(status, bytes.copyOfRange(1, bytes.size))
    }

    fun clearLocalPresence() {
        runCatching { NativeLibrary.clearNextendoLocalPresence() }
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

    /**
     * Installs one explicit hostname-to-IPv4 route in Eden's guest DNS resolver.
     * The resolver keeps the original hostname in its result for TLS/SNI.
     */
    fun updateDnsRedirect(hostname: String, ipv4: String) {
        require(NativeLibrary.updateNextendoDnsRedirect(hostname, ipv4)) {
            "Nextendo DNS route was rejected. Use one exact hostname and a valid IPv4 address."
        }
    }

    /** Routes only the two recognized NNCS hostname families to their separate vantage IPs. */
    fun updateNncsRedirects(serverIpv4: String, natIpv4: String) {
        require(NativeLibrary.updateNextendoNncsRedirects(serverIpv4, natIpv4)) {
            "As rotas DNS dos dois pontos NNCS foram rejeitadas. Verifique os IPv4 configurados."
        }
    }

    fun clearDnsRedirects() {
        runCatching { NativeLibrary.clearNextendoDnsRedirects() }
    }

    fun clearObservedExternalIpv4() {
        runCatching { NativeLibrary.clearNextendoObservedExternalIpv4() }
    }

    /** Reads only the bounded SYN/CONNECT metadata snapshot collected by Eden SSL. */
    fun prudpDiagnostics(): List<String> =
        runCatching {
            NativeLibrary.getNextendoPrudpDiagnostics()?.toList().orEmpty()
        }.getOrDefault(emptyList())

    /** Prevents a previous linked account's network metadata from being shown. */
    fun clearPrudpDiagnostics() {
        runCatching { NativeLibrary.clearNextendoPrudpDiagnostics() }
    }
}
