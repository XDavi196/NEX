package com.nex.nx.nextendo

import java.io.ByteArrayOutputStream

/**
 * RMC message-envelope codec using the public NintendoClients NEX reference.
 * Method payload serialization and service authentication remain title-specific.
 */
internal object NextendoRmcCodec {
    enum class Kind {
        REQUEST,
        SUCCESS_RESPONSE,
        ERROR_RESPONSE
    }

    data class Message(
        val kind: Kind,
        val protocolId: Int,
        val callId: Long,
        val methodId: Long?,
        val resultCode: Long?,
        val body: ByteArray
    )

    fun encodeRequest(
        protocolId: Int,
        methodId: Long,
        callId: Long,
        body: ByteArray = byteArrayOf()
    ): ByteArray {
        validateUInt32(methodId)
        validateUInt32(callId)
        val content = ByteArrayOutputStream()
        writeProtocol(content, protocolId, isRequest = true)
        writeUInt32LE(content, callId)
        writeUInt32LE(content, methodId)
        content.write(body)
        return frame(content.toByteArray())
    }

    fun encodeSuccessResponse(
        protocolId: Int,
        methodId: Long,
        callId: Long,
        body: ByteArray = byteArrayOf()
    ): ByteArray {
        require(methodId in 0L..0x7FFFL) { "RMC response method id is out of range." }
        validateUInt32(callId)
        val content = ByteArrayOutputStream()
        writeProtocol(content, protocolId, isRequest = false)
        content.write(1)
        writeUInt32LE(content, callId)
        writeUInt32LE(content, methodId or 0x8000L)
        content.write(body)
        return frame(content.toByteArray())
    }

    fun encodeErrorResponse(
        protocolId: Int,
        resultCode: Long,
        callId: Long
    ): ByteArray {
        require(resultCode in 0x80000000L..0xFFFFFFFFL) {
            "RMC error result must carry the error bit."
        }
        validateUInt32(callId)
        val content = ByteArrayOutputStream()
        writeProtocol(content, protocolId, isRequest = false)
        content.write(0)
        writeUInt32LE(content, resultCode)
        writeUInt32LE(content, callId)
        return frame(content.toByteArray())
    }

    fun decode(frame: ByteArray): Message {
        require(frame.size >= 5) { "RMC frame is truncated." }
        val declaredLength = readUInt32LE(frame, 0)
        require(declaredLength == frame.size - 4L) { "RMC frame length does not match its payload." }

        val reader = Reader(frame, 4)
        val protocolAndDirection = reader.readUInt8()
        val isRequest = protocolAndDirection and 0x80 != 0
        var protocolId = protocolAndDirection and 0x7F
        if (protocolId == 0x7F) {
            protocolId = reader.readUInt16LE()
        }

        if (isRequest) {
            val callId = reader.readUInt32LE()
            val methodId = reader.readUInt32LE()
            return Message(
                kind = Kind.REQUEST,
                protocolId = protocolId,
                callId = callId,
                methodId = methodId,
                resultCode = null,
                body = reader.readRemaining()
            )
        }

        val success = reader.readUInt8()
        require(success == 0 || success == 1) { "Invalid RMC response result flag." }
        if (success == 1) {
            val callId = reader.readUInt32LE()
            val methodWithResponseFlag = reader.readUInt32LE()
            require(methodWithResponseFlag and 0x8000L != 0L) {
                "RMC success response is missing the response method flag."
            }
            return Message(
                kind = Kind.SUCCESS_RESPONSE,
                protocolId = protocolId,
                callId = callId,
                methodId = methodWithResponseFlag and 0xFFFF7FFFL,
                resultCode = null,
                body = reader.readRemaining()
            )
        }

        val resultCode = reader.readUInt32LE()
        val callId = reader.readUInt32LE()
        require(reader.remaining() == 0) { "RMC error response contains unexpected trailing data." }
        return Message(
            kind = Kind.ERROR_RESPONSE,
            protocolId = protocolId,
            callId = callId,
            methodId = null,
            resultCode = resultCode,
            body = byteArrayOf()
        )
    }

