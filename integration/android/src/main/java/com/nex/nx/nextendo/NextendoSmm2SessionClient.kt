package com.nex.nx.nextendo

import okhttp3.OkHttpClient

/**
 * Explicit SMM2 NEX client-session coordinator.
 *
 * It performs the public TicketGranting.LoginEx exchange on the title auth WSS,
 * consumes the returned Kerberos client ticket and StationURL, then opens the
 * secure PRUDP WSS and validates the session-key check response. The guest game
 * can continue using its own native NEX stack; this class is also useful for
 * deterministic integration probes and future ACC/network lifecycle wiring.
 *
 * The original auth hostname and virtual PRUDP station parameters are mandatory
 * runtime inputs. This code never guesses or substitutes a production endpoint.
 */
internal class NextendoSmm2SessionClient(
    private val config: Config,
    private val events: Events,
    private val httpClient: OkHttpClient = OkHttpClient()
) : AutoCloseable {
    data class Config(
        val authHostname: String,
        val authStation: NextendoSmm2Profile.PrudpStationParameters,
        val secureStation: NextendoSmm2Profile.PrudpStationParameters,
        val loginUsername: String,
        val authenticationInfoData: ByteArray,
        val loginCallId: Long
    ) {
        init {
            require(authHostname.isNotBlank()) { "SMM2 auth hostname is not provisioned." }
            require(loginUsername.isNotBlank()) { "SMM2 LoginEx username is not provisioned." }
            require(authenticationInfoData.isNotEmpty()) {
                "SMM2 LoginEx AuthenticationInfo is missing."
            }
            require(loginCallId in 1L..0xFFFFFFFFL) { "SMM2 LoginEx call ID is invalid." }
        }
    }

    interface Events {
        fun onSecureConnected(playerPid: Long, secureStationUrl: String)
        fun onRmcMessage(message: NextendoRmcCodec.Message)
        fun onFailure(error: Throwable)
        fun onDisconnected()
    }

    @Volatile
    private var closed = false

    @Volatile
    private var secureConnected = false

    @Volatile
    private var authTransport: NextendoPrudpLiteWebSocketTransport? = null

    @Volatile
    private var secureTransport: NextendoPrudpLiteWebSocketTransport? = null

    @Volatile
    private var loginResult: NextendoSmm2TicketCodec.LoginResult? = null

    @Volatile
    private var secureCheck: NextendoSmm2TicketCodec.SecureConnectPayload? = null

    fun start() {
        check(!closed && authTransport == null) {
            "SMM2 session was already started or closed."
        }
        val session = NextendoPrudpLiteSession(
            NextendoSmm2Profile.authSessionConfig(config.authStation)
        )
        val transport = NextendoPrudpLiteWebSocketTransport(
            endpointUrl = NextendoSmm2Profile.authEndpoint(config.authHostname).toString(),
            session = session,
            events = authEvents,
            client = httpClient
        )
        authTransport = transport
        transport.connect()
    }

    fun sendRmc(
        protocolId: Int,
        methodId: Long,
        callId: Long,
        body: ByteArray = byteArrayOf()
    ) {
        check(secureConnected && !closed) {
            "SMM2 secure session is not authenticated."
        }
        val frame = NextendoRmcCodec.encodeRequest(protocolId, methodId, callId, body)
        secureTransport?.sendRmc(frame)
            ?: throw IllegalStateException("SMM2 secure transport is unavailable.")
    }

    fun isSecureConnected(): Boolean = secureConnected && !closed

    override fun close() {
        if (closed) return
        closed = true
        secureConnected = false
        authTransport?.close()
        secureTransport?.close()
        authTransport = null
        secureTransport = null
        clearLoginMaterial()
        config.authenticationInfoData.fill(0)
    }

    private val authEvents = object : NextendoPrudpLiteWebSocketTransport.Events {
        override fun onConnected(responsePayload: ByteArray) {
            if (closed) return
            if (responsePayload.isNotEmpty()) {
                fail(IllegalStateException("SMM2 auth CONNECT returned an unexpected payload."))
                return
            }
            val request = runCatching {
                NextendoSmm2LoginCodec.encodeRequest(
                    loginUsername = config.loginUsername,
                    authenticationInfo = config.authenticationInfoData,
                    callId = config.loginCallId
                )
            }.getOrElse {
                fail(it)
                return
            }
            runCatching { authTransport?.sendRmc(request) }
                .onFailure(::fail)
        }

        override fun onRmcMessage(message: NextendoRmcCodec.Message) {
            if (closed) return
            val decoded = runCatching {
                NextendoSmm2TicketCodec.decodeLoginExResponse(message, config.loginCallId)
            }.getOrElse {
                fail(it)
                return
            }
            loginResult = decoded

            val check = runCatching {
                NextendoSmm2TicketCodec.createSecureConnectPayload(decoded)
            }.getOrElse {
                fail(it)
                return
            }
            secureCheck = check

            val secureEndpoint = runCatching {
                NextendoSmm2Profile.secureEndpointFromStationUrl(decoded.secureStationUrl)
            }.getOrElse {
                fail(it)
                return
            }
            val connectPayload = check.bytes.copyOf()
            val secureSession = runCatching {
                NextendoPrudpLiteSession(
                    NextendoSmm2Profile.secureSessionConfig(
                        station = config.secureStation,
                        ticketAndCheckPayload = connectPayload
                    )
                )
            }.getOrElse {
                connectPayload.fill(0)
                fail(it)
                return
            }
            connectPayload.fill(0)
            check.bytes.fill(0)
            decoded.encryptedServerTicket.fill(0)

            val transport = NextendoPrudpLiteWebSocketTransport(
                endpointUrl = secureEndpoint.toString(),
                session = secureSession,
                events = secureEvents,
                client = httpClient
            )
            secureTransport = transport
            runCatching { transport.connect() }.onFailure(::fail)
        }

        override fun onProtocolError(reason: String) {
            fail(IllegalStateException("SMM2 auth PRUDP protocol error: " + reason))
        }

        override fun onDisconnected() {
            if (!closed && !secureConnected) {
                fail(IllegalStateException("SMM2 auth session disconnected before LoginEx completed."))
            }
        }

        override fun onFailure(error: Throwable) = fail(error)
    }

    private val secureEvents = object : NextendoPrudpLiteWebSocketTransport.Events {
        override fun onConnected(responsePayload: ByteArray) {
            if (closed) return
            val expected = secureCheck?.expectedCheckReply
            if (expected == null) {
                fail(IllegalStateException("SMM2 secure CONNECT has no expected check value."))
                return
            }
            runCatching {
                NextendoSmm2TicketCodec.validateSecureConnectAcknowledgement(
                    responsePayload,
                    expected
                )
            }.onFailure {
                fail(it)
                return
            }
            val result = loginResult
            if (result == null) {
                fail(IllegalStateException("SMM2 LoginEx result was lost before secure CONNECT."))
                return
            }
            secureConnected = true
            authTransport?.close()
            authTransport = null
            events.onSecureConnected(result.playerPid, result.secureStationUrl)
            clearLoginMaterial()
        }

        override fun onRmcMessage(message: NextendoRmcCodec.Message) {
            if (closed || !secureConnected) return
            events.onRmcMessage(message)
        }

        override fun onProtocolError(reason: String) {
            fail(IllegalStateException("SMM2 secure PRUDP protocol error: " + reason))
        }

        override fun onDisconnected() {
            if (closed) return
            closed = true
            secureConnected = false
            secureTransport = null
            events.onDisconnected()
        }

        override fun onFailure(error: Throwable) = fail(error)
    }

    @Synchronized
    private fun fail(error: Throwable) {
        if (closed) return
        closed = true
        secureConnected = false
        authTransport?.close()
        secureTransport?.close()
        authTransport = null
        secureTransport = null
        clearLoginMaterial()
        config.authenticationInfoData.fill(0)
        events.onFailure(error)
    }

    private fun clearLoginMaterial() {
        loginResult?.sessionKey?.fill(0)
        loginResult = null
        secureCheck?.bytes?.fill(0)
        secureCheck = null
    }
}
