package com.nex.nx.nextendo

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * OkHttp WSS adapter for the tested PRUDP-Lite session engine.
 *
 * The endpoint URL is supplied by the caller and must use wss://. OkHttp's normal
 * TLS hostname and certificate verification stays enabled. The adapter does not
 * add DNS overrides, trust-all certificates, title hosts or access keys.
 */
internal class NextendoPrudpLiteWebSocketTransport(
    private val endpointUrl: String,
    private val session: NextendoPrudpLiteSession,
    private val events: Events,
    private val client: OkHttpClient = OkHttpClient()
) : WebSocketListener(), AutoCloseable {
    interface Events {
        fun onConnected(responsePayload: ByteArray)
        fun onRmcMessage(message: NextendoRmcCodec.Message)
        fun onProtocolError(reason: String)
        fun onDisconnected()
        fun onFailure(error: Throwable)
    }

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    private val timerExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "NEX-PRUDP-Lite-retry").apply { isDaemon = true }
    }

    @Volatile
    private var retransmissionTask: ScheduledFuture<*>? = null

    fun connect() {
        val uri = runCatching { URI(endpointUrl) }.getOrNull()
        require(
            uri != null &&
                uri.scheme.equals("wss", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        ) { "Nextendo PRUDP endpoint must be a wss:// URL without user information." }
        check(socket == null && !closed) {
            "PRUDP WebSocket transport was already started or closed."
        }

        val request = Request.Builder()
            .url(endpointUrl)
            .build()
        socket = client.newWebSocket(request, this)
    }

    fun isOpen(): Boolean = socket != null && !closed

    fun sendRmc(encodedRmc: ByteArray) {
        check(!closed) { "PRUDP WebSocket transport is closed." }
        val activeSocket = socket ?: throw IllegalStateException("PRUDP WebSocket is not open.")
        val step = session.sendRmc(encodedRmc)
        sendFrames(activeSocket, step.outboundFrames)
    }

    fun pendingReliableCount(): Int = session.pendingReliableCount()

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (closed) {
            webSocket.close(1000, "NEX session closed")
            return
        }
        socket = webSocket
        runCatching { session.start() }
            .onSuccess {
                sendFrames(webSocket, it.outboundFrames)
                startRetransmissionTimer()
            }
            .onFailure(::fail)
    }

    private fun startRetransmissionTimer() {
        if (closed || retransmissionTask != null) return
        retransmissionTask = timerExecutor.scheduleAtFixedRate({
            if (closed) return@scheduleAtFixedRate
            val activeSocket = socket ?: return@scheduleAtFixedRate
            runCatching { session.tick() }
                .onSuccess { step -> sendFrames(activeSocket, step.outboundFrames) }
                .onFailure(::fail)
        }, 250L, 250L, TimeUnit.MILLISECONDS)
    }

    private fun stopRetransmissionTimer() {
        retransmissionTask?.cancel(false)
        retransmissionTask = null
        if (!timerExecutor.isShutdown) timerExecutor.shutdownNow()
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (closed) return
        runCatching { session.receive(bytes.toByteArray()) }
            .onSuccess { step ->
                sendFrames(webSocket, step.outboundFrames)
                step.events.forEach { event ->
                    when (event) {
                        is NextendoPrudpLiteSession.Event.Connected ->
                            events.onConnected(event.responsePayload)
                        is NextendoPrudpLiteSession.Event.RmcMessage ->
                            events.onRmcMessage(event.message)
                        is NextendoPrudpLiteSession.Event.ProtocolError ->
                            events.onProtocolError(event.reason)
                        NextendoPrudpLiteSession.Event.Disconnected -> {
                            closed = true
                            stopRetransmissionTimer()
                            session.close()
                            webSocket.close(1000, "Nextendo PRUDP session disconnected")
                            socket = null
                            events.onDisconnected()
                        }
                    }
                }
            }
            .onFailure(::fail)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        events.onProtocolError("unexpected_text_websocket_frame")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (!closed) {
            closed = true
            stopRetransmissionTimer()
            session.close()
            events.onDisconnected()
        } else {
            stopRetransmissionTimer()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        fail(t)
    }

    override fun close() {
        if (!closed) {
            closed = true
            session.close()
            socket?.close(1000, "NEX session closed")
            socket = null
        }
        stopRetransmissionTimer()
    }

    private fun sendFrames(webSocket: WebSocket, frames: List<ByteArray>) {
        for (frame in frames) {
            if (!webSocket.send(ByteString.of(*frame))) {
                fail(IOException("OkHttp refused a PRUDP-Lite WebSocket frame."))
                return
            }
        }
    }

    private fun fail(error: Throwable) {
        if (closed) return
        closed = true
        stopRetransmissionTimer()
        session.close()
        socket?.cancel()
        socket = null
        events.onFailure(error)
    }
}
