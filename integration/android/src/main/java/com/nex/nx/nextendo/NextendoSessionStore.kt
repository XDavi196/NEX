package com.nex.nx.nextendo

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
 * Stores Nextendo session and pending PKCE transaction data encrypted with an
 * AES-GCM key held by Android Keystore. There is intentionally no plaintext fallback.
 */
object NextendoSessionStore {
    private const val PREFERENCES = "nex_nextendo_secure_session"
    private const val KEY_ALIAS = "com.nex.nx.nextendo.session.v1"

    private const val SESSION_IV = "session_iv"
    private const val SESSION_CIPHERTEXT = "session_ciphertext"
    private const val PENDING_IV = "pending_iv"
    private const val PENDING_CIPHERTEXT = "pending_ciphertext"
    private const val PENDING_MAX_AGE_SECONDS = 10L * 60L

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val accessTokenExpiresAtEpochSeconds: Long,
        val grantedScopes: Set<String> = emptySet(),
        val nexGameToken: String = "",
        val nexGameTokenExpiresAtEpochSeconds: Long = 0L,
        val pid: Long = 0L,
        val username: String = "",
        val friendCode: String = "",
        val gameTokenDiagnostic: String = "not_checked"
    )

    data class PendingAuthorization(
        val codeVerifier: String,
        val state: String,
        val createdAtEpochSeconds: Long
    )

    fun saveOAuthTokens(context: Context, tokens: NextendoAuthClient.OAuthTokens) {
        val previous = runCatching { load(context) }.getOrNull()
        saveSession(
            context,
            Session(
                accessToken = tokens.accessToken,
                // Preserve the current refresh token only when the provider omits
                // a replacement. When a rotated token is returned, it always wins.
                refreshToken = tokens.refreshToken.ifBlank { previous?.refreshToken.orEmpty() },
                accessTokenExpiresAtEpochSeconds = tokens.expiresAtEpochSeconds,
                grantedScopes = tokens.grantedScopes.ifEmpty { previous?.grantedScopes ?: emptySet() },
                nexGameToken = previous?.nexGameToken.orEmpty(),
                nexGameTokenExpiresAtEpochSeconds = previous?.nexGameTokenExpiresAtEpochSeconds ?: 0L,
                pid = previous?.pid ?: 0L,
                username = previous?.username.orEmpty(),
                friendCode = previous?.friendCode.orEmpty(),
                gameTokenDiagnostic = previous?.gameTokenDiagnostic ?: "not_checked"
            )
        )
    }

    /** Starts a new account session without carrying over the previous player's game token. */
    fun saveFreshOAuthTokens(context: Context, tokens: NextendoAuthClient.OAuthTokens) {
        saveSession(
            context,
            Session(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                accessTokenExpiresAtEpochSeconds = tokens.expiresAtEpochSeconds,
                grantedScopes = tokens.grantedScopes
            )
        )
    }

    fun saveAccountIdentity(
        context: Context,
        pid: Long,
        username: String,
        friendCode: String
    ) {
        val current = load(context)
            ?: throw IllegalStateException("Cannot save account identity without a linked Nextendo session.")
        saveSession(
            context,
            current.copy(
                pid = if (pid > 0L) pid else current.pid,
                username = username.ifBlank { current.username },
                friendCode = friendCode.ifBlank { current.friendCode }
            )
        )
    }

    fun saveNexGameToken(context: Context, token: NextendoAuthClient.NexGameToken) {
        val current = load(context)
            ?: throw IllegalStateException("Cannot save a game token without a linked Nextendo session.")

        saveSession(
            context,
            current.copy(
                nexGameToken = token.token,
                nexGameTokenExpiresAtEpochSeconds = token.expiresAtEpochSeconds,
                pid = token.pid,
                username = token.username,
                friendCode = token.friendCode,
                gameTokenDiagnostic = "HTTP 200 (nx2 accepted)"
            )
        )
    }

    /** Persists a token-free result label inside the encrypted session blob. */
    fun saveGameTokenDiagnostic(context: Context, diagnostic: String) {
        require(diagnostic.isNotBlank() && diagnostic.length <= 96) {
            "The game-token diagnostic must be a short, non-empty status label."
        }
        val current = load(context)
            ?: throw IllegalStateException("Cannot save a game-token diagnostic without a linked Nextendo session.")
        saveSession(context, current.copy(gameTokenDiagnostic = diagnostic))
    }

    /**
     * Exports the currently linked account as a portable, password-encrypted backup.
     * The backup contains bearer tokens and must be kept private.
     */
    fun exportEncryptedBackup(context: Context, passphrase: CharArray): String {
        val session = load(context)
            ?: throw IllegalStateException("Nenhuma conta Nextendo está conectada ao NEX.")
        return NextendoSessionBackupCodec.encode(session, passphrase)
    }

    /**
     * Restores only NEX-format encrypted backups. The destination is re-encrypted
     * with this installation's Android Keystore key before returning.
     */
    fun importEncryptedBackup(context: Context, backup: String, passphrase: CharArray) {
        val restored = NextendoSessionBackupCodec.decode(backup, passphrase)
        saveSession(context, restored)
        clearPendingAuthorization(context)
    }

    /**
     * Imports the DEN/Citron key=value account file into this installation's
     * encrypted session store. The source file itself is never copied or retained.
     */
    fun importDenAccountFile(context: Context, account: NextendoAccountTxtCodec.Account) {
        saveSession(
            context,
            Session(
                accessToken = account.accessToken,
                refreshToken = account.refreshToken,
                accessTokenExpiresAtEpochSeconds = account.tokenExpiryEpochSeconds,
                grantedScopes = emptySet(),
                pid = account.pid,
                username = account.username,
                friendCode = account.friendCode,
                gameTokenDiagnostic = "imported_waiting_for_server_validation"
            )
        )
        clearPendingAuthorization(context)
    }

    /** Records only scopes whose protected API operation succeeded on this server. */
    fun addVerifiedScopes(context: Context, scopes: Set<String>) {
        require(scopes.all { it.matches(Regex("[a-zA-Z0-9._-]{1,64}")) }) {
            "The verified OAuth scope set contains an invalid value."
        }
        val current = load(context)
            ?: throw IllegalStateException("Nenhuma conta Nextendo está conectada.")
        saveSession(context, current.copy(grantedScopes = current.grantedScopes + scopes))
    }

    fun load(context: Context): Session? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val ivText = preferences.getString(SESSION_IV, null) ?: return null
        val ciphertextText = preferences.getString(SESSION_CIPHERTEXT, null) ?: return null
        val json = JSONObject(decrypt(ivText, ciphertextText))

        val accessToken = json.optString("access_token")
        val refreshToken = json.optString("refresh_token")
        if (accessToken.isBlank()) return null

        return Session(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresAtEpochSeconds = json.optLong("access_expires_at", 0L),
            grantedScopes = json.optString("granted_scopes")
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
                .toSet(),
            nexGameToken = json.optString("nex_token"),
            nexGameTokenExpiresAtEpochSeconds = json.optLong("nex_expires_at", 0L),
            pid = json.optLong("pid", 0L),
            username = json.optString("username"),
            friendCode = json.optString("friend_code"),
            gameTokenDiagnostic = json.optString("game_token_diagnostic", "not_checked")
        )
    }

    fun savePendingAuthorization(context: Context, pending: NextendoAuthClient.PendingAuthorization) {
        val json = JSONObject()
            .put("code_verifier", pending.codeVerifier)
            .put("state", pending.state)
            .put("created_at", System.currentTimeMillis() / 1000L)

        val (iv, ciphertext) = encrypt(json.toString())
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(PENDING_IV, iv)
            .putString(PENDING_CIPHERTEXT, ciphertext)
            .commit()
        check(saved) { "Could not persist the pending Nextendo authorization." }
    }

    fun loadPendingAuthorization(context: Context): PendingAuthorization? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val iv = preferences.getString(PENDING_IV, null) ?: return null
        val ciphertext = preferences.getString(PENDING_CIPHERTEXT, null) ?: return null

        val pending = try {
            val json = JSONObject(decrypt(iv, ciphertext))
            PendingAuthorization(
                codeVerifier = json.getString("code_verifier"),
                state = json.getString("state"),
                createdAtEpochSeconds = json.getLong("created_at")
            )
        } catch (_: Exception) {
            clearPendingAuthorization(context)
            return null
        }

        val age = (System.currentTimeMillis() / 1000L) - pending.createdAtEpochSeconds
        if (age !in 0..PENDING_MAX_AGE_SECONDS) {
            clearPendingAuthorization(context)
            return null
        }
        return pending
    }

    fun clearPendingAuthorization(context: Context) {
        val cleared = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(PENDING_IV)
            .remove(PENDING_CIPHERTEXT)
            .commit()
        check(cleared) { "Could not clear the pending Nextendo authorization." }
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

    private fun saveSession(context: Context, session: Session) {
        val json = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("access_expires_at", session.accessTokenExpiresAtEpochSeconds)
            .put("granted_scopes", session.grantedScopes.sorted().joinToString(" "))
            .put("nex_token", session.nexGameToken)
            .put("nex_expires_at", session.nexGameTokenExpiresAtEpochSeconds)
            .put("pid", session.pid)
            .put("username", session.username)
            .put("friend_code", session.friendCode)
            .put("game_token_diagnostic", session.gameTokenDiagnostic)

        val (iv, ciphertext) = encrypt(json.toString())
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(SESSION_IV, iv)
            .putString(SESSION_CIPHERTEXT, ciphertext)
            .commit()
        check(saved) { "Could not persist the encrypted Nextendo session." }
    }

    private fun encrypt(cleartext: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(cleartext.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(ivText: String, ciphertextText: String): String {
        val iv = Base64.decode(ivText, Base64.NO_WRAP)
        val ciphertext = Base64.decode(ciphertextText, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

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
