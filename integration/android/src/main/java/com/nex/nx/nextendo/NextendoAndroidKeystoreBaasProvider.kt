package com.nex.nx.nextendo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds the BAAS claim set only from values supplied by the Nextendo integration
 * contract. In particular, the subject mapping is injected rather than guessed.
 */
internal class NextendoAndroidKeystoreBaasProvider(
    override val signer: NextendoBaasSigner,
    private val issuer: String,
    private val audience: String,
    private val subjectForSession: (NextendoSessionStore.Session) -> String,
    private val idTokenLifetimeSeconds: Long
) : NextendoBaasProvisioningProvider {

    override fun claimsFor(
        session: NextendoSessionStore.Session
    ): Map<String, JsonElement> {
        require(session.pid > 0L) {
            "A verified Nextendo player PID is required before BAAS signing."
        }
        require(session.nexGameToken.startsWith("nx2.") && session.nexGameToken.length > 4) {
            "A valid Nextendo nx2. game token is required before BAAS signing."
        }

        val subject = subjectForSession(session)
        require(subject.isNotBlank()) {
            "The Nextendo BAAS subject mapping returned an empty subject."
        }

        val now = System.currentTimeMillis() / 1000L
        val expiresAt = minOf(
            now + idTokenLifetimeSeconds,
            session.nexGameTokenExpiresAtEpochSeconds
        )
        require(expiresAt > now) {
            "The Nextendo game token expires before a BAAS id_token can be issued."
        }

        return linkedMapOf(
            "iss" to JsonPrimitive(issuer),
            "aud" to JsonPrimitive(audience),
            "sub" to JsonPrimitive(subject),
            "hm" to JsonPrimitive(true),
            "iat" to JsonPrimitive(now),
            "exp" to JsonPrimitive(expiresAt)
        )
    }

    companion object {
        /**
         * Use only after Nextendo has confirmed the NEX issuer, audience,
         * subject mapping, claim semantics and registered this exact public JWK
         * at the supplied URL. This method does not contact or register with
         * Nextendo; it only creates/loads the Android Keystore signer.
         */
        fun createAfterJwksRegistration(
            jwksUrl: String,
            issuer: String,
            audience: String,
            subjectForSession: (NextendoSessionStore.Session) -> String,
            idTokenLifetimeSeconds: Long
        ): NextendoAndroidKeystoreBaasProvider {
            require(issuer.isNotBlank()) { "The NEX BAAS issuer is missing." }
            require(audience.isNotBlank()) { "The NEX BAAS audience is missing." }
            require(idTokenLifetimeSeconds in MIN_TOKEN_LIFETIME_SECONDS..MAX_TOKEN_LIFETIME_SECONDS) {
                "The BAAS id_token lifetime must be between 60 seconds and 24 hours."
            }
            val signer = NextendoAndroidKeystoreBaasSigner.createOrLoad(jwksUrl)
            return NextendoAndroidKeystoreBaasProvider(
                signer = signer,
                issuer = issuer,
                audience = audience,
                subjectForSession = subjectForSession,
                idTokenLifetimeSeconds = idTokenLifetimeSeconds
            )
        }

        private const val MIN_TOKEN_LIFETIME_SECONDS = 60L
        private const val MAX_TOKEN_LIFETIME_SECONDS = 24L * 60L * 60L
    }
}
