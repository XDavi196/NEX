package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Test

class NextendoSocialApiTest {
    @Test
    fun doesNotInventOfflinePresenceWhenOAuthPayloadOmitsPresence() {
        val result = NextendoFriendListParser.parse(
            """{"scope":"identity friends","friends":[{"pid":1800000002,"username":"Friend","friend_code":"SW-0000-0000-0002"}]}"""
        )

        assertEquals(1, result.friends.size)
        assertEquals(-1, result.friends.single().presenceStatus)
        assertEquals("Presença indisponível", result.friends.single().presenceLabel())
        assertEquals(0, result.requests.size)
    }

    @Test
    fun mapsOnlyExplicitPresenceStatuses() {
        val result = NextendoFriendListParser.parse(
            """{"friends":[
                {"pid":1,"username":"Offline","status":0},
                {"pid":2,"username":"Online","presence":{"status":1}},
                {"pid":3,"username":"Playing","presence":{"status":2,"app_id":"0100000000000000","app_detail":"Match"}}
            ]}"""
        )

        assertEquals("Offline", result.friends.first { it.pid == 1L }.presenceLabel())
        assertEquals("Online", result.friends.first { it.pid == 2L }.presenceLabel())
        assertEquals("Jogando · Match", result.friends.first { it.pid == 3L }.presenceLabel())
    }

    @Test
    fun ignoresMalformedFriendRecordsAndUnknownPresenceValues() {
        val result = NextendoFriendListParser.parse(
            """{"friends":[null,{"pid":"not-a-number","username":"Bad"},{"pid":4,"username":"No status","status":"online"}]}"""
        )

        assertEquals(2, result.friends.size)
        assertEquals(0L, result.friends.first { it.username == "Bad" }.pid)
        assertEquals(-1, result.friends.first { it.username == "No status" }.presenceStatus)
    }
}
