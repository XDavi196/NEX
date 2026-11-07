package com.nex.nx.nextendo

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** TicketGranting.LoginEx (protocol 0x0A, method 2) request framing for NEX 4.0.0. */
internal object NextendoSmm2LoginCodec {
    const val PROTOCOL_ID = 0x0A
    const val METHOD_LOGIN_EX = 2L

    /**
     * LoginEx carries a NEX String followed directly by the AuthenticationInfo
     * DataHolder bytes. The public Nextendo server reads all remaining bytes as
     * extraData; it is not wrapped in an additional NEX Buffer.
     */
    fun encodeBody(loginUsername: String, authenticationInfo: ByteArray): ByteArray {
        require(loginUsername.isNotBlank()) { "SMM2 LoginEx username is missing." }
        val usernameBytes = loginUsername.toByteArray(StandardCharsets.UTF_8)
        require(usernameBytes.size + 1 in 1..0xFFFF) { "SMM2 LoginEx username is too long." }
        require(authenticationInfo.isNotEmpty()) {
            "SMM2 LoginEx requires the AuthenticationInfo DataHolder."
        }

        return ByteArrayOutputStream(usernameBytes.size + authenticationInfo.size + 3).apply {
            val stringLength = usernameBytes.size + 1
            write(stringLength and 0xFF)
            write((stringLength ushr 8) and 0xFF)
            write(usernameBytes)
            write(0)
            write(authenticationInfo)
        }.toByteArray()
    }

    fun encodeRequest(
        loginUsername: String,
        authenticationInfo: ByteArray,
        callId: Long
    ): ByteArray = NextendoRmcCodec.encodeRequest(
        protocolId = PROTOCOL_ID,
        methodId = METHOD_LOGIN_EX,
        callId = callId,
        body = encodeBody(loginUsername, authenticationInfo)
    )
}
