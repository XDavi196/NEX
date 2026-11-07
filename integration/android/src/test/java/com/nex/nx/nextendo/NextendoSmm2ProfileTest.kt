package com.nex.nx.nextendo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NextendoSmm2ProfileTest {
    @Test
    fun exposesOnlyThePublicSmm2TitleProfile() {
        assertEquals(40000, NextendoSmm2Profile.NEX_VERSION)
        assertEquals("fdf6617f", NextendoSmm2Profile.PRUDP_ACCESS_KEY)
        assertEquals(443, NextendoSmm2Profile.AUTH_WSS_PORT)
        assertEquals(60007, NextendoSmm2Profile.DEFAULT_SECURE_WSS_PORT)
        assertEquals(5, NextendoSmm2Profile.AUTH_PRUDP_MINOR_VERSION)
        assertEquals(0, NextendoSmm2Profile.SECURE_PRUDP_MINOR_VERSION)
        assertEquals(1300, NextendoSmm2Profile.DEFAULT_FRAGMENT_SIZE)
    }

    @Test
    fun preservesAuthHostnameForTlsSni() {
        val endpoint = NextendoSmm2Profile.authEndpoint("smm2.example.test")
        assertEquals("wss", endpoint.scheme)
        assertEquals("smm2.example.test", endpoint.host)
        assertEquals(443, endpoint.port)
        assertEquals("/", endpoint.path)
    }

    @Test
    fun followsTheSecureStationUrlAdvertisedByLoginEx() {
        val endpoint = NextendoSmm2Profile.secureEndpointFromStationUrl(
            "prudps:/address=secure.example.test;port=61234;PID=2"
        )
        assertEquals("wss", endpoint.scheme)
        assertEquals("secure.example.test", endpoint.host)
        assertEquals(61234, endpoint.port)
    }

    @Test
    fun securePortIsNotGuessedWhenStationUrlOmitsIt() {
        invalid {
            NextendoSmm2Profile.secureEndpointFromStationUrl(
                "prudps:/address=secure.example.test"
            )
        }
    }

    @Test
    fun rejectsUntrustedStationUrlSchemesAndMalformedHosts() {
        invalid {
            NextendoSmm2Profile.secureEndpointFromStationUrl(
                "prudp:/address=secure.example.test;port=60007"
            )
        }
        invalid { NextendoSmm2Profile.authEndpoint("https://secure.example.test/path") }
        invalid {
            NextendoSmm2Profile.secureEndpointFromStationUrl(
                "prudps:/address=secure.example.test;address=other.example.test;port=60007"
            )
        }
    }

    private fun invalid(action: () -> Unit) {
        try {
            action()
            fail("Expected invalid SMM2 endpoint configuration to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed behavior.
        }
    }
}
