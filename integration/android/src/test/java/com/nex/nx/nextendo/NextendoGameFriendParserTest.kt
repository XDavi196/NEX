package com.nex.nx.nextendo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoGameFriendParserTest {
    @Test
    fun parsesGameTokenFriendPresenceAndAppField() {
        val friends = NextendoGameFriendParser.parse(
            """{"friends":[{"pid":1800000002,"nsa":"0000000000001234","name":"Player",
                "presence":{"status":2,"app_id":"0100000000000000","app_detail":"Match",
                "app_field":"AQIDBA=="}}]}"""
        )

        assertEquals(1, friends.size)
        val friend = friends.single()
        assertEquals(1800000002L, friend.pid)
        assertEquals(0x1234L, friend.networkServiceAccountId)
        assertEquals("Player", friend.username)
        assertEquals(2, friend.presenceStatus)
        assertEquals("0100000000000000", friend.appId)
        assertEquals("Match", friend.appDetail)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), friend.appField)
    }

    @Test
    fun rejectsMalformedPresenceAndOversizedAppField() {
        val malformed = NextendoGameFriendParser.parse(
            """{"friends":[
                {"pid":1,"name":"No presence"},
                {"pid":2,"name":"Bad status","presence":{"status":9}},
                {"pid":3,"name":"Bad app field","presence":{"status":1,
                    "app_field":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}
            ]}"""
        )
        assertTrue(malformed.isEmpty())
    }

    @Test
    fun missingAppFieldIsAnEmptyByteArrayNotFabricatedMetadata() {
        val friends = NextendoGameFriendParser.parse(
            """{"friends":[{"pid":4,"name":"Offline","presence":{"status":0}}]}"""
        )
        assertEquals(1, friends.size)
        assertArrayEquals(byteArrayOf(), friends.single().appField)
    }
}
