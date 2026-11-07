package com.nex.nx.nextendo

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client side of the public Nextendo NEX 4 TicketGranting.LoginEx response and
 * secure PRUDP CONNECT proof. The client decrypts only its outer ClientTicket
 * with the one-time source key returned by LoginEx; the inner server ticket stays
 * opaque and is forwarded to the secure server.
 */
internal object NextendoSmm2TicketCodec {
    private const val SESSION_KEY_BYTES = 32
    private const val HMAC_BYTES = 16
    private const val RESULT_ERROR_MASK = 0x80000000L
    private val random = SecureRandom()

    data class LoginResult(
        val playerPid: Long,
        val secureTargetPid: Long,
        val secureStationUrl: String,
        val specialProtocols: List<Int>,
        val specialStationUrl: String,
        val serverTime: Long,
        val serverName: String,
        internal val sessionKey: ByteArray,
        internal val encryptedServerTicket: ByteArray
    )

    data class SecureConnectPayload(
        val bytes: ByteArray,
        val clientConnectionId: Long,
        val checkValue: Long,
        val expectedCheckReply: Long
    )

    fun decodeLoginExResponse(
        message: NextendoRmcCodec.Message,
        expectedCallId: Long
    ): LoginResult {
        if (message.kind == NextendoRmcCodec.Kind.ERROR_RESPONSE) {
            throw IllegalStateException(
                "Nextendo LoginEx was rejected (result=0x" +
                    (message.resultCode ?: 0L).toString(16) + ")."
            )
        }
        require(message.kind == NextendoRmcCodec.Kind.SUCCESS_RESPONSE) {
            "Nextendo LoginEx returned an unexpected RMC response."
        }
        require(message.protocolId == NextendoSmm2LoginCodec.PROTOCOL_ID) {
            "Nextendo LoginEx response used an unexpected protocol."
        }
        require(message.methodId == NextendoSmm2LoginCodec.METHOD_LOGIN_EX) {
            "Nextendo LoginEx response used an unexpected method."
        }
        require(message.callId == expectedCallId) {
            "Nextendo LoginEx response call ID did not match the request."
        }

        val input = Reader(message.body)
        val resultCode = input.readUInt32LE()
        require(resultCode and RESULT_ERROR_MASK == 0L) {
            "Nextendo LoginEx failed (result=0x" + resultCode.toString(16) + ")."
        }
        val playerPid = input.readUInt64LE()
        require(playerPid > 0L) { "Nextendo LoginEx returned an invalid player PID." }
        val encryptedClientTicket = input.readBuffer(64 * 1024)
        require(encryptedClientTicket.isNotEmpty()) {
            "Nextendo LoginEx did not return a client ticket."
        }

        val structureVersion = input.readUInt8()
        require(structureVersion == 1) {
            "Nextendo LoginEx returned an unsupported connection-data structure version."
        }
        val structureLength = input.readUInt32LE()
        require(structureLength <= input.remaining().toLong()) {
            "Nextendo LoginEx connection-data structure is truncated."
        }
        val structure = Reader(input.readBytes(structureLength.toInt()))
        val secureStationUrl = structure.readNexString()
        require(secureStationUrl.isNotBlank()) {
            "Nextendo LoginEx did not advertise a secure StationURL."
        }
        val specialProtocolCount = structure.readUInt32LE()
        require(specialProtocolCount <= 64L) {
            "Nextendo LoginEx returned too many special protocols."
        }
        val specialProtocols = List(specialProtocolCount.toInt()) { structure.readUInt8() }
        val specialStationUrl = structure.readNexString()
        val serverTime = structure.readUInt64LE()
        require(structure.remaining() == 0) {
            "Nextendo LoginEx connection-data structure contains trailing bytes."
        }

        val serverName = input.readNexString()
        val sourceKeyHex = input.readNexString()
        require(input.remaining() == 0) {
            "Nextendo LoginEx response contains unexpected trailing data."
        }
        val sourceKey = decodeHex(sourceKeyHex)
        require(sourceKey.size in 1..64) { "Nextendo LoginEx returned an invalid source key." }

        // Validate the server-advertised endpoint. There is no guessed secure host/port.
        NextendoSmm2Profile.secureEndpointFromStationUrl(secureStationUrl)

        val decryptedClientTicket = try {
            kerberosDecrypt(sourceKey, encryptedClientTicket)
        } finally {
            sourceKey.fill(0)
        }
        val ticketInput = Reader(decryptedClientTicket)
        val sessionKey = ticketInput.readBytes(SESSION_KEY_BYTES)
        val secureTargetPid = ticketInput.readUInt64LE()
        val encryptedServerTicket = ticketInput.readBuffer(64 * 1024)
        require(ticketInput.remaining() == 0) {
            "Nextendo client ticket contains unexpected trailing data."
        }
        require(secureTargetPid > 0L && encryptedServerTicket.isNotEmpty()) {
            "Nextendo LoginEx returned an invalid secure-server ticket."
        }
        decryptedClientTicket.fill(0)

        return LoginResult(
            playerPid = playerPid,
            secureTargetPid = secureTargetPid,
            secureStationUrl = secureStationUrl,
            specialProtocols = specialProtocols,
            specialStationUrl = specialStationUrl,
            serverTime = serverTime,
            serverName = serverName,
            sessionKey = sessionKey,
            encryptedServerTicket = encryptedServerTicket
        )
    }

