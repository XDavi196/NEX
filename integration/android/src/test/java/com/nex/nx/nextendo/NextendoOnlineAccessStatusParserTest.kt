package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Test

class NextendoOnlineAccessStatusParserTest {
    @Test
    fun parsesAccountAdmissionAllowed() {
        val result = NextendoOnlineAccessStatusParser.parse("""{"allow":true,"reason":""}""")
        assertEquals(true, result.allow)
        assertEquals("", result.reason)
    }

    @Test
    fun parsesEveryDocumentedAdmissionBlocker() {
        listOf("unknown", "disabled", "unverified", "discord_unlinked", "elsewhere")
            .forEach { reason ->
                val result = NextendoOnlineAccessStatusParser.parse(
                    """{"allow":false,"reason":"$reason"}"""
                )
                assertEquals(false, result.allow)
                assertEquals(reason, result.reason)
            }
    }

    @Test
    fun rejectsUnknownReasonsAndContradictoryStates() {
        invalid("""{"allow":false,"reason":"server_key_missing"}""")
        invalid("""{"allow":true,"reason":"unverified"}""")
        invalid("""{"allow":false,"reason":""}""")
    }

    @Test
    fun rejectsMissingAndWronglyTypedFields() {
        invalid("""{"allow":true}""")
        invalid("""{"reason":""}""")
        invalid("""{"allow":"true","reason":""}""")
        invalid("""{"allow":false,"reason":1}""")
        invalid("not-json")
    }

    private fun invalid(json: String) {
        try {
            NextendoOnlineAccessStatusParser.parse(json)
            throw AssertionError("Expected invalid online-status response to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
