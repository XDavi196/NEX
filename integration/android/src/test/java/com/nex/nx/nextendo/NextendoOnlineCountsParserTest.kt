package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NextendoOnlineCountsParserTest {
    @Test
    fun parsesPublicTitleCountsAndNormalizesTitleIds() {
        val counts = NextendoOnlineCountsParser.parse(
            """{"counts":{"0100152000022000":7,"01006A800016E000":2}}"""
        )

        assertEquals(7, counts["0100152000022000"])
        assertEquals(2, counts["01006a800016e000"])
        assertEquals(2, counts.size)
    }

    @Test
    fun acceptsAnEmptyPublishedMapWithoutInventingTitles() {
        val counts = NextendoOnlineCountsParser.parse("""{"counts":{}}""")
        assertTrue(counts.isEmpty())
        assertEquals(null, counts["01009b90006dc000"])
    }

    @Test
    fun rejectsMissingMapInvalidTitleIdsAndNonIntegerCounts() {
        invalid("""{"online_players":5}""")
        invalid("""{"counts":{"smm2":5}}""")
        invalid("""{"counts":{"0100152000022000":"5"}}""")
        invalid("""{"counts":{"0100152000022000":1.5}}""")
        invalid("""{"counts":{"0100152000022000":-1}}""")
        invalid("not-json")
    }

    @Test
    fun rejectsCaseFoldedDuplicateTitleIds() {
        invalid("""{"counts":{"01006a800016e000":1,"01006A800016E000":2}}""")
    }

    private fun invalid(json: String) {
        try {
            NextendoOnlineCountsParser.parse(json)
            fail("Expected invalid online-counts JSON to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