    /**
     * Serializes [Buffer(server ticket)][Buffer(encrypted check request)] for
     * the secure PRUDP CONNECT. The session key is cleared after this is built.
     */
    fun createSecureConnectPayload(
        login: LoginResult,
        clientConnectionId: Long = randomUInt32(),
        checkValue: Long = randomUInt32()
    ): SecureConnectPayload {
        require(clientConnectionId in 0L..0xFFFFFFFFL) {
            "Secure client connection ID is outside the unsigned 32-bit range."
        }
        require(checkValue in 0L..0xFFFFFFFFL) {
            "Secure CONNECT check value is outside the unsigned 32-bit range."
        }
        require(login.sessionKey.size == SESSION_KEY_BYTES) {
            "The LoginEx session key has an invalid length."
        }

        val checkData = ByteArrayOutputStream(16).apply {
            writeUInt64LE(login.playerPid)
            writeUInt32LE(clientConnectionId)
            writeUInt32LE(checkValue)
        }.toByteArray()
        val encryptedCheck = try {
            kerberosEncrypt(login.sessionKey, checkData)
        } finally {
            login.sessionKey.fill(0)
            checkData.fill(0)
        }

        val connectPayload = ByteArrayOutputStream(
            login.encryptedServerTicket.size + encryptedCheck.size + 8
        ).apply {
            writeBuffer(login.encryptedServerTicket)
            writeBuffer(encryptedCheck)
        }.toByteArray()
        encryptedCheck.fill(0)

        return SecureConnectPayload(
            bytes = connectPayload,
            clientConnectionId = clientConnectionId,
            checkValue = checkValue,
            expectedCheckReply = (checkValue + 1L) and 0xFFFFFFFFL
        )
    }

    /** Checks the secure server proof: Buffer(u32 length=4 + u32(check+1)). */
    fun validateSecureConnectAcknowledgement(
        responsePayload: ByteArray,
        expectedCheckReply: Long
    ) {
        require(expectedCheckReply in 0L..0xFFFFFFFFL) {
            "Expected secure CONNECT check reply is outside the unsigned 32-bit range."
        }
        val input = Reader(responsePayload)
        val responseLength = input.readUInt32LE()
        require(responseLength == 4L) {
            "Secure CONNECT acknowledgement has an unexpected payload length."
        }
        val actual = input.readUInt32LE()
        require(input.remaining() == 0 && actual == expectedCheckReply) {
            "Secure CONNECT proof did not match the LoginEx session key."
        }
    }