    private fun frame(content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(content.size + 4)
        writeUInt32LE(output, content.size.toLong())
        output.write(content)
        return output.toByteArray()
    }

    private fun writeProtocol(output: ByteArrayOutputStream, protocolId: Int, isRequest: Boolean) {
        require(protocolId in 0..0xFFFF && protocolId != 0x7F) {
            "RMC protocol id is out of range or reserved."
        }
        val extended = protocolId >= 0x80
        val marker = if (extended) 0x7F else protocolId
        output.write(marker or (if (isRequest) 0x80 else 0))
        if (extended) {
            writeUInt16LE(output, protocolId)
        }
    }

    private fun validateUInt32(value: Long) {
        require(value in 0L..0xFFFFFFFFL) { "RMC value is outside the unsigned 32-bit range." }
    }

    private fun writeUInt16LE(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }

    private fun writeUInt32LE(output: ByteArrayOutputStream, value: Long) {
        for (shift in 0..3) {
            output.write(((value ushr (shift * 8)) and 0xFFL).toInt())
        }
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    private class Reader(
        private val bytes: ByteArray,
        startOffset: Int
    ) {
        private var offset = startOffset

        fun readUInt8(): Int {
            require(offset < bytes.size) { "RMC frame is truncated." }
            return bytes[offset++].toInt() and 0xFF
        }

        fun readUInt16LE(): Int {
            val low = readUInt8()
            val high = readUInt8()
            return low or (high shl 8)
        }

        fun readUInt32LE(): Long {
            val low = readUInt8().toLong()
            val b1 = readUInt8().toLong()
            val b2 = readUInt8().toLong()
            val high = readUInt8().toLong()
            return low or (b1 shl 8) or (b2 shl 16) or (high shl 24)
        }

        fun readRemaining(): ByteArray = bytes.copyOfRange(offset, bytes.size)

        fun remaining(): Int = bytes.size - offset
    }
}

/** Protocol and method identifiers from the public DataStore_SMM2 schema. */
internal object NextendoSmm2DataStore {
    const val PROTOCOL_ID = 115
    const val REGISTER_USER = 47
    const val GET_USERS = 48
    const val SYNC_USER_PROFILE = 49
    const val SEARCH_USERS_USER_POINT = 50
    const val SEARCH_USERS_PLAYED_COURSE = 53
    const val SEARCH_USERS_CLEARED_COURSE = 54
    const val SEARCH_USERS_POSITIVE_RATED_COURSE = 55
    const val UPDATE_LAST_LOGIN_TIME = 59
    const val GET_USERNAME_NG_TYPE = 65
    const val GET_COURSES = 70
    const val SEARCH_COURSES_POINT_RANKING = 71
    const val SEARCH_COURSES_LATEST = 73
    const val SEARCH_COURSES_POSTED_BY = 74
    const val SEARCH_COURSES_POSITIVE_RATED_BY = 75
    const val SEARCH_COURSES_PLAYED_BY = 76
    const val SEARCH_COURSES_ENDLESS_MODE = 79
    const val SEARCH_COURSES_FIRST_CLEAR = 80
    const val SEARCH_COURSES_BEST_TIME = 81
    const val GET_COURSES_EVENT = 85
    const val SEARCH_COURSES_EVENT = 86
    const val SEARCH_COMMENTS_IN_ORDER = 94
    const val SEARCH_COMMENTS = 95
    const val GET_DEATH_POSITIONS = 103
    const val GET_USER_OR_COURSE = 131
    const val GET_REQ_GET_INFO_HEADERS_INFO = 134
    const val GET_EVENT_COURSE_STAMP = 153
    const val GET_EVENT_COURSE_STATUS = 154
    const val GET_EVENT_COURSE_HISTOGRAM = 156
    const val GET_EVENT_COURSE_GHOST = 157
    const val GET_WORLD_MAP = 160
    const val SEARCH_WORLD_MAP_PICK_UP = 162
}
