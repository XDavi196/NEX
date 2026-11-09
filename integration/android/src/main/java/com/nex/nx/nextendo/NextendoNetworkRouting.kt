package com.nex.nx.nextendo

import android.content.Context
import okhttp3.Dns

/**
 * Installs the explicit, title-specific host routes in Eden's native resolver.
 *
 * All listed game auth hosts share the configured main server address; TLS SNI
 * remains the original game hostname, allowing the Nextendo SNI router to select
 * the correct per-title auth service. No wildcard Nintendo redirect is installed.
 */
internal object NextendoNetworkRouting {
    @Volatile
    var diagnostic: String = "not_configured"
        private set

    @Volatile
    var installedRouteCount: Int = 0
        private set

    fun restore(context: Context) {
        val config = NextendoNetworkConfigStore.load(context.applicationContext)
        if (config == null) {
            NextendoNativeBridge.clearDnsRedirects()
            installedRouteCount = 0
            diagnostic = "server_addresses_not_configured"
            return
        }
        apply(config)
    }

    /** Creates an OkHttp client whose exact-host DNS matches Eden's guest resolver. */
    fun createHttpClient(
        context: Context,
        fallbackDns: Dns = Dns.SYSTEM
    ): okhttp3.OkHttpClient {
        val config = NextendoNetworkConfigStore.load(context.applicationContext)
        return NextendoHttpClientFactory.create(config, fallbackDns)
    }

    fun apply(config: NextendoNetworkConfigStore.Config) {
        NextendoNativeBridge.clearDnsRedirects()
        installedRouteCount = 0
        try {
            NextendoNativeBridge.updateNncsRedirects(config.serverIpv4, config.natIpv4)
            installedRouteCount += 2
            NextendoGameNetworkProfiles.allExactAuthHosts.forEach { hostname ->
                NextendoNativeBridge.updateDnsRedirect(hostname, config.serverIpv4)
                installedRouteCount += 1
            }
            diagnostic = "exact_and_nncs_routes_installed"
        } catch (error: Throwable) {
            NextendoNativeBridge.clearDnsRedirects()
            installedRouteCount = 0
            diagnostic = "route_install_failed"
            throw IllegalStateException(
                "Não foi possível instalar todas as rotas DNS seguras do Nextendo.",
                error
            )
        }
    }

    fun clear(context: Context) {
        NextendoNetworkConfigStore.clear(context.applicationContext)
        NextendoNativeBridge.clearDnsRedirects()
        installedRouteCount = 0
        diagnostic = "server_addresses_not_configured"
    }
}