    private fun kerberosEncrypt(key: ByteArray, cleartext: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "Kerberos session key is empty." }
        val ciphertext = rc4(key, cleartext)
        val checksum = hmacMd5(key, ciphertext)
        return ciphertext + checksum
    }

    private fun kerberosDecrypt(key: ByteArray, encrypted: ByteArray): ByteArray {
        require(key.isNotEmpty() && encrypted.size >= HMAC_BYTES) {
            "Kerberos client ticket is truncated."
        }
        val ciphertext = encrypted.copyOfRange(0, encrypted.size - HMAC_BYTES)
        val suppliedChecksum = encrypted.copyOfRange(encrypted.size - HMAC_BYTES, encrypted.size)
        val expectedChecksum = hmacMd5(key, ciphertext)
        require(MessageDigest.isEqual(suppliedChecksum, expectedChecksum)) {
            "Kerberos client ticket checksum does not match the LoginEx source key."
        }
        return rc4(key, ciphertext)
    }

    /** RC4 is retained only because the legacy NEX Kerberos ticket format requires it. */
    private fun rc4(key: ByteArray, input: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "RC4 key cannot be empty." }
        val state = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val swap = state[i]
            state[i] = state[j]
            state[j] = swap
        }

        val output = ByteArray(input.size)
        var i = 0
        j = 0
        for (offset in input.indices) {
            i = (i + 1) and 0xFF
            j = (j + state[i]) and 0xFF
            val swap = state[i]
            state[i] = state[j]
            state[j] = swap
            val keyByte = state[(state[i] + state[j]) and 0xFF]
            output[offset] = (input[offset].toInt() xor keyByte).toByte()
        }
        state.fill(0)
        return output
    }

    private fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        return mac.doFinal(data)
    }

    private fun decodeHex(value: String): ByteArray {
        require(
            value.isNotBlank() && value.length % 2 == 0 &&
                value.all { it.digitToIntOrNull(16) != null }
        ) { "Nextendo LoginEx returned an invalid hexadecimal source key." }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun randomUInt32(): Long = random.nextInt().toLong() and 0xFFFFFFFFL

    private class Reader(private val data: ByteArray) {
        private var offset = 0

        fun remaining(): Int = data.size - offset

        fun readUInt8(): Int {
            require(remaining() >= 1) { "Nextendo LoginEx response is truncated." }
            return data[offset++].toInt() and 0xFF
        }

        fun readUInt16LE(): Int {
            require(remaining() >= 2) { "Nextendo LoginEx response is truncated." }
            val result = (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            return result
        }

        fun readUInt32LE(): Long {
            require(remaining() >= 4) { "Nextendo LoginEx response is truncated." }
            val result = (data[offset].toLong() and 0xFFL) or
                ((data[offset + 1].toLong() and 0xFFL) shl 8) or
                ((data[offset + 2].toLong() and 0xFFL) shl 16) or
                ((data[offset + 3].toLong() and 0xFFL) shl 24)
            offset += 4
            return result
        }

        fun readUInt64LE(): Long {
            require(remaining() >= 8) { "Nextendo LoginEx response is truncated." }
            var result = 0L
            repeat(8) { index ->
                result = result or ((data[offset + index].toLong() and 0xFFL) shl (index * 8))
            }
            offset += 8
            return result
        }

        fun readBytes(length: Int): ByteArray {
            require(length >= 0 && remaining() >= length) {
                "Nextendo LoginEx response is truncated."
            }
            return data.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun readBuffer(maxBytes: Int): ByteArray {
            val length = readUInt32LE()
            require(length <= maxBytes.toLong() && length <= remaining().toLong()) {
                "Nextendo LoginEx buffer length is invalid."
            }
            return readBytes(length.toInt())
        }

        fun readNexString(): String {
            val length = readUInt16LE()
            require(length > 0 && remaining() >= length) {
                "Nextendo LoginEx NEX String is invalid or truncated."
            }
            val value = readBytes(length)
            require(value.last() == 0.toByte()) {
                "Nextendo LoginEx NEX String is missing its null terminator."
            }
            return String(value, 0, value.size - 1, StandardCharsets.UTF_8)
        }
    }

    private fun ByteArrayOutputStream.writeBuffer(bytes: ByteArray) {
        writeUInt32LE(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeUInt32LE(value: Long) {
        for (shift in 0..3) write(((value ushr (shift * 8)) and 0xFFL).toInt())
    }

    private fun ByteArrayOutputStream.writeUInt64LE(value: Long) {
        for (shift in 0..7) write(((value ushr (shift * 8)) and 0xFFL).toInt())
    }
}
