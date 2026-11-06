package com.nex.nx.nextendo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NextendoGameFriendParserTest {
    @Test
    fun parsesGameFriendIdentityAndBinaryPresence() {
        val friends = NextendoGameFriendParser.parse(
            """{"friends":[{"pid":1800000002,"nsa":"00000000000000A1","name":"Friend","presence":{"status":2,"app_id":"0100F8F0000A2000","app_detail":"Private Battle","app_field":"AAH/"}}]}"""
        )

        assertEquals(1, friends.size)
        val friend = friends.single()
        assertEquals(1800000002L, friend.pid)
        assertEquals(161L, friend.networkServiceAccountId)
        assertEquals("Friend", friend.username)
        assertEquals(2, friend.presenceStatus)
        assertEquals("0100F8F0000A2000", friend.appId)
        assertEquals("Private Battle", friend.appDetail)
        assertArrayEquals(byteArrayOf(0, 1, 0xFF.toByte()), friend.appField)
    }

    @Test
    fun ignoresRecordsWithoutValidPresenceOrBinaryField() {
        val friends = NextendoGameFriendParser.parse(
            """{"friends":[
                {"pid":1,"name":"Missing presence"},
                {"pid":2,"name":"Unknown status","presence":{"status":9}},
                {"pid":3,"name":"Malformed base64","presence":{"status":2,"app_field":"***="}},
                {"pid":4,"name":"Wrong field type","presence":{"status":1,"app_field":true}},
                {"pid":5,"name":"Valid empty field","presence":{"status":1,"app_field":""}}
            ]}"""
        )

        assertEquals(listOf(5L), friends.map { it.pid })
        assertTrue(friends.single().appField.isEmpty())
    }

    @Test
    fun rejectsAppFieldLargerThanGuestPresenceStructure() {
        val oversized = Base64.getEncoder().encodeToString(ByteArray(0xC1))
        val friends = NextendoGameFriendParser.parse(
            """{"friends":[{"pid":7,"name":"Oversized","presence":{"status":2,"app_field":"$oversized"}}]}"""
        )

        assertTrue(friends.isEmpty())
    }

    @Test
    fun keepsFirstRecordWhenServerPayloadContainsDuplicatePid() {
        val friends = NextendoGameFriendParser.parse(
            """{"friends":[
                {"pid":8,"name":"First","presence":{"status":1,"app_field":""}},
                {"pid":8,"name":"Duplicate","presence":{"status":2,"app_field":""}}
            ]}"""
        )

        assertEquals(1, friends.size)
        assertEquals("First", friends.single().username)
        assertEquals(1, friends.single().presenceStatus)
    }
}
