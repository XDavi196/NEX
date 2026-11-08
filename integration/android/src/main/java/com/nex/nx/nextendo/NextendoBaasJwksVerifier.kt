package com.nex.nx.nextendo

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Verifies that the configured HTTPS jku endpoint actually publishes the exact
 * public key held by this NEX installation. A local JWK export alone is not
 * considered registration.
 *
 * This proves publication/matching only. It does not prove that the Nextendo
 * game service accepts the issuer, audience, subject mapping or token claims.
 */
internal object NextendoBaasJwksVerifier {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_JWKS_BYTES = 128 * 1024
    private val forbiddenPrivateMembers = setOf("d", "p", "q", "dp", "dq", "qi", "oth")

    internal data class VerifiedRegistration internal constructor(
        val jwksUrl: String,
        val keyId: String,
        private val modulus: String,
        private val exponent: String
    ) {
        fun matches(jwk: NextendoBaasJwk.PublicRsaJwk): Boolean =
            jwksUrl.isNotBlank() &&
                keyId == jwk.keyId &&
                modulus == jwk.modulus &&
                exponent == jwk.exponent
    }

    /** Fetches the JWKS with normal platform TLS validation and no redirects. */
    fun fetchAndVerify(
        jwksUrl: String,
        expected: NextendoBaasJwk.PublicRsaJwk
    ): VerifiedRegistration {
        validateHttpsJwksUrl(jwksUrl)
        val connection = URL(jwksUrl).openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("The BAAS JWKS endpoint must use HTTPS.")

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode
            require(status == HttpsURLConnection.HTTP_OK) {
                "The configured BAAS JWKS endpoint did not return HTTP 200 (HTTP $status)."
            }

            val body = connection.inputStream.use(::readBoundedUtf8)
            return verifyDocument(jwksUrl, body, expected)
        } finally {
            connection.disconnect()
        }
    }

    /** Pure verifier used by tests and by the HTTPS fetch path. */
    fun verifyDocument(
        jwksUrl: String,
        document: String,
        expected: NextendoBaasJwk.PublicRsaJwk
    ): VerifiedRegistration {
        validateHttpsJwksUrl(jwksUrl)
        require(document.toByteArray(StandardCharsets.UTF_8).size <= MAX_JWKS_BYTES) {
            "The BAAS JWKS document exceeds the configured size limit."
        }

        val root = runCatching {
            Json.parseToJsonElement(document).jsonObject
        }.getOrElse {
            throw IllegalArgumentException("The BAAS JWKS response is not valid JSON.")
        }
        val keys = root["keys"] as? JsonArray
            ?: throw IllegalArgumentException("The BAAS JWKS response has no keys array.")

        require(keys.none { element ->
            val item = element as? JsonObject ?: return@none false
            forbiddenPrivateMembers.any(item::containsKey)
        }) {
            "The BAAS JWKS response unexpectedly contains private RSA key material."
        }

        val matching = keys.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if (stringValue(item["kid"]) == expected.keyId) item else null
        }
        require(matching.size == 1) {
            "The BAAS JWKS endpoint must publish exactly one entry for the NEX kid."
        }

        val key = matching.single()
        require(stringValue(key["kty"]) == "RSA") {
            "The registered NEX JWK has the wrong key type."
        }
        require(stringValue(key["alg"]) == "RS256") {
            "The registered NEX JWK has the wrong signing algorithm."
        }
        require(stringValue(key["use"]) == "sig") {
            "The registered NEX JWK is not marked for signatures."
        }
        require(stringValue(key["n"]) == expected.modulus) {
            "The BAAS JWKS modulus does not match this NEX installation."
        }
        require(stringValue(key["e"]) == expected.exponent) {
            "The BAAS JWKS exponent does not match this NEX installation."
        }

        return VerifiedRegistration(
            jwksUrl = jwksUrl,
            keyId = expected.keyId,
            modulus = expected.modulus,
            exponent = expected.exponent
        )
    }

    private fun validateHttpsJwksUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(
            uri != null &&
                uri.scheme?.equals("https", ignoreCase = true) == true &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null &&
                uri.query == null
        ) {
            "The NEX BAAS JWKS URL must be a fixed HTTPS URL without credentials, query or fragment."
        }
    }

    private fun stringValue(element: JsonElement?): String? =
        runCatching { element?.jsonPrimitive?.content }.getOrNull()

    private fun readBoundedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_JWKS_BYTES) {
                "The BAAS JWKS response exceeds the configured size limit."
            }
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }
}
