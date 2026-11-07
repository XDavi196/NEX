package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NextendoNetworkStatsParserTest {
    @Test
    fun parsesDocumentedPublicStats() {
        val result = NextendoNetworkStatsParser.parse(
            """{"online_players":142,"total_registered":12850,"games_online":9}"""
        )

        assertEquals(142L, result.onlinePlayers)
        assertEquals(12850L, result.totalRegistered)
        assertEquals(9L, result.gamesOnline)
    }

    @Test
    fun rejectsMissingOrNonIntegerCounters() {
        invalid("""{"online_players":2,"games_online":1}""")
        invalid("""{"online_players":"2","total_registered":10,"games_online":1}""")
        invalid("""{"online_players":2.5,"total_registered":10,"games_online":1}""")
    }

    @Test
    fun rejectsNegativeCountersAndMalformedJson() {
        invalid("""{"online_players":-1,"total_registered":10,"games_online":1}""")
        invalid("not-json")
    }

    private fun invalid(json: String) {
        try {
            NextendoNetworkStatsParser.parse(json)
            fail("Expected invalid Nextendo stats response to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
