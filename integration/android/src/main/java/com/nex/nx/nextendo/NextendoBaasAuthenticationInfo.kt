package com.nex.nx.nextendo

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Encodes the BAAS id_token as the NEX AuthenticationInfo DataHolder carried
 * by TicketGranting.LoginEx.extraData.
 *
 * The signed JWT's nnex claim must contain the exact Nextendo-issued nx2. token.
 * The resulting bytes are LoginEx extraData; they are NOT the PRUDP CONNECT
 * payload (which carries the Kerberos ticket and encrypted check request).
 */
internal object NextendoBaasAuthenticationInfo {
    private const val HOLDER_NAME = "AuthenticationInfo"
    private const val STRUCTURE_VERSION = 1

    fun encodeLoginExtraData(
        idToken: String,
        expectedGameToken: String,
        serverVersion: Int = NextendoSmm2Profile.SERVER_VERSION,
        ngsVersion: Int = NextendoSmm2Profile.AUTHENTICATION_INFO_NGS_VERSION,
        tokenType: Int = NextendoSmm2Profile.AUTHENTICATION_INFO_TOKEN_TYPE
    ): ByteArray {
        require(expectedGameToken.startsWith("nx2.") && expectedGameToken.length > 4) {
            "A Nextendo-issued nx2. token is required."
        }
        require(serverVersion > 0 && ngsVersion >= 0 && tokenType in 0..0xFF) {
            "AuthenticationInfo version fields are invalid."
        }

        val jwtParts = idToken.split('.')
        require(jwtParts.size == 3 && jwtParts.all { it.isNotEmpty() }) {
            "BAAS id_token must be a three-part compact JWT."
        }
        val payload = runCatching {
            val decoded = Base64.getUrlDecoder().decode(jwtParts[1])
            Json.parseToJsonElement(String(decoded, StandardCharsets.UTF_8)).jsonObject
        }.getOrNull() ?: throw IllegalArgumentException("BAAS id_token payload is invalid.")
        val boundGameToken = runCatching {
            payload["nnex"]?.jsonPrimitive?.content
        }.getOrNull()
        require(boundGameToken == expectedGameToken) {
            "BAAS id_token nnex claim does not match the current Nextendo game token."
        }

        // AuthenticationInfo v1 fields as used by the Switch NEX server profile:
        // Token (NEX String), NGSVersion (u32), TokenType (u8), ServerVersion (u32).
        val fields = ByteArrayOutputStream().apply {
            writeNexString(idToken)
            writeUInt32(ngsVersion)
            write(tokenType)
            writeUInt32(serverVersion)
        }.toByteArray()

        // NEX Structure: version byte + u32-length-prefixed structure fields.
        val structure = ByteArrayOutputStream().apply {
            write(STRUCTURE_VERSION)
            writeUInt32(fields.size)
            write(fields)
        }.toByteArray()

        // DataHolder: name, u32(body length + 4), then a Buffer(body).
        return ByteArrayOutputStream().apply {
            writeNexString(HOLDER_NAME)
            writeUInt32(structure.size + 4)
            writeUInt32(structure.size)
            write(structure)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeNexString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size + 1 <= 0xFFFF) { "NEX String is too long." }
        writeUInt16(bytes.size + 1)
        write(bytes)
        write(0)
    }

    private fun ByteArrayOutputStream.writeUInt16(value: Int) {
        require(value in 0..0xFFFF)
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeUInt32(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}
