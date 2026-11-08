package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextendoPresenceAuthorizationTest {
    @Test
    fun acceptsOnlyUnexpiredOAuthTokenWithPresenceScope() {
        assertEquals(
            "oauth-access-token",
            NextendoPresenceAuthorization.usableAccessToken(
                accessToken = "oauth-access-token",
                grantedScopes = setOf("identity", "presence"),
                expiresAtEpochSeconds = 2_000L,
                nowEpochSeconds = 1_000L
            )
        )
    }

    @Test
    fun rejectsGameTokenAndMissingPresenceScope() {
        assertNull(
            NextendoPresenceAuthorization.usableAccessToken(
                accessToken = "nx2.game-token",
                grantedScopes = setOf("identity", "presence"),
                expiresAtEpochSeconds = 2_000L,
                nowEpochSeconds = 1_000L
            )
        )
        assertNull(
            NextendoPresenceAuthorization.usableAccessToken(
                accessToken = "oauth-access-token",
                grantedScopes = setOf("identity", "friends"),
                expiresAtEpochSeconds = 2_000L,
                nowEpochSeconds = 1_000L
            )
        )
    }

    @Test
    fun rejectsBlankAndExpiredTokens() {
        assertNull(
            NextendoPresenceAuthorization.usableAccessToken(
                accessToken = "",
                grantedScopes = setOf("presence"),
                expiresAtEpochSeconds = 2_000L,
                nowEpochSeconds = 1_000L
            )
        )
        assertNull(
            NextendoPresenceAuthorization.usableAccessToken(
                accessToken = "oauth-access-token",
                grantedScopes = setOf("presence"),
                expiresAtEpochSeconds = 1_000L,
                nowEpochSeconds = 1_000L
            )
        )
    }
}
