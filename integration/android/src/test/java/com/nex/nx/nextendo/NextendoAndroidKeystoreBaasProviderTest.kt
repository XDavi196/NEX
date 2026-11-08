package com.nex.nx.nextendo

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoAndroidKeystoreBaasProviderTest {
    private object TestSigner : NextendoBaasSigner {
        override val keyId = "nex-unit-test-kid"
        override val jwksUrl = "https://nex.example.invalid/jwks"
        override fun signRs256(signingInput: ByteArray) = byteArrayOf(1, 2, 3)
    }

    private fun session(
        pid: Long = 123456L,
        gameToken: String = "nx2.server-issued-token",
        gameTokenExpiry: Long = System.currentTimeMillis() / 1000L + 7200L
    ) = NextendoSessionStore.Session(
        accessToken = "oauth-access-test",
        refreshToken = "oauth-refresh-test",
        accessTokenExpiresAtEpochSeconds = System.currentTimeMillis() / 1000L + 3600L,
        grantedScopes = setOf("game.matchmaking"),
        nexGameToken = gameToken,
        nexGameTokenExpiresAtEpochSeconds = gameTokenExpiry,
        pid = pid,
        username = "test-player",
        friendCode = "0000-0000-0000"
    )

    @Test
    fun claimsUseOnlyTheProvisionedIdentityMappingAndStandardTimes() {
        val current = session()
        val provider = NextendoAndroidKeystoreBaasProvider(
            signer = TestSigner,
            issuer = "https://baas.nextendo.example/issuer",
            audience = "nex-android",
            subjectForSession = { "registered-player-${it.pid}" },
            idTokenLifetimeSeconds = 3600L
        )

        val claims = provider.claimsFor(current)
        assertEquals(
            "https://baas.nextendo.example/issuer",
            claims.getValue("iss").jsonPrimitive.content
        )
        assertEquals("nex-android", claims.getValue("aud").jsonPrimitive.content)
        assertEquals("registered-player-123456", claims.getValue("sub").jsonPrimitive.content)
        assertEquals("true", claims.getValue("hm").jsonPrimitive.content)
        val now = System.currentTimeMillis() / 1000L
        val issuedAt = claims.getValue("iat").jsonPrimitive.content.toLong()
        val expiresAt = claims.getValue("exp").jsonPrimitive.content.toLong()
        assertTrue(issuedAt in (now - 2)..(now + 2))
        assertTrue(expiresAt > issuedAt)
        assertTrue(expiresAt <= current.nexGameTokenExpiresAtEpochSeconds)
        assertTrue(expiresAt - issuedAt <= 3600L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSessionWithoutVerifiedPid() {
        val provider = NextendoAndroidKeystoreBaasProvider(
            TestSigner,
            "https://baas.nextendo.example/issuer",
            "nex-android",
            { "subject" },
            3600L
        )
        provider.claimsFor(session(pid = 0L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExpiredGameToken() {
        val provider = NextendoAndroidKeystoreBaasProvider(
            TestSigner,
            "https://baas.nextendo.example/issuer",
            "nex-android",
            { "subject" },
            3600L
        )
        provider.claimsFor(session(gameTokenExpiry = 1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankServerProvisionedSubject() {
        val provider = NextendoAndroidKeystoreBaasProvider(
            TestSigner,
            "https://baas.nextendo.example/issuer",
            "nex-android",
            { "" },
            3600L
        )
        provider.claimsFor(session())
    }
}
