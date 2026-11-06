package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NextendoOAuthTokenParserTest {
    private val now = 1_800_000_000L

    @Test
    fun acceptsPublishedThirdPartyResponseWithoutRefreshToken() {
        val tokens = NextendoOAuthTokenParser.parse(
            """{"access_token":"oauth.access.token","token_type":"Bearer","expires_in":3600,"scope":"identity friends"}""",
            now
        )

        assertEquals("oauth.access.token", tokens.accessToken)
        assertTrue(tokens.refreshToken.isEmpty())
        assertEquals(now + 3600L, tokens.expiresAtEpochSeconds)
    }

    @Test
    fun preservesRefreshTokenWhenProviderActuallyReturnsOne() {
        val tokens = NextendoOAuthTokenParser.parse(
            """{"access_token":"access","refresh_token":"refresh","token_type":"Bearer","expires_in":3600}""",
            now
        )

        assertEquals("refresh", tokens.refreshToken)
    }

    @Test
    fun rejectsNonBearerTokenTypes() = invalid(
        """{"access_token":"access","token_type":"MAC","expires_in":3600}"""
    )

    @Test
    fun rejectsMissingAccessTokenOrExpiry() {
        invalid("""{"token_type":"Bearer","expires_in":3600}""")
        invalid("""{"access_token":"access","token_type":"Bearer"}""")
    }

    @Test
    fun rejectsInvalidLifetimeAndOverflow() {
        invalid("""{"access_token":"access","expires_in":0}""")
        try {
            NextendoOAuthTokenParser.parse(
                """{"access_token":"access","expires_in":3600}""",
                Long.MAX_VALUE - 10L
            )
            fail("Expected expiry overflow to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun invalid(json: String) {
        try {
            NextendoOAuthTokenParser.parse(json, now)
            fail("Expected invalid OAuth response to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
