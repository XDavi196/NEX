package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Test

class NextendoOAuthScopesTest {
    @Test
    fun requestsExactlyTheScopesEnabledInTheDeveloperPortal() {
        assertEquals(
            listOf(
                "identity",
                "friends",
                "presence",
                "profil",
                "game.matchmaking",
                "emulator.status"
            ),
            NextendoAuthClient.SCOPES.split(" ")
        )
    }
}
