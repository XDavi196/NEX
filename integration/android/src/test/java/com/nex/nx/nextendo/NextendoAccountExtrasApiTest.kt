package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoAccountExtrasApiTest {
    @Test
    fun parsesActiveLobbyAndDeduplicatesPlayers() {
        val lobby = NextendoAccountExtrasApi.parseMyLobby(
            """{
                "in_lobby":true,
                "title_id":"01009B90006DC000",
                "lobby":{
                    "type":"matchmake",
                    "state":"Matched",
                    "state_code":"matched",
                    "id":42,
                    "count":2,
                    "max":4
                },
                "players":[
                    {"pid":1001,"name":"Host","known":true,"friend_code":"SW-1000-2000-3000","host":true,"is_me":false,"title_id":"01009B90006DC000"},
                    {"pid":1002,"name":"Me","known":true,"host":false,"is_me":true},
                    {"pid":1001,"name":"Duplicate","known":true}
                ]
            }"""
        )

        assertTrue(lobby.inLobby)
        assertEquals("01009B90006DC000", lobby.titleId)
        assertEquals("matchmake", lobby.type)
        assertEquals("Matched", lobby.state)
        assertEquals("matched", lobby.stateCode)
        assertEquals(42L, lobby.id)
        assertEquals(2, lobby.count)
        assertEquals(4, lobby.max)
        assertEquals(2, lobby.players.size)
        assertTrue(lobby.players.first().host)
        assertFalse(lobby.players.last().host)
        assertTrue(lobby.players.last().isMe)
    }

    @Test
    fun noLobbyResponseReturnsEmptyLobbyWithoutInventingRoom() {
        val lobby = NextendoAccountExtrasApi.parseMyLobby("""{"in_lobby":false}""")
        assertFalse(lobby.inLobby)
        assertEquals("", lobby.titleId)
        assertTrue(lobby.players.isEmpty())
        assertEquals(0, lobby.count)
    }

    @Test
    fun parsesRecentPlayersAndIgnoresInvalidPid() {
        val recent = NextendoAccountExtrasApi.parseRecentPlayers(
            """{"players":[
                {"pid":9,"name":"Seen","known":true,"friend_code":"SW-1111-2222-3333","seen_at":"2026-11-08T20:00:00Z"},
                {"pid":0,"name":"Invalid"},
                {"pid":"bad","name":"Malformed"},
                {"pid":9,"name":"Duplicate"}
            ]}"""
        )
        assertEquals(1, recent.size)
        assertEquals(9L, recent.single().pid)
        assertEquals("Seen", recent.single().name)
        assertEquals("SW-1111-2222-3333", recent.single().friendCode)
        assertEquals("2026-11-08T20:00:00Z", recent.single().seenAt)
    }

    @Test
    fun parsesProfileAndOptionalFields() {
        val profile = NextendoAccountExtrasApi.parseProfile(
            """{"username":"Davi","profile":{"image":"aGVsbG8=","name":"NEX Player","mii":"bWlp","avatar":"avatar_01","color":"#AABBCC"}}"""
        )
        assertEquals("Davi", profile.username)
        assertEquals("aGVsbG8=", profile.imageBase64)
        assertEquals("NEX Player", profile.consoleNickname)
        assertEquals("bWlp", profile.miiBase64)
        assertEquals("avatar_01", profile.avatarId)
        assertEquals("#AABBCC", profile.colorHex)
    }

    @Test
    fun parsesHistorySortsNewestAndRejectsInvalidEntries() {
        val history = NextendoAccountExtrasApi.parseHistory(
            """{"history":[
                {"title_id":"01009b90006dc000","name":"SMM2","seconds":120,"last_played":"2026-11-07T20:00:00Z"},
                {"title_id":"not-a-title","name":"Bad id","seconds":999,"last_played":"2026-11-08T21:00:00Z"},
                {"title_id":"0100000000010000","name":"Odyssey","seconds":-5,"last_played":"2026-11-08T20:00:00Z"},
                {"title_id":"0100000000010000","name":"Odyssey","seconds":60,"last_played":"2026-11-08T20:00:00Z"}
            ]}"""
        )
        assertEquals(2, history.size)
        assertEquals("0100000000010000", history[0].titleId)
        assertEquals(60L, history[0].seconds)
        assertEquals("01009B90006DC000", history[1].titleId)
    }

    @Test
    fun parsesPublicGalleryIdsAndRejectsUnsafeOrDuplicateEntries() {
        val ids = NextendoAccountExtrasApi.parseGalleryAvatarIds(
            """{"avatars":["avatar_01","switch-02","../secrets","with space","avatar_01","123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890","not-a-number"]}"""
        )
        assertEquals(listOf("avatar_01", "switch-02"), ids)
    }

    @Test
    fun bcatHashUsesLowercaseSha256Hex() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            NextendoAccountExtrasApi.hashBcatSeedHex("hello".toByteArray())
        )
    }
}
