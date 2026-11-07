package com.nex.nx.nextendo

import java.net.URI
import java.util.Locale

/**
 * Publicly documented Super Mario Maker 2 client profile for Nextendo.
 *
 * The title access key and NEX version below are public per-title values copied
 * from NextendoNetwork/super-mario-maker-2. They are not account credentials.
 * The secure server endpoint is taken from the StationURL returned by LoginEx;
 * the deployment's real hostname and port must not be guessed.
 */
internal object NextendoSmm2Profile {
    const val NEX_VERSION = 40000

    /**
     * Public SMM2 title access key from the public Nextendo SMM2 server source.
     * This is the PRUDP Lite CONNECT title key, not the server Kerberos password.
     */
    const val PRUDP_ACCESS_KEY = "fdf6617f"

    const val AUTH_WSS_PORT = 443
    const val DEFAULT_SECURE_WSS_PORT = 60007

    // SMM2 server profile: auth LoginEx advertises the generic Switch minor version,
    // but the secure endpoint explicitly overrides it to zero.
    const val AUTH_PRUDP_MINOR_VERSION = 5
    const val SECURE_PRUDP_MINOR_VERSION = 0

    const val DEFAULT_FRAGMENT_SIZE = 1300
    const val SERVER_VERSION = NEX_VERSION
    const val AUTHENTICATION_INFO_NGS_VERSION = 3
    const val AUTHENTICATION_INFO_TOKEN_TYPE = 1

    /**
     * Builds the auth WebSocket URI while preserving the original title hostname.
     * DNS routing must redirect that hostname to the configured service IP; do
     * not replace it with an IP in this URI or TLS hostname verification will differ.
     */
    fun authEndpoint(titleHostname: String): URI =
        wssEndpoint(titleHostname, AUTH_WSS_PORT)

    /**
     * Converts the secure StationURL advertised by LoginEx into its WSS endpoint.
     *
     * The public SMM2 service uses a `prudps:/address=...;port=...` StationURL.
     * Its deployed port can be overridden, so the received StationURL is
     * authoritative; DEFAULT_SECURE_WSS_PORT is only the public server's local
     * default and is deliberately not used as a fallback here.
     */
    fun secureEndpointFromStationUrl(stationUrl: String): URI {
        require(stationUrl.isNotBlank()) { "SMM2 secure StationURL is missing." }
        val separator = stationUrl.indexOf(":/")
        require(separator > 0) { "SMM2 secure StationURL has an invalid scheme." }

        val scheme = stationUrl.substring(0, separator).lowercase(Locale.ROOT)
        require(scheme == "prudps") { "SMM2 secure StationURL must use prudps." }

        val parameters = linkedMapOf<String, String>()
        stationUrl.substring(separator + 2)
            .split(';')
            .filter { it.isNotBlank() }
            .forEach { part ->
                val index = part.indexOf('=')
                require(index > 0 && index < part.lastIndex) {
                    "SMM2 secure StationURL contains an invalid parameter."
                }
                val key = part.substring(0, index)
                val value = part.substring(index + 1)
                require(key !in parameters) {
                    "SMM2 secure StationURL contains a duplicate parameter."
                }
                parameters[key] = value
            }

        val host = parameters["address"]
            ?: throw IllegalArgumentException("SMM2 secure StationURL has no address.")
        val port = parameters["port"]?.toIntOrNull()
            ?: throw IllegalArgumentException("SMM2 secure StationURL has no port.")
        return wssEndpoint(host, port)
    }

    private fun wssEndpoint(host: String, port: Int): URI {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' || it == '@' }) {
            "SMM2 endpoint host is invalid."
        }
        require(port in 1..65535) { "SMM2 endpoint port is out of range." }

        val uri = runCatching {
            URI("wss", null, host, port, "/", null, null)
        }.getOrNull()
            ?: throw IllegalArgumentException("SMM2 WSS endpoint could not be constructed.")
        require(uri.host == host || uri.host.equals(host, ignoreCase = true)) {
            "SMM2 WSS endpoint host was not preserved."
        }
        return uri
    }
}
