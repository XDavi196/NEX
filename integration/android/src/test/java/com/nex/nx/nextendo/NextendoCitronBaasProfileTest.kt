package com.nex.nx.nextendo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoCitronBaasProfileTest {
    @Test
    fun matchesCitronIssuerAudienceDeviceClaimsAndNx2ExpiryBoundary() {
        val now = System.currentTimeMillis() / 1000L
        val session = NextendoSessionStore.Session(
            accessToken = "oauth-access",
            refreshToken = "oauth-refresh",
            accessTokenExpiresAtEpochSeconds = now + 3600L,
            grantedScopes = setOf("game.matchmaking"),
            nexGameToken = "nx2.server-issued-token",
            nexGameTokenExpiresAtEpochSeconds = now + 5400L,
            pid = 123456L,
            username = "test-player",
            friendCode = "SW-0000-0000-0000"
        )

        val claims = NextendoCitronBaasProfile.claimsFor(session)
        val nintendo = claims.getValue("nintendo") as JsonObject

        assertEquals(NextendoCitronBaasProfile.ISSUER, claims.getValue("iss").jsonPrimitive.content)
        assertEquals(NextendoCitronBaasProfile.AUDIENCE, claims.getValue("aud").jsonPrimitive.content)
        assertEquals("id_token", claims.getValue("typ").jsonPrimitive.content)
        assertEquals("true", claims.getValue("hm").jsonPrimitive.content)
        assertEquals(NextendoCitronBaasProfile.DEVICE_SERIAL, claims.getValue("sn").jsonPrimitive.content)
        assertEquals("NX Prod 1", nintendo.getValue("dt").jsonPrimitive.content)
        assertEquals("HAC", nintendo.getValue("pc").jsonPrimitive.content)
        assertEquals("false", nintendo.getValue("ist").jsonPrimitive.content)
        assertEquals(
            claims.getValue("di").jsonPrimitive.content,
            nintendo.getValue("di").jsonPrimitive.content
        )
        assertTrue(claims.getValue("exp").jsonPrimitive.content.toLong() <=
            session.nexGameTokenExpiresAtEpochSeconds)
        assertTrue(claims.getValue("exp").jsonPrimitive.content.toLong() >
            claims.getValue("iat").jsonPrimitive.content.toLong())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExpiredNx2Credential() {
        val now = System.currentTimeMillis() / 1000L
        val session = NextendoSessionStore.Session(
            accessToken = "oauth-access",
            refreshToken = "",
            accessTokenExpiresAtEpochSeconds = now + 3600L,
            nexGameToken = "nx2.expired",
            nexGameTokenExpiresAtEpochSeconds = now - 1L,
            pid = 123456L
        )
        NextendoCitronBaasProfile.claimsFor(session)
    }
}
