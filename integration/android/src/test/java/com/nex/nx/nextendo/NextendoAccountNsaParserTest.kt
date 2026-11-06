package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Test

class NextendoAccountNsaParserTest {
    @Test
    fun acceptsSixteenCharacterHexNsa() {
        assertEquals(0xA1L, NextendoAccountNsaParser.parse("""{"nsa":"00000000000000A1"}"""))
    }

    @Test
    fun rejectsNumericShortMalformedAndZeroNsa() {
        assertEquals(0L, NextendoAccountNsaParser.parse("""{"nsa":161}"""))
        assertEquals(0L, NextendoAccountNsaParser.parse("""{"nsa":"A1"}"""))
        assertEquals(0L, NextendoAccountNsaParser.parse("""{"nsa":"XXXXXXXXXXXXXXXX"}"""))
        assertEquals(0L, NextendoAccountNsaParser.parse("""{"nsa":"0000000000000000"}"""))
    }
}
