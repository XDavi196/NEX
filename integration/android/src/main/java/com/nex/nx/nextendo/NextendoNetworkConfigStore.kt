package com.nex.nx.nextendo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Non-secret routing configuration for the Nextendo replacement network.
 *
 * The main and NAT-check addresses are deployment values supplied by Nextendo.
 * They are never inferred from the auth host or silently defaulted to loopback.
 * The small configuration blob is encrypted so it follows the same storage
 * boundary as the account session.
 */
internal object NextendoNetworkConfigStore {
    private const val PREFERENCES = "nex_nextendo_network"
    private const val KEY_ALIAS = "com.nex.nx.nextendo.network.v1"
    private const val CIPHERTEXT = "config_ciphertext"
    private const val IV = "config_iv"

    data class Config(
        val serverIpv4: String,
        val natIpv4: String
    ) {
        init {
            require(NextendoNetworkAddress.isValidIpv4(serverIpv4)) {
                "O IP principal do Nextendo precisa ser um IPv4 válido."
            }
            require(NextendoNetworkAddress.isValidIpv4(natIpv4)) {
                "O IP do segundo ponto NAT precisa ser um IPv4 válido."
            }
            require(serverIpv4 != natIpv4) {
                "Os dois pontos do Nextendo precisam usar IPv4 diferentes."
            }
        }
    }

    fun save(context: Context, config: Config) {
        val json = JSONObject()
            .put("server_ipv4", config.serverIpv4)
            .put("nat_ipv4", config.natIpv4)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): Config? = runCatching {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val iv = preferences.getString(IV, null) ?: return null
        val ciphertext = preferences.getString(CIPHERTEXT, null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        val json = JSONObject(
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        )
        Config(
            serverIpv4 = json.getString("server_ipv4"),
            natIpv4 = json.getString("nat_ipv4")
        )
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(IV)
            .remove(CIPHERTEXT)
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

/** Strict IPv4 validation without resolving hostnames or using network access. */
internal object NextendoNetworkAddress {
    fun isValidIpv4(value: String): Boolean {
        if (value.isBlank() || value.length > 15 || value != value.trim()) return false
        val parts = value.split('.')
        if (parts.size != 4) return false
        if (parts.any { part ->
                part.isEmpty() || part.length > 3 ||
                    (part.length > 1 && part.startsWith('0')) ||
                    part.any { !it.isDigit() }
            }
        ) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        return octets.all { it in 0..255 } &&
            value != "0.0.0.0" &&
            value != "255.255.255.255"
    }
}
