package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NextendoAccountTxtCodecTest {
    @Test
    fun parsesDenAccountFileAndOptionalRotatingRefreshFields() {
        val account = NextendoAccountTxtCodec.parse(
            """
            pid=1800000001
            username=Player Name
            friend_code=SW-0000-0000-0001
            token=oauth.access.token
            refresh_token=oauth.refresh.token
            token_expiry=1800003600
            """.trimIndent()
        )

        assertEquals(1_800_000_001L, account.pid)
        assertEquals("Player Name", account.username)
        assertEquals("SW-0000-0000-0001", account.friendCode)
        assertEquals("oauth.access.token", account.accessToken)
        assertEquals("oauth.refresh.token", account.refreshToken)
        assertEquals(1_800_003_600L, account.tokenExpiryEpochSeconds)
    }

    @Test
    fun acceptsBomCrLfAndAccessTokenAlias() {
        val account = NextendoAccountTxtCodec.parse(
            "\uFEFFpid=42\r\nusername=Player\r\naccess_token=access.token\r\n"
        )
        assertEquals(42L, account.pid)
        assertEquals("access.token", account.accessToken)
        assertEquals(0L, account.tokenExpiryEpochSeconds)
    }

    @Test
    fun rejectsMissingIdentityOrOAuthToken() {
        invalid("username=Player\ntoken=access")
        invalid("pid=2\nusername=Player")
        invalid("pid=0\nusername=Player\ntoken=access")
    }

    @Test
    fun rejectsGameTokenInOAuthTokenField() {
        invalid("pid=2\nusername=Player\ntoken=nx2.game-token")
    }

    @Test
    fun rejectsDuplicateAndConflictingFields() {
        invalid("pid=2\npid=3\nusername=Player\ntoken=access")
        invalid("pid=2\nusername=Player\ntoken=one\naccess_token=two")
    }

    @Test
    fun rejectsInvalidExpiryAndOversizedInput() {
        invalid("pid=2\nusername=Player\ntoken=access\ntoken_expiry=-1")
        invalid("x".repeat(32 * 1024 + 1))
    }

    private fun invalid(contents: String) {
        try {
            NextendoAccountTxtCodec.parse(contents)
            fail("Expected invalid nextendo_account.txt to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
