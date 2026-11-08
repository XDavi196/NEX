package com.nex.nx.nextendo

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, password-protected backup for a user's own NEX Nextendo session.
 *
 * This format intentionally does not contain Android Keystore keys, BAAS
 * signing material, PKCE transactions, or credentials from another client.
 * A backup is a bearer-credential container: keep it private and never commit it.
 */
internal object NextendoSessionBackupCodec {
    const val MIN_PASSPHRASE_LENGTH = 12

    private const val FORMAT = "NEX_NEXTENDO_SESSION_BACKUP"
    private const val PAYLOAD_FORMAT = "NEX_NEXTENDO_SESSION"
    private const val VERSION = 1
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val CIPHER_NAME = "AES-256-GCM"
    private const val PBKDF2_ITERATIONS = 310_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val MAX_BACKUP_CHARS = 96 * 1024
    private const val MAX_TOKEN_CHARS = 16 * 1024
    private const val AAD = "NEX_NEXTENDO_SESSION_BACKUP|1|com.nex.nx"

    fun encode(session: NextendoSessionStore.Session, passphrase: CharArray): String {
        validatePassphrase(passphrase)
        validateSession(session)

        val payload = JSONObject()
            .put("format", PAYLOAD_FORMAT)
            .put("version", VERSION)
            .put("application_id", "com.nex.nx")
            .put("exported_at", System.currentTimeMillis() / 1000L)
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("access_expires_at", session.accessTokenExpiresAtEpochSeconds)
            .put("granted_scopes", JSONArray(session.grantedScopes.sorted()))
            .put("nex_token", session.nexGameToken)
            .put("nex_expires_at", session.nexGameTokenExpiresAtEpochSeconds)
            .put("pid", session.pid)
            .put("username", session.username)
            .put("friend_code", session.friendCode)

        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(passphrase, salt),
            GCMParameterSpec(128, iv)
        )
        cipher.updateAAD(AAD.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(payload.toString().toByteArray(StandardCharsets.UTF_8))

        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", KDF)
            .put("iterations", PBKDF2_ITERATIONS)
            .put("cipher", CIPHER_NAME)
            .put("salt", encodeBase64(salt))
            .put("iv", encodeBase64(iv))
            .put("ciphertext", encodeBase64(ciphertext))
            .toString(2)
            .also {
                require(it.length <= MAX_BACKUP_CHARS) {
                    "O backup excede o limite de tamanho permitido."
                }
            }
    }

