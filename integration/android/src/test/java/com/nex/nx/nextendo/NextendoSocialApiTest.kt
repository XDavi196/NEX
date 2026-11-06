package com.nex.nx.nextendo

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class NextendoSocialApiTest {
    @Test
    fun doesNotInventOfflinePresenceWhenOAuthPayloadOmitsPresence() {
        val friends = NextendoSocialApi.parseFriends(
            JSONArray(
                """[{"pid":1800000002,"username":"Friend","friend_code":"SW-0000-0000-0002"}]"""
            )
        )

        assertEquals(1, friends.size)
        assertEquals(-1, friends.single().presenceStatus)
        assertEquals("Presença indisponível", friends.single().presenceLabel())
    }

    @Test
    fun mapsOnlyExplicitPresenceStatuses() {
        val friends = NextendoSocialApi.parseFriends(
            JSONArray(
                """[
                    {"pid":1,"username":"Offline","status":0},
                    {"pid":2,"username":"Online","status":1},
                    {"pid":3,"username":"Playing","presence":{"status":2,"app_id":"0100000000000000"}}
                ]"""
            )
        )

        assertEquals("Offline", friends.first { it.pid == 1L }.presenceLabel())
        assertEquals("Online", friends.first { it.pid == 2L }.presenceLabel())
        assertEquals("Jogando · 0100000000000000", friends.first { it.pid == 3L }.presenceLabel())
    }
}
