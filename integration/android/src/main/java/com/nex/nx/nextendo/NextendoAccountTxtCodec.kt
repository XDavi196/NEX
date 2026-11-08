package com.nex.nx.nextendo

/**
 * Parses DEN/Citron's nextendo_account.txt format.
 *
 * The file contains bearer credentials. Callers must never log its contents and
 * must move accepted credentials into the Android Keystore-backed NEX store.
 */
internal object NextendoAccountTxtCodec {
    private const val MAX_FILE_CHARS = 32 * 1024
    private const val MAX_TOKEN_CHARS = 8 * 1024

    data class Account(
        val pid: Long,
        val username: String,
        val friendCode: String,
        val accessToken: String,
        val refreshToken: String,
        val tokenExpiryEpochSeconds: Long
    )

    fun parse(contents: String): Account {
        require(contents.isNotBlank()) { "O arquivo nextendo_account.txt está vazio." }
        require(contents.length <= MAX_FILE_CHARS) {
            "O arquivo nextendo_account.txt excede o limite de 32 KiB."
        }

        val acceptedKeys = setOf(
            "pid", "username", "friend_code", "token", "access_token",
            "refresh_token", "token_expiry"
        )
        val fields = linkedMapOf<String, String>()
        contents.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                return@forEachIndexed
            }
            val separator = line.indexOf('=')
            if (separator <= 0) {
                return@forEachIndexed
            }

            val key = line.substring(0, separator).trim().lowercase()
            if (key !in acceptedKeys) return@forEachIndexed
            require(!fields.containsKey(key)) {
                "O arquivo nextendo_account.txt contém a chave '$key' duplicada (linha ${index + 1})."
            }
            fields[key] = line.substring(separator + 1).trim()
        }

        fun aliasValue(vararg aliases: String): String {
            val values = aliases.mapNotNull(fields::get).distinct()
            require(values.size <= 1) {
                "O arquivo nextendo_account.txt contém valores conflitantes para ${aliases.joinToString("/") }."
            }
            return values.firstOrNull().orEmpty()
        }

        val pidText = fields["pid"].orEmpty()
        require(pidText.matches(Regex("[0-9]{1,19}"))) {
            "O arquivo nextendo_account.txt não contém um PID válido."
        }
        val pid = pidText.toLongOrNull()
        require(pid != null && pid > 0L) {
            "O arquivo nextendo_account.txt não contém um PID válido."
        }

        val username = fields["username"].orEmpty()
        require(username.isNotBlank() && username.length <= 64) {
            "O arquivo nextendo_account.txt não contém um nome de usuário válido."
        }

        val accessToken = aliasValue("token", "access_token")
        validateBearer(accessToken, "token")
        require(!accessToken.startsWith("nx2.", ignoreCase = true)) {
            "O campo token precisa conter o token OAuth da conta, não o token de jogo nx2."
        }

        val refreshToken = fields["refresh_token"].orEmpty()
        if (refreshToken.isNotEmpty()) validateBearer(refreshToken, "refresh_token")

        val expiryText = fields["token_expiry"].orEmpty()
        val expiry = if (expiryText.isEmpty()) {
            0L
        } else {
            require(expiryText.matches(Regex("[0-9]{1,19}"))) {
                "O token_expiry do arquivo não é um timestamp Unix válido."
            }
            expiryText.toLongOrNull()
                ?: throw IllegalArgumentException("O token_expiry está fora do intervalo aceito.")
        }

        val friendCode = fields["friend_code"].orEmpty()
        require(friendCode.length <= 64 && friendCode.none(Char::isISOControl)) {
            "O Friend Code do arquivo é inválido."
        }

        return Account(
            pid = pid,
            username = username,
            friendCode = friendCode,
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenExpiryEpochSeconds = expiry
        )
    }

    private fun validateBearer(value: String, field: String) {
        require(value.isNotBlank() && value.length <= MAX_TOKEN_CHARS) {
            "O campo $field está vazio ou excede o limite permitido."
        }
        require(value.none { it.isWhitespace() || it.isISOControl() }) {
            "O campo $field contém caracteres inválidos."
        }
    }
}
