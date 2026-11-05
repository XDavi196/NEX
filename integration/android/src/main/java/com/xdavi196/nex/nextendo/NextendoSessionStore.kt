package com.xdavi196.nex.nextendo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores Nextendo session material encrypted with an AES-GCM key held by Android Keystore.
 *
 * There is intentionally no plaintext fallback. If Keystore encryption is unavailable,
 * saving fails rather than writing account tokens to ordinary preferences or files.
 */
object NextendoSessionStore {
    private const val PREFERENCES = "nex_nextendo_secure_session"
    private const val KEY_ALIAS = "com.xdavi196.nex.nextendo.session.v1"
    private const val VALUE_IV = "iv"
    private const val VALUE_CIPHERTEXT = "ciphertext"

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val accessTokenExpiresAtEpochSeconds: Long,
        val nexGameToken: String = "",
        val nexGameTokenExpiresAtEpochSeconds: Long = 0L
    )

    fun saveOAuthTokens(context: Context, tokens: NextendoAuthClient.OAuthTokens) {
        save(
            context,
            Session(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                accessTokenExpiresAtEpochSeconds = tokens.expiresAtEpochSeconds
            )
        )
    }

    fun saveNexGameToken(context: Context, token: NextendoAuthClient.NexGameToken) {
        val current = load(context)
            ?: throw IllegalStateException("Cannot save a game token without a linked Nextendo session.")

        save(
            context,
            current.copy(
                nexGameToken = token.token,
                nexGameTokenExpiresAtEpochSeconds = token.expiresAtEpochSeconds
            )
        )
    }

    fun load(context: Context): Session? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val ivText = preferences.getString(VALUE_IV, null) ?: return null
        val ciphertextText = preferences.getString(VALUE_CIPHERTEXT, null) ?: return null

        val iv = Base64.decode(ivText, Base64.NO_WRAP)
        val ciphertext = Base64.decode(ciphertextText, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val cleartext = cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
        val json = JSONObject(cleartext)

        return Session(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            accessTokenExpiresAtEpochSeconds = json.getLong("access_expires_at"),
            nexGameToken = json.optString("nex_token"),
            nexGameTokenExpiresAtEpochSeconds = json.optLong("nex_expires_at", 0L)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun save(context: Context, session: Session) {
        val json = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("access_expires_at", session.accessTokenExpiresAtEpochSeconds)
            .put("nex_token", session.nexGameToken)
            .put("nex_expires_at", session.nexGameTokenExpiresAtEpochSeconds)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(json.toString().toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv

        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(VALUE_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(VALUE_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()

        check(saved) { "Could not persist the encrypted Nextendo session." }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }
}
