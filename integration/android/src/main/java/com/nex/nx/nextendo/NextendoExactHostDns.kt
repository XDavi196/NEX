package com.nex.nx.nextendo

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Resolves only known Nextendo game/DAUTH hostnames to the configured server IP.
 *
 * The request URL is never rewritten: OkHttp still uses the original hostname
 * for TLS certificate verification and SNI. All unrelated hostnames are handed
 * to the platform DNS resolver unchanged.
 */
internal class NextendoExactHostDns(
    serverIpv4: String,
    private val fallback: Dns = Dns.SYSTEM,
    exactHosts: Set<String> = NextendoGameNetworkProfiles.allExactAuthHosts.toSet()
) : Dns {
    private val serverAddress: Inet4Address
    private val redirectedHosts = exactHosts.mapTo(linkedSetOf()) {
        it.trimEnd('.').lowercase()
    }

    init {
        require(NextendoNetworkAddress.isValidIpv4(serverIpv4)) {
            "Nextendo DNS server address must be a valid IPv4 address."
        }
        val bytes = serverIpv4.split('.').map { it.toInt() }.map(Int::toByte).toByteArray()
        serverAddress = InetAddress.getByAddress(bytes) as Inet4Address
        require(redirectedHosts.isNotEmpty() && redirectedHosts.all { it.isNotBlank() }) {
            "Nextendo exact-host routing list is empty or invalid."
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val normalized = hostname.trimEnd('.').lowercase()
        return if (normalized in redirectedHosts) {
            listOf(serverAddress)
        } else {
            fallback.lookup(hostname)
        }
    }
}

/** Builds OkHttp clients that share the same exact-host routing as guest DNS. */
internal object NextendoHttpClientFactory {
    fun create(
        config: NextendoNetworkConfigStore.Config?,
        fallbackDns: Dns = Dns.SYSTEM
    ): okhttp3.OkHttpClient {
        val builder = okhttp3.OkHttpClient.Builder()
        if (config != null) {
            builder.dns(NextendoExactHostDns(config.serverIpv4, fallbackDns))
        } else {
            builder.dns(fallbackDns)
        }
        return builder.build()
    }
}
