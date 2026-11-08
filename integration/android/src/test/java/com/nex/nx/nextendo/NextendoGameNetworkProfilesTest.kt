package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoGameNetworkProfilesTest {
    @Test
    fun includesTheFirstNexTitlesAndTheSmm2MainServer() {
        val byName = NextendoGameNetworkProfiles.profiles.associateBy { it.title }
        assertEquals("g22306d00-lp1.s.n.srv.nintendo.net", byName["Super Mario Maker 2"]?.authHostname)
        assertEquals("g2b309e01-lp1.s.n.srv.nintendo.net", byName["Mario Kart 8 Deluxe"]?.authHostname)
        assertEquals("g2df33d01-lp1.s.n.srv.nintendo.net", byName["Splatoon 2"]?.authHostname)
        assertEquals("g23380901-lp1.s.n.srv.nintendo.net", byName["Super Smash Bros. Ultimate"]?.authHostname)
        assertTrue(byName.size >= 8)
    }

    @Test
    fun routesAreExactUniqueAndFitTheNativeCacheCapacity() {
        val hosts = NextendoGameNetworkProfiles.allExactAuthHosts
        assertEquals(hosts.size, hosts.toSet().size)
        assertTrue(hosts.contains("g22306d00-lp1.s.n.srv.nintendo.net"))
        assertTrue(hosts.contains("dauth-lp1.ndas.srv.nintendo.net"))
        assertTrue(hosts.contains("aauth.hac.lp1.ndas.srv.nintendo.net"))
        assertFalse(hosts.any { it.startsWith("*.") })
        assertTrue(hosts.size <= 32)
    }

    @Test
    fun validatesOnlyUsableDistinctIpv4Addresses() {
        assertTrue(NextendoNetworkAddress.isValidIpv4("203.0.113.10"))
        assertTrue(NextendoNetworkAddress.isValidIpv4("198.51.100.20"))
        assertFalse(NextendoNetworkAddress.isValidIpv4("localhost"))
        assertFalse(NextendoNetworkAddress.isValidIpv4("256.0.0.1"))
        assertFalse(NextendoNetworkAddress.isValidIpv4("0.0.0.0"))
        assertFalse(NextendoNetworkAddress.isValidIpv4("255.255.255.255"))
        assertFalse(NextendoNetworkAddress.isValidIpv4("192.168.001.1"))
        assertFalse(NextendoNetworkAddress.isValidIpv4("1.2.3"))
    }
}
