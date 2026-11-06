package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NextendoGameTokenParserTest {
    private val now = 1_800_000_000L

    @Test fun parsesDocumentedResponse() {
        val result = NextendoGameTokenParser.parse(
            """{"nex_token":"nx2.opaque-server-token","pid":1800000001,"username":"NEXPlayer","friend_code":"SW-0000-0000-0001","expires_in":86400}""", now)
        assertEquals("nx2.opaque-server-token", result.token)
        assertEquals(1_800_086_400L, result.expiresAtEpochSeconds)
        assertEquals(1_800_000_001L, result.pid)
        assertEquals("NEXPlayer", result.username)
        assertEquals("SW-0000-0000-0001", result.friendCode)
    }

    @Test fun rejectsUndocumentedTokenAliases() = invalid(
        """{"token":"nx2.opaque","pid":1,"username":"Player","expires_in":60}""", now)

    @Test fun rejectsWrongTokenPrefix() = invalid(
        """{"nex_token":"oauth.access.token","pid":1,"username":"Player","expires_in":60}""", now)

    @Test fun rejectsMissingPidAndExpiry() {
        invalid("""{"nex_token":"nx2.opaque","username":"Player","expires_in":60}""", now)
        invalid("""{"nex_token":"nx2.opaque","pid":1,"username":"Player"}""", now)
    }

    @Test fun rejectsInvalidPidAndLifetime() {
        invalid("""{"nex_token":"nx2.opaque","pid":0,"username":"Player","expires_in":60}""", now)
        invalid("""{"nex_token":"nx2.opaque","pid":1,"username":"Player","expires_in":0}""", now)
    }

    @Test fun rejectsExpiryOverflow() = invalid(
        """{"nex_token":"nx2.opaque","pid":1,"username":"Player","expires_in":60}""", Long.MAX_VALUE - 10L)

    private fun invalid(json: String, time: Long) {
        try {
            NextendoGameTokenParser.parse(json, time)
            fail("Expected invalid game-token response to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
