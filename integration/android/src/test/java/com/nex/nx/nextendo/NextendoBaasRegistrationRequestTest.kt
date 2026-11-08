package com.nex.nx.nextendo

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextendoBaasRegistrationRequestTest {
    @Test
    fun requestContainsNexPublicIdentityAndOnlyPublicJwkMembers() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val jwk = NextendoBaasJwk.from(generator.generateKeyPair().public as RSAPublicKey)

        val root = Json.parseToJsonElement(NextendoBaasRegistrationRequest.build(jwk)).jsonObject
        val app = root.getValue("application").jsonObject
        val signingKey = root.getValue("signing_key").jsonObject
        val jwks = signingKey.getValue("public_jwks").jsonObject
        val publishedKey = (jwks.getValue("keys") as kotlinx.serialization.json.JsonArray).single().jsonObject

        assertEquals("nextendo_baas_public_jwk_registration", root.getValue("request_type").jsonPrimitive.content)
        assertEquals("com.nex.nx", app.getValue("android_application_id").jsonPrimitive.content)
        assertEquals("nxc_yAdR-14bOEq7", app.getValue("oauth_client_id").jsonPrimitive.content)
        assertEquals(jwk.keyId, signingKey.getValue("kid").jsonPrimitive.content)
        assertEquals(jwk.keyId, signingKey.getValue("rfc7638_thumbprint").jsonPrimitive.content)
        assertEquals("false", signingKey.getValue("private_key_included").jsonPrimitive.content)
        assertEquals(jwk.keyId, publishedKey.getValue("kid").jsonPrimitive.content)
        assertEquals(jwk.modulus, publishedKey.getValue("n").jsonPrimitive.content)
        assertEquals(jwk.exponent, publishedKey.getValue("e").jsonPrimitive.content)
        assertFalse(publishedKey.containsKey("d"))
        assertTrue(root.getValue("status").jsonPrimitive.content == "pending_nextendo_registration")
    }
}
