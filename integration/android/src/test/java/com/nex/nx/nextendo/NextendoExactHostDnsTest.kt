package com.nex.nx.nextendo

import java.net.InetAddress
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoExactHostDnsTest {
    @Test
    fun redirectsOnlyExactKnownHostsAndKeepsUnrelatedDnsOnFallback() {
        var fallbackHost = ""
        val fallback = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                fallbackHost = hostname
                return listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0.toByte(), 2.toByte(), 10.toByte())))
            }
        }
        val dns = NextendoExactHostDns(
            serverIpv4 = "203.0.113.20",
            fallback = fallback,
            exactHosts = setOf("g22306d00-lp1.s.n.srv.nintendo.net")
        )

        val redirected = dns.lookup("G22306D00-LP1.S.N.SRV.NINTENDO.NET.")
        assertEquals(1, redirected.size)
        assertEquals("203.0.113.20", redirected.single().hostAddress)
        assertFalse(fallbackHost.isNotEmpty())

        val unrelatedHost = "api.nextendo.network"
        val normal = dns.lookup(unrelatedHost)
        assertEquals(unrelatedHost, fallbackHost)
        assertEquals("192.0.2.10", normal.single().hostAddress)
    }

    @Test
    fun exactHostListContainsMultipleGameAndDauthRoutes() {
        val hosts = NextendoGameNetworkProfiles.allExactAuthHosts
        assertTrue(hosts.contains("g22306d00-lp1.s.n.srv.nintendo.net"))
        assertTrue(hosts.contains("g2b309e01-lp1.s.n.srv.nintendo.net"))
        assertTrue(hosts.contains("dauth-lp1.ndas.srv.nintendo.net"))
        assertTrue(hosts.contains("acert-lp1.ndas.srv.nintendo.net"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidServerAddress() {
        NextendoExactHostDns("not-an-ip")
    }
}
