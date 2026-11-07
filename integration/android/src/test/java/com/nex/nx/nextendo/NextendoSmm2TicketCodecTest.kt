package com.nex.nx.nextendo

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoSmm2TicketCodecTest {
    @Test
    fun parsesLoginExTicketAndBuildsSecureConnectProof() {
        val sourceKey = "nextendo-srckey1".toByteArray(Charsets.US_ASCII)
        val originalSessionKey = ByteArray(32) { it.toByte() }
        val encryptedServerTicket = byteArrayOf(1, 2, 3, 4, 5, 6)
        val response = buildLoginExResponse(
            sourceKey = sourceKey,
            sessionKey = originalSessionKey,
            playerPid = 1800000001L,
            secureTargetPid = 2L,
            encryptedServerTicket = encryptedServerTicket
        )
        val message = NextendoRmcCodec.Message(
            kind = NextendoRmcCodec.Kind.SUCCESS_RESPONSE,
            protocolId = NextendoSmm2LoginCodec.PROTOCOL_ID,
            callId = 77L,
            methodId = NextendoSmm2LoginCodec.METHOD_LOGIN_EX,
            resultCode = null,
            body = response
        )

        val login = NextendoSmm2TicketCodec.decodeLoginExResponse(message, 77L)
        assertEquals(1800000001L, login.playerPid)
        assertEquals(2L, login.secureTargetPid)
        assertEquals("prudps:/address=smm2.example.test;port=60007", login.secureStationUrl)
        assertEquals("Nextendo", login.serverName)
        assertEquals(encryptedServerTicket.toList(), login.encryptedServerTicket.toList())

        val connect = NextendoSmm2TicketCodec.createSecureConnectPayload(
            login = login,
            clientConnectionId = 9L,
            checkValue = 41L
        )
        assertEquals(9L, connect.clientConnectionId)
        assertEquals(41L, connect.checkValue)
        assertEquals(42L, connect.expectedCheckReply)
        assertTrue(login.sessionKey.all { it == 0.toByte() })

        val payload = Reader(connect.bytes)
        val serverTicket = payload.readBuffer()
        val encryptedCheck = payload.readBuffer()
        assertEquals(encryptedServerTicket.toList(), serverTicket.toList())
        val checkData = kerberosDecrypt(originalSessionKey, encryptedCheck)
        val check = Reader(checkData)
        assertEquals(1800000001L, check.readUInt64LE())
        assertEquals(9L, check.readUInt32LE())
        assertEquals(41L, check.readUInt32LE())
        assertEquals(0, check.remaining())

        NextendoSmm2TicketCodec.validateSecureConnectAcknowledgement(
            byteArrayOf(4, 0, 0, 0, 42, 0, 0, 0),
            42L
        )
    }

    @Test
    fun rejectsBadLoginResponseAndSecureProof() {
        val wrong = NextendoRmcCodec.Message(
            kind = NextendoRmcCodec.Kind.ERROR_RESPONSE,
            protocolId = NextendoSmm2LoginCodec.PROTOCOL_ID,
            callId = 1L,
            methodId = null,
            resultCode = 0x80000001L,
            body = byteArrayOf()
        )
        try {
            NextendoSmm2TicketCodec.decodeLoginExResponse(wrong, 1L)
            throw AssertionError("Expected LoginEx error to be rejected.")
        } catch (_: IllegalStateException) {
            // Expected.
        }

        try {
            NextendoSmm2TicketCodec.validateSecureConnectAcknowledgement(
                byteArrayOf(4, 0, 0, 0, 41, 0, 0, 0),
                42L
            )
            throw AssertionError("Expected a mismatched secure check to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun buildLoginExResponse(
        sourceKey: ByteArray,
        sessionKey: ByteArray,
        playerPid: Long,
        secureTargetPid: Long,
        encryptedServerTicket: ByteArray
    ): ByteArray {
        val clientTicket = ByteArrayOutputStream().apply {
            write(sessionKey)
            writeUInt64LE(secureTargetPid)
            writeBuffer(encryptedServerTicket)
        }.toByteArray()
        val encryptedClientTicket = kerberosEncrypt(sourceKey, clientTicket)

        val connectionData = ByteArrayOutputStream().apply {
            writeNexString("prudps:/address=smm2.example.test;port=60007")
            writeUInt32LE(0)
            writeNexString("prudp:/")
            writeUInt64LE(123456789L)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            writeUInt32LE(0x00010001L) // ResultCoreUnknown (success).
            writeUInt64LE(playerPid)
            writeBuffer(encryptedClientTicket)
            write(1) // RVConnectionData structure version.
            writeUInt32LE(connectionData.size.toLong())
            write(connectionData)
            writeNexString("Nextendo")
            writeNexString(sourceKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        }.toByteArray()
    }

    private fun kerberosEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        val encrypted = rc4(key, data)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        return encrypted + mac.doFinal(encrypted)
    }

    private fun kerberosDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val encrypted = data.copyOfRange(0, data.size - 16)
        val suppliedMac = data.copyOfRange(data.size - 16, data.size)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        assertTrue(suppliedMac.contentEquals(mac.doFinal(encrypted)))
        return rc4(key, encrypted)
    }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val state = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = state[i]
            state[i] = state[j]
            state[j] = tmp
        }
        val output = ByteArray(data.size)
        var i = 0
        j = 0
        for (position in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + state[i]) and 0xFF
            val tmp = state[i]
            state[i] = state[j]
            state[j] = tmp
            output[position] = (data[position].toInt() xor state[(state[i] + state[j]) and 0xFF]).toByte()
        }
        return output
    }

    private class Reader(private val data: ByteArray) {
        private var offset = 0
        fun remaining(): Int = data.size - offset
        fun readBuffer(): ByteArray {
            val length = readUInt32LE().toInt()
            require(length >= 0 && remaining() >= length)
            return data.copyOfRange(offset, offset + length).also { offset += length }
        }
        fun readUInt32LE(): Long {
            require(remaining() >= 4)
            val result = (data[offset].toLong() and 0xFFL) or
                ((data[offset + 1].toLong() and 0xFFL) shl 8) or
                ((data[offset + 2].toLong() and 0xFFL) shl 16) or
                ((data[offset + 3].toLong() and 0xFFL) shl 24)
            offset += 4
            return result
        }
        fun readUInt64LE(): Long {
            require(remaining() >= 8)
            var result = 0L
            repeat(8) { result = result or ((data[offset + it].toLong() and 0xFFL) shl (it * 8)) }
            offset += 8
            return result
        }
    }

    private fun ByteArrayOutputStream.writeNexString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeUInt16LE(bytes.size + 1)
        write(bytes)
        write(0)
    }
    private fun ByteArrayOutputStream.writeBuffer(value: ByteArray) {
        writeUInt32LE(value.size.toLong())
        write(value)
    }
    private fun ByteArrayOutputStream.writeUInt16LE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }
    private fun ByteArrayOutputStream.writeUInt32LE(value: Long) {
        repeat(4) { shift -> write(((value ushr (shift * 8)) and 0xFFL).toInt()) }
    }
    private fun ByteArrayOutputStream.writeUInt64LE(value: Long) {
        repeat(8) { shift -> write(((value ushr (shift * 8)) and 0xFFL).toInt()) }
    }
}