    fun decode(backup: String, passphrase: CharArray): NextendoSessionStore.Session {
        validatePassphrase(passphrase)
        require(backup.isNotBlank() && backup.length <= MAX_BACKUP_CHARS) {
            "O arquivo de backup está vazio ou excede o tamanho permitido."
        }

        val envelope = try {
            JSONObject(backup)
        } catch (_: Exception) {
            throw IllegalArgumentException("O arquivo não é um backup NEX válido.")
        }

        require(envelope.optString("format") == FORMAT) {
            "Este arquivo não é um backup de sessão NEX. Arquivos nextendo_account.txt de outros clientes não são compatíveis."
        }
        require(envelope.optInt("version", -1) == VERSION) {
            "A versão deste backup NEX não é compatível com esta versão do aplicativo."
        }
        require(envelope.optString("kdf") == KDF && envelope.optString("cipher") == CIPHER_NAME) {
            "O formato de criptografia deste backup não é compatível."
        }
        require(envelope.optInt("iterations", -1) == PBKDF2_ITERATIONS) {
            "Os parâmetros de derivação da chave deste backup não são compatíveis."
        }

        val salt = decodeBase64(envelope.getString("salt"))
        val iv = decodeBase64(envelope.getString("iv"))
        val ciphertext = decodeBase64(envelope.getString("ciphertext"))
        require(salt.size == SALT_BYTES && iv.size == IV_BYTES) {
            "O backup contém parâmetros criptográficos inválidos."
        }
        require(ciphertext.isNotEmpty() && ciphertext.size <= MAX_BACKUP_CHARS) {
            "O conteúdo criptografado do backup é inválido."
        }

        val cleartext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(passphrase, salt),
                GCMParameterSpec(128, iv)
            )
            cipher.updateAAD(AAD.toByteArray(StandardCharsets.UTF_8))
            cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Senha incorreta ou arquivo de backup danificado.")
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("Não foi possível descriptografar o backup.")
        }

        val payload = try {
            JSONObject(cleartext)
        } catch (_: Exception) {
            throw IllegalArgumentException("O conteúdo do backup está inválido.")
        }
        require(
            payload.optString("format") == PAYLOAD_FORMAT &&
                payload.optInt("version", -1) == VERSION &&
                payload.optString("application_id") == "com.nex.nx"
        ) {
            "O backup não pertence a esta instalação do NEX."
        }

        val scopesJson = payload.optJSONArray("granted_scopes") ?: JSONArray()
        require(scopesJson.length() <= 32) { "O backup contém permissões demais." }
        val scopes = buildSet {
            for (index in 0 until scopesJson.length()) {
                val scope = scopesJson.optString(index)
                require(scope.matches(Regex("[A-Za-z0-9._:-]{1,64}"))) {
                    "O backup contém uma permissão inválida."
                }
                add(scope)
            }
        }

        val session = NextendoSessionStore.Session(
            accessToken = payload.optString("access_token"),
            refreshToken = payload.optString("refresh_token"),
            accessTokenExpiresAtEpochSeconds = payload.optLong("access_expires_at", 0L),
            grantedScopes = scopes,
            nexGameToken = payload.optString("nex_token"),
            nexGameTokenExpiresAtEpochSeconds = payload.optLong("nex_expires_at", 0L),
            pid = payload.optLong("pid", 0L),
            username = payload.optString("username"),
            friendCode = payload.optString("friend_code"),
            gameTokenDiagnostic = "restored_encrypted_backup"
        )
        validateSession(session)
        return session
    }

    private fun validatePassphrase(passphrase: CharArray) {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Use uma senha com pelo menos $MIN_PASSPHRASE_LENGTH caracteres."
        }
        require(passphrase.size <= 128) {
            "A senha do backup excede o limite de 128 caracteres."
        }
    }

    private fun validateSession(session: NextendoSessionStore.Session) {
        require(session.accessToken.isNotBlank() && session.accessToken.length <= MAX_TOKEN_CHARS) {
            "A sessão não contém um token de acesso válido."
        }
        require(!session.accessToken.startsWith("nx2.")) {
            "Um token de jogo não pode ser usado como token OAuth."
        }
        require(session.refreshToken.length <= MAX_TOKEN_CHARS) {
            "O token de renovação excede o limite permitido."
        }
        require(!session.refreshToken.startsWith("nx2.")) {
            "Um token de jogo não pode ser usado como token de renovação."
        }
        require(
            session.nexGameToken.isEmpty() ||
                (session.nexGameToken.startsWith("nx2.") &&
                    session.nexGameToken.length <= MAX_TOKEN_CHARS)
        ) {
            "O backup contém um token de jogo inválido."
        }
        require(
            session.accessTokenExpiresAtEpochSeconds >= 0L &&
                session.nexGameTokenExpiresAtEpochSeconds >= 0L &&
                session.pid >= 0L
        ) {
            "O backup contém dados de sessão inválidos."
        }
        require(session.username.length <= 128 && session.friendCode.length <= 128) {
            "O backup contém informações de perfil acima do limite."
        }
        require(session.grantedScopes.size <= 32 && session.grantedScopes.all {
            it.matches(Regex("[A-Za-z0-9._:-]{1,64}"))
        }) {
            "A sessão contém permissões inválidas."
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun encodeBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    private fun decodeBase64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
}
