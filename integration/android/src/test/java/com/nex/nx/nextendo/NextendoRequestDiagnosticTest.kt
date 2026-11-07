package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Test

class NextendoRequestDiagnosticTest {
    @Test
    fun distinguishesCommonProviderFailuresWithoutExposingCredentials() {
        assertEquals("HTTP 401 (authentication rejected)", NextendoRequestDiagnostic.fromHttpStatus(401))
        assertEquals("HTTP 403 (permission or client not authorized)", NextendoRequestDiagnostic.fromHttpStatus(403))
        assertEquals("HTTP 404 (endpoint not found)", NextendoRequestDiagnostic.fromHttpStatus(404))
        assertEquals("HTTP 429 (rate limited)", NextendoRequestDiagnostic.fromHttpStatus(429))
        assertEquals("HTTP 503 (server error)", NextendoRequestDiagnostic.fromHttpStatus(503))
    }

    @Test
    fun reportsMissingScopeByName() {
        assertEquals("scope_missing:game.matchmaking", NextendoRequestDiagnostic.missingScope("game.matchmaking"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidHttpStatus() {
        NextendoRequestDiagnostic.fromHttpStatus(99)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankScopeName() {
        NextendoRequestDiagnostic.missingScope(" ")
    }
}
