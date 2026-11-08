package com.nex.nx.nextendo

/**
 * Builds a support/registration request containing only NEX public identity
 * and the public half of this installation's Android Keystore key.
 *
 * Copying this document does NOT register or publish the key. Nextendo must
 * add the exact JWK to the HTTPS JWKS endpoint used by the guest verifier.
 */
internal object NextendoBaasRegistrationRequest {
    fun build(jwk: NextendoBaasJwk.PublicRsaJwk): String {
        require(jwk.keyId.isNotBlank() && jwk.modulus.isNotBlank() && jwk.exponent.isNotBlank()) {
            "A complete public RSA JWK is required for registration."
        }

        return """
            {
              "schema_version": 1,
              "request_type": "nextendo_baas_public_jwk_registration",
              "application": {
                "name": "NEX",
                "android_application_id": "com.nex.nx",
                "oauth_client_id": "nxc_yAdR-14bOEq7",
                "oauth_redirect_uri": "com.nex.nx://oauth/callback"
              },
              "signing_key": {
                "algorithm": "RS256",
                "key_type": "RSA",
                "key_size_bits": 2048,
                "storage": "Android Keystore; non-exportable private key",
                "scope": "per-installation",
                "kid": "${jwk.keyId}",
                "rfc7638_thumbprint": "${jwk.keyId}",
                "public_jwks": ${jwk.toJwksJson()},
                "private_key_included": false
              },
              "requested_server_confirmation": [
                "Confirm whether NEX may register a distinct per-installation RS256 public JWK and kid.",
                "Provide or confirm the HTTPS jku URL and exact JWKS path that will serve this public key.",
                "Confirm the BAAS issuer, audience, subject/PID mapping, required claims and token lifetime.",
                "Confirm whether Android Keystore-backed signing is accepted for NEX game authentication.",
                "Provide the supported key rotation and revocation procedure."
              ],
              "status": "pending_nextendo_registration"
            }
        """.trimIndent()
    }
}
