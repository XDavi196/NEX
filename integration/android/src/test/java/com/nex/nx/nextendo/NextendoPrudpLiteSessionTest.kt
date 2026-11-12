package com.nex.nx.nextendo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoPrudpLiteSessionTest {
    private fun config(connectPayload: ByteArray = byteArrayOf(), fragmentSize: Int = 1300) =
        NextendoPrudpLiteSession.Config(
            accessKey = "nex-test-access-key",
            clientSourceType = 10,
            clientSourcePort = 15,
            serverDestinationType = 10,
            serverDestinationPort = 1,
            minorVersion = 5,
            supportedFunctions = 0x1234,
            connectPayload = connectPayload,
            fragmentSize = fragmentSize
        )

    private fun connect(
        session: NextendoPrudpLiteSession,
        config: NextendoPrudpLiteSession.Config,
        nowMs: Long = System.nanoTime() / 1_000_000L
    ) {
        val syn = session.start(nowMs).outboundFrames.single()
        val synPacket = NextendoPrudpLiteCodec.decode(syn).packets.single()
        assertEquals(NextendoPrudpLiteCodec.TYPE_SYN, synPacket.type)
        assertEquals(NextendoPrudpLiteCodec.FLAG_NEED_ACK, synPacket.flags)

        val connectionSignature = ByteArray(16) { (it + 1).toByte() }
        val synAck = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_SYN,
            flags = NextendoPrudpLiteCodec.FLAG_ACK or NextendoPrudpLiteCodec.FLAG_HAS_SIZE,
            sourceType = config.serverDestinationType,
            destinationType = config.clientSourceType,
            sourcePort = config.serverDestinationPort,
            destinationPort = config.clientSourcePort,
            packetId = 0,
            minorVersion = 5,
            supportedFunctions = 0x0034,
            connectionSignature = connectionSignature
        )
        // Keep the synthetic handshake clock at the supplied instant so later calls using the real monotonic clock never move backwards.
        val connectStep = session.receive(NextendoPrudpLiteCodec.encode(synAck), nowMs)
        assertEquals(1, connectStep.outboundFrames.size)
        val connectPacket = NextendoPrudpLiteCodec.decode(connectStep.outboundFrames.single())
            .packets.single()
        assertEquals(NextendoPrudpLiteCodec.TYPE_CONNECT, connectPacket.type)
        assertEquals(NextendoPrudpLiteCodec.FLAG_NEED_ACK, connectPacket.flags)
        assertEquals(1, connectPacket.packetId)
        assertEquals(config.connectPayload.toList(), connectPacket.payload.toList())
        assertEquals(5, connectPacket.minorVersion)
        assertEquals(0x0034, connectPacket.supportedFunctions)
        assertArrayEquals(
            NextendoPrudpLiteAuthenticator.signConnect(config.accessKey, connectionSignature),
            connectPacket.signature
        )

        val connectAck = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_CONNECT,
            flags = NextendoPrudpLiteCodec.FLAG_ACK or NextendoPrudpLiteCodec.FLAG_HAS_SIZE,
            sourceType = config.serverDestinationType,
            destinationType = config.clientSourceType,
            sourcePort = config.serverDestinationPort,
            destinationPort = config.clientSourcePort,
            packetId = 1,
            payload = byteArrayOf(0x10, 0x20)
        )
        val connected = session.receive(NextendoPrudpLiteCodec.encode(connectAck), nowMs)
        assertEquals(NextendoPrudpLiteSession.State.CONNECTED, session.state())
        assertTrue(connected.events.single() is NextendoPrudpLiteSession.Event.Connected)
        assertArrayEquals(
            byteArrayOf(0x10, 0x20),
            (connected.events.single() as NextendoPrudpLiteSession.Event.Connected).responsePayload
        )
    }


    @Test
    fun retransmitsInitialSynAfterPublishedFiveSecondTimeout() {
        val config = config()
        val session = NextendoPrudpLiteSession(config)
        val first = session.start(nowMs = 1_000L).outboundFrames.single()

        assertTrue(session.tick(nowMs = 5_999L).outboundFrames.isEmpty())
        val retry = session.tick(nowMs = 6_000L).outboundFrames.single()
        assertArrayEquals(first, retry)
        assertEquals(NextendoPrudpLiteCodec.TYPE_SYN,
            NextendoPrudpLiteCodec.decode(retry).packets.single().type)
        session.close()
    }

    @Test
    fun retransmitsConnectUntilConnectAcknowledgementArrives() {
        val config = config()
        val session = NextendoPrudpLiteSession(config)
        session.start(nowMs = 1_000L)

        val synAck = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_SYN,
            flags = NextendoPrudpLiteCodec.FLAG_ACK or NextendoPrudpLiteCodec.FLAG_HAS_SIZE,
            sourceType = config.serverDestinationType,
            destinationType = config.clientSourceType,
            sourcePort = config.serverDestinationPort,
            destinationPort = config.clientSourcePort,
            packetId = 0,
            minorVersion = 5,
            supportedFunctions = 0,
            connectionSignature = ByteArray(16) { (it + 1).toByte() }
        )
        val connect = session.receive(
            NextendoPrudpLiteCodec.encode(synAck),
            nowMs = 1_100L
        ).outboundFrames.single()

        assertTrue(session.tick(nowMs = 6_099L).outboundFrames.isEmpty())
        assertArrayEquals(
            connect,
            session.tick(nowMs = 6_100L).outboundFrames.single()
        )

        val connectAck = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_CONNECT,
            flags = NextendoPrudpLiteCodec.FLAG_ACK or NextendoPrudpLiteCodec.FLAG_HAS_SIZE,
            sourceType = config.serverDestinationType,
            destinationType = config.clientSourceType,
            sourcePort = config.serverDestinationPort,
            destinationPort = config.clientSourcePort,
            packetId = 1
        )
        session.receive(NextendoPrudpLiteCodec.encode(connectAck), nowMs = 6_200L)
        val idle = NextendoPrudpLiteCodec.decode(
            session.tick(nowMs = 11_200L).outboundFrames.single()
        ).packets.single()
        assertEquals(NextendoPrudpLiteCodec.TYPE_PING, idle.type)
        session.close()
    }

    @Test
    fun retransmitsReliableDataUntilItsAckIsReceived() {
        val config = config()
        val session = NextendoPrudpLiteSession(config)
        connect(session, config, nowMs = 1_000L)

        val rmc = NextendoRmcCodec.encodeRequest(
            protocolId = NextendoSmm2DataStore.PROTOCOL_ID,
            methodId = NextendoSmm2DataStore.GET_USERS.toLong(),
            callId = 50L
        )
        val first = session.sendRmc(rmc, nowMs = 2_000L).outboundFrames.single()
        assertTrue(session.tick(nowMs = 6_999L).outboundFrames.isEmpty())
        assertArrayEquals(first, session.tick(nowMs = 7_000L).outboundFrames.single())

        val ack = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_DATA,
            flags = NextendoPrudpLiteCodec.FLAG_ACK,
            sourceType = config.serverDestinationType,
            destinationType = config.clientSourceType,
            sourcePort = config.serverDestinationPort,
            destinationPort = config.clientSourcePort,
            packetId = 1
        )
        session.receive(NextendoPrudpLiteCodec.encode(ack), nowMs = 7_100L)
        val keepalive = NextendoPrudpLiteCodec.decode(
            session.tick(nowMs = 12_100L).outboundFrames.single()
        ).packets.single()
        assertEquals(NextendoPrudpLiteCodec.TYPE_PING, keepalive.type)
        session.close()
    }

    @Test
    fun signsConnectWithTheRuntimeAccessKeyAndServerConnectionSignature() {
        val signature = ByteArray(16) { it.toByte() }
        val first = NextendoPrudpLiteAuthenticator.signConnect("key-one", signature)
        val repeated = NextendoPrudpLiteAuthenticator.signConnect("key-one", signature)
        val otherKey = NextendoPrudpLiteAuthenticator.signConnect("key-two", signature)
        val otherSignature = NextendoPrudpLiteAuthenticator.signConnect(
            "key-one",
            ByteArray(16) { (it + 1).toByte() }
        )

        assertEquals(16, first.size)
        assertArrayEquals(first, repeated)
        assertFalse(first.contentEquals(otherKey))
        assertFalse(first.contentEquals(otherSignature))
    }

    @Test
    fun matchesPublishedNextendoLiteSignatureVector() {
        val serverConnectionSignature = byteArrayOf(
            0xFA.toByte(), 0x2B, 0xE3.toByte(), 0x79, 0xF5.toByte(), 0x43, 0x07, 0x84.toByte(),
            0x2C, 0x3C, 0x72, 0x38, 0xDB.toByte(), 0x6C, 0x06, 0x17
        )
        val signature = NextendoPrudpLiteAuthenticator.signConnect(
            "09c1c475",
            serverConnectionSignature
        )
        val expectedHex = "a267f245d4247393600ae60c58dae243"
        val actualHex = signature.joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }
        assertEquals(expectedHex, actualHex)
    }

    @Test
    fun completesSynConnectHandshakeAndPreservesCallerSuppliedConnectPayload() {
        val config = config(byteArrayOf(1, 2, 3, 4))
        val session = NextendoPrudpLiteSession(config)
        connect(session, config)
        session.close()
    }

    @Test
    fun sendsRmcAsReliableDataAndTracksAcknowledgements() {
        val config = config()
        val session = NextendoPrudpLiteSession(config)
        connect(session, config)

        val rmc = NextendoRmcCodec.encodeRequest(
            protocolId = NextendoSmm2DataStore.PROTOCOL_ID,
            methodId = NextendoSmm2DataStore.GET_USERS.toLong(),
            callId = 42L,
            body = byteArrayOf(1, 2, 3)
        )
        val sent = session.sendRmc(rmc)
        assertEquals(1, sent.outboundFrames.size)
        val data = NextendoPrudpLiteCodec.decode(sent.outboundFrames.single()).packets.single()
        assertEquals(NextendoPrudpLiteCodec.TYPE_DATA, data.type)
        assertEquals(
            NextendoPrudpLiteCodec.FLAG_RELIABLE or NextendoPrudpLiteCodec.FLAG_NEED_ACK,
            data.flags
        )
        assertEquals(0, data.fragmentId)
        assertEquals(1, data.packetId)
        assertEquals(1, session.pendingReliableCount())

        val ack = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_DATA,
            flags = NextendoPrudpLiteCodec.FLAG_ACK,
            sourceType = config.serverDestinationType,
            destinationType = config.clientSourceType,
            sourcePort = config.serverDestinationPort,
            destinationPort = config.clientSourcePort,
            packetId = 1
        )
        session.receive(NextendoPrudpLiteCodec.encode(ack))
        assertEquals(0, session.pendingReliableCount())
        session.close()
    }

    @Test
    fun acknowledgesInboundRmcAndDoesNotDispatchARepeatedReliablePacketTwice() {
        val config = config()
        val session = NextendoPrudpLiteSession(config)
        connect(session, config)

        val response = NextendoRmcCodec.encodeSuccessResponse(
            protocolId = NextendoSmm2DataStore.PROTOCOL_ID,
            methodId = NextendoSmm2DataStore.GET_USERS.toLong(),
            callId = 42L,
            body = byteArrayOf(0x55)
        )
        val data = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_DATA,
            flags = NextendoPrudpLiteCodec.FLAG_RELIABLE or NextendoPrudpLiteCodec.FLAG_NEED_ACK,
            sourceType = config.serverDestinationType,
            destinationType = config.clientSourceType,
            sourcePort = config.serverDestinationPort,
            destinationPort = config.clientSourcePort,
            fragmentId = 0,
            packetId = 77,
            payload = response
        )
        val first = session.receive(NextendoPrudpLiteCodec.encode(data))
        assertEquals(1, first.outboundFrames.size)
        val ack = NextendoPrudpLiteCodec.decode(first.outboundFrames.single()).packets.single()
        assertEquals(NextendoPrudpLiteCodec.FLAG_ACK, ack.flags)
        assertEquals(77, ack.packetId)
        assertEquals(1, first.events.size)
        val decoded = (first.events.single() as NextendoPrudpLiteSession.Event.RmcMessage).message
        assertEquals(NextendoRmcCodec.Kind.SUCCESS_RESPONSE, decoded.kind)
        assertEquals(42L, decoded.callId)

        val repeated = session.receive(NextendoPrudpLiteCodec.encode(data))
        assertEquals(1, repeated.outboundFrames.size)
        assertTrue(repeated.events.isEmpty())
        session.close()
    }

    @Test
    fun fragmentsLargeRmcFramesAndReassemblesIncomingFragments() {
        val config = config(fragmentSize = 64)
        val session = NextendoPrudpLiteSession(config)
        connect(session, config)

        val rmc = NextendoRmcCodec.encodeRequest(
            protocolId = NextendoSmm2DataStore.PROTOCOL_ID,
            methodId = NextendoSmm2DataStore.REGISTER_USER.toLong(),
            callId = 5L,
            body = ByteArray(140) { it.toByte() }
        )
        val outgoing = session.sendRmc(rmc).outboundFrames
        assertEquals(3, outgoing.size)
        val packets = outgoing.map { NextendoPrudpLiteCodec.decode(it).packets.single() }
        assertEquals(listOf(1, 2, 0), packets.map { it.fragmentId })
        assertEquals(listOf(1, 2, 3), packets.map { it.packetId })

        val response = NextendoRmcCodec.encodeSuccessResponse(
            protocolId = NextendoSmm2DataStore.PROTOCOL_ID,
            methodId = NextendoSmm2DataStore.REGISTER_USER.toLong(),
            callId = 5L,
            body = ByteArray(140) { (it + 1).toByte() }
        )
        val chunks = response.toList().chunked(64).map { it.toByteArray() }
        var allAcks = 0
        var completeEvent: NextendoPrudpLiteSession.Event.RmcMessage? = null
        chunks.forEachIndexed { index, bytes ->
            val packet = NextendoPrudpLiteCodec.Packet(
                type = NextendoPrudpLiteCodec.TYPE_DATA,
                flags = NextendoPrudpLiteCodec.FLAG_RELIABLE or
                    NextendoPrudpLiteCodec.FLAG_NEED_ACK,
                sourceType = config.serverDestinationType,
                destinationType = config.clientSourceType,
                sourcePort = config.serverDestinationPort,
                destinationPort = config.clientSourcePort,
                fragmentId = if (index == chunks.lastIndex) 0 else index + 1,
                packetId = 11 + index,
                payload = bytes
            )
            val step = session.receive(NextendoPrudpLiteCodec.encode(packet))
            allAcks += step.outboundFrames.size
            completeEvent = step.events
                .filterIsInstance<NextendoPrudpLiteSession.Event.RmcMessage>()
                .singleOrNull() ?: completeEvent
        }
        assertEquals(chunks.size, allAcks)
        val decoded = checkNotNull(completeEvent).message
        assertEquals(5L, decoded.callId)
        assertArrayEquals(ByteArray(140) { (it + 1).toByte() }, decoded.body)
        session.close()
    }

    @Test
    fun refusesToSendRmcResponsesAsClientRequests() {
        val config = config()
        val session = NextendoPrudpLiteSession(config)
        connect(session, config)
        val response = NextendoRmcCodec.encodeSuccessResponse(
            protocolId = NextendoSmm2DataStore.PROTOCOL_ID,
            methodId = NextendoSmm2DataStore.GET_USERS.toLong(),
            callId = 8L
        )
        try {
            session.sendRmc(response)
            throw AssertionError("A client response was accepted as an outbound request.")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        session.close()
    }

    @Test
    fun rejectsServerPacketsWithUnexpectedVirtualPorts() {
        val config = config()
        val session = NextendoPrudpLiteSession(config)
        session.start()
        val synAck = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_SYN,
            flags = NextendoPrudpLiteCodec.FLAG_ACK or NextendoPrudpLiteCodec.FLAG_HAS_SIZE,
            sourceType = config.serverDestinationType,
            destinationType = config.clientSourceType,
            sourcePort = config.serverDestinationPort + 1,
            destinationPort = config.clientSourcePort,
            connectionSignature = ByteArray(16)
        )
        val result = session.receive(NextendoPrudpLiteCodec.encode(synAck))
        assertEquals("unexpected_station_address",
            (result.events.single() as NextendoPrudpLiteSession.Event.ProtocolError).reason)
        assertEquals(NextendoPrudpLiteSession.State.SYN_SENT, session.state())
        session.close()
    }


    @Test
    fun sendsPeriodicKeepalivePingsWhenTheSecureSessionIsIdle() {
        val config = config()
        val session = NextendoPrudpLiteSession(config)
        connect(session, config, nowMs = 1_000L)

        assertTrue(session.tick(nowMs = 5_999L).outboundFrames.isEmpty())
        val firstPing = NextendoPrudpLiteCodec.decode(
            session.tick(nowMs = 6_000L).outboundFrames.single()
        ).packets.single()
        assertEquals(NextendoPrudpLiteCodec.TYPE_PING, firstPing.type)
        assertEquals(NextendoPrudpLiteCodec.FLAG_NEED_ACK, firstPing.flags)
        assertEquals(0, firstPing.packetId)

        val secondPing = NextendoPrudpLiteCodec.decode(
            session.tick(nowMs = 11_000L).outboundFrames.single()
        ).packets.single()
        assertEquals(NextendoPrudpLiteCodec.TYPE_PING, secondPing.type)
        assertEquals(1, secondPing.packetId)
        session.close()
    }

    @Test
    fun respondsToPingAndStopsOnDisconnect() {
        val config = config()
        val session = NextendoPrudpLiteSession(config)
        connect(session, config)

        val ping = NextendoPrudpLiteCodec.Packet(
            type = NextendoPrudpLiteCodec.TYPE_PING,
            flags = NextendoPrudpLiteCodec.FLAG_NEED_ACK,
            sourceType = config.serverDestinationType,
            destinationType = config.clientSourceType,
            sourcePort = config.serverDestinationPort,
            destinationPort = config.clientSourcePort,
            packetId = 4
        )
        val pingResult = session.receive(NextendoPrudpLiteCodec.encode(ping))
        val pingAck = NextendoPrudpLiteCodec.decode(pingResult.outboundFrames.single())
            .packets.single()
        assertEquals(NextendoPrudpLiteCodec.TYPE_PING, pingAck.type)
        assertEquals(NextendoPrudpLiteCodec.FLAG_ACK, pingAck.flags)

        val disconnect = ping.copy(
            type = NextendoPrudpLiteCodec.TYPE_DISCONNECT,
            packetId = 5
        )
        val closed = session.receive(NextendoPrudpLiteCodec.encode(disconnect))
        assertTrue(closed.events.single() is NextendoPrudpLiteSession.Event.Disconnected)
        assertEquals(NextendoPrudpLiteSession.State.CLOSED, session.state())
    }
    @Test
    fun closeIsIdempotentAndClearsSensitiveSessionState() {
        val connectPayload = ByteArray(24) { (it + 1).toByte() }
        val config = config(connectPayload = connectPayload)
        val session = NextendoPrudpLiteSession(config)
        connect(session, config, nowMs = 1_000L)

        val request = NextendoRmcCodec.encodeRequest(
            protocolId = NextendoSmm2DataStore.PROTOCOL_ID,
            methodId = NextendoSmm2DataStore.GET_USERS.toLong(),
            callId = 90L
        )
        session.sendRmc(request, nowMs = 2_000L)
        assertTrue(session.pendingReliableCount() > 0)

        session.close()
        assertEquals(NextendoPrudpLiteSession.State.CLOSED, session.state())
        assertEquals(0, session.pendingReliableCount())

        val keyField = NextendoPrudpLiteSession::class.java.getDeclaredField("runtimeAccessKey")
        keyField.isAccessible = true
        assertNull(keyField.get(session))

        val payloadField = NextendoPrudpLiteSession::class.java.getDeclaredField("connectPayload")
        payloadField.isAccessible = true
        assertArrayEquals(ByteArray(connectPayload.size), payloadField.get(session) as ByteArray)

        session.close()
        assertEquals(NextendoPrudpLiteSession.State.CLOSED, session.state())
    }
}
