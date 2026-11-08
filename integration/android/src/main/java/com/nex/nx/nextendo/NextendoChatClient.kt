// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from DEN's DenChat client (Dmr77680/Den2, GPL-3.0-or-later).
// NEX keeps its own account/session identity and encrypts the chat device secret.

package com.nex.nx.nextendo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-installation chat credential and consent. The secret is encrypted with
 * an AES-GCM key that never leaves Android Keystore.
 */
internal object NextendoChatIdentityStore {
    private const val PREFERENCES = "nex_nextendo_chat_identity"
    private const val KEY_ALIAS = "com.nex.nx.nextendo.chat.identity.v1"
    private const val SECRET_IV = "secret_iv"
    private const val SECRET_CIPHERTEXT = "secret_ciphertext"
    private const val CONSENTED_PID = "consented_pid"

    @Synchronized
    fun getOrCreateSecret(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val ivText = prefs.getString(SECRET_IV, null)
        val cipherText = prefs.getString(SECRET_CIPHERTEXT, null)
        if (!ivText.isNullOrBlank() && !cipherText.isNullOrBlank()) {
            val existing = runCatching { decrypt(ivText, cipherText) }.getOrNull()
            if (!existing.isNullOrBlank() && existing.length == 64) return existing
            prefs.edit().remove(SECRET_IV).remove(SECRET_CIPHERTEXT).commit()
        }

        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        val secret = random.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        val encrypted = encrypt(secret)
        val saved = prefs.edit()
            .putString(SECRET_IV, encrypted.first)
            .putString(SECRET_CIPHERTEXT, encrypted.second)
            .commit()
        check(saved) { "Não foi possível guardar a identidade segura do chat." }
        return secret
    }

    fun hasConsent(context: Context, pid: Long): Boolean {
        if (pid <= 0L) return false
        return context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(CONSENTED_PID, 0L) == pid
    }

    fun grantConsent(context: Context, pid: Long) {
        require(pid > 0L)
        val saved = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(CONSENTED_PID, pid)
            .commit()
        check(saved) { "Não foi possível guardar a autorização do chat." }
    }

    fun revokeConsent(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(CONSENTED_PID)
            .commit()
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

/**
 * Client for DEN's independent friend-chat service. This is not a Nextendo
 * endpoint and never receives NEX OAuth or nx2 tokens.
 */
internal object NextendoChatClient {
    const val SERVER_URL = "https://2-29-6-216.sslip.io"
    const val SERVER_OPERATOR_LABEL = "desenvolvedor do Den2"

    data class Reply(val code: Int, val json: JSONObject?)

    fun publishFriends(context: Context, friendPids: List<Long>): Reply {
        val friends = JSONArray()
        friendPids.asSequence()
            .filter { it > 0L }
            .distinct()
            .take(500)
            .forEach(friends::put)
        return request(context, "POST", "/v1/friends", JSONObject().put("friends", friends))
    }

    fun messages(context: Context, friendPid: Long, afterId: Long): Reply {
        require(friendPid > 0L && afterId >= 0L)
        return request(
            context,
            "GET",
            "/v1/messages?with=" + friendPid + "&after=" + afterId
        )
    }

    fun sendMessage(context: Context, friendPid: Long, message: String): Reply {
        require(friendPid > 0L)
        val normalized = message.trim()
        require(normalized.isNotEmpty() && normalized.length <= 500)
        return request(
            context,
            "POST",
            "/v1/send",
            JSONObject().put("to", friendPid).put("text", normalized)
        )
    }

    fun markRead(context: Context, friendPid: Long, upToMessageId: Long): Reply =
        request(
            context,
            "POST",
            "/v1/read",
            JSONObject().put("with", friendPid).put("up_to", upToMessageId)
        )

    fun setBlocked(context: Context, friendPid: Long, blocked: Boolean): Reply =
        request(
            context,
            "POST",
            "/v1/block",
            JSONObject().put("pid", friendPid).put("blocked", blocked)
        )

    fun report(context: Context, friendPid: Long, messageId: Long): Reply =
        request(
            context,
            "POST",
            "/v1/report",
            JSONObject()
                .put("pid", friendPid)
                .put("message_id", messageId)
                .put("reason", "reported from NEX")
        )

    private fun request(
        context: Context,
        method: String,
        path: String,
        body: JSONObject? = null
    ): Reply {
        return try {
            val session = NextendoSessionStore.load(context.applicationContext)
                ?: return Reply(401, null)
            val pid = session.pid
            if (pid <= 0L) return Reply(401, null)
            val secret = NextendoChatIdentityStore.getOrCreateSecret(context)
            val connection = URL(SERVER_URL + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer " + pid + "." + secret)
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use {
                        it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                    }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
                Reply(code, json)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            Reply(0, null)
        }
    }
}
