package com.nex.nx.nextendo

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * First-party Nextendo OAuth client for NEX.
 *
 * This is a public Android client: Authorization Code + PKCE (S256) is mandatory.
 * No client secret is embedded in the APK. Network calls are synchronous by design;
 * callers must run them on a background executor.
 */
object NextendoAuthClient {
    // Public Client ID registered for NEX in the Nextendo Developer Portal.
    const val CLIENT_ID = "nxc_yAdR-14bOEq7"
    const val CLIENT_ID_HEADER = "X-Nextendo-Client-Id"
    const val REDIRECT_URI = "com.nex.nx://oauth/callback"

    private const val BASE_URL = "https://nextendo.network"
    // The Developer Portal's current generated authorization link uses /oauth/authorize.
    private const val AUTHORIZE_URL = "$BASE_URL/oauth/authorize"
    private const val TOKEN_URL = "$BASE_URL/api/oauth/token"
    private const val NEX_TOKEN_URL = "$BASE_URL/api/nex-token"
    private const val USERINFO_URL = "$BASE_URL/api/oauth/userinfo"
    private const val MAX_BINARY_RESPONSE_BYTES = 64 * 1024 * 1024

    // Exact scopes shown by the current NEX URL generated in the Nextendo portal.
    const val SCOPES = "identity friends presence profil game.matchmaking emulator.status"

    data class PendingAuthorization(
        val codeVerifier: String,
        val state: String,
        val authorizationUri: Uri
    )

    data class OAuthTokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochSeconds: Long,
        val grantedScopes: Set<String> = emptySet()
    )

    data class NexGameToken(
        val token: String,
        val expiresAtEpochSeconds: Long,
        val pid: Long,
        val username: String,
        val friendCode: String
    )

    /** Public aggregate statistics; this endpoint does not require a player token. */
    internal fun fetchNetworkStats(): NextendoNetworkStats {
        val response = request(
            url = "$BASE_URL/api/stats",
            method = "GET",
            connectTimeoutMs = 4_000,
            readTimeoutMs = 4_000
        )
        return NextendoNetworkStatsParser.parse(response)
    }

    /** Public per-title online counts; the endpoint does not require a player token. */
    internal fun fetchOnlineCounts(): Map<String, Int> {
        val response = request(
            url = "$BASE_URL/api/online-counts",
            method = "GET",
            connectTimeoutMs = 4_000,
            readTimeoutMs = 4_000
        )
        return NextendoOnlineCountsParser.parse(response)
    }

    /** Read-only account-admission check authenticated by the NEX-issued nx2. token. */
    internal fun fetchOnlineAccessStatus(gameToken: String): NextendoOnlineAccessStatus {
        require(gameToken.startsWith("nx2.")) {
            "Online admission diagnostics require the Nextendo nx2. game token."
        }
        val response = request(
            url = "$BASE_URL/api/online-status",
            method = "GET",
            headers = mapOf("Authorization" to "Bearer $gameToken"),
            connectTimeoutMs = 4_000,
            readTimeoutMs = 4_000
        )
        return NextendoOnlineAccessStatusParser.parse(response)
    }

    class NextendoException(
        val httpStatus: Int,
        message: String
    ) : Exception(message)

    fun beginAuthorization(): PendingAuthorization {
        val verifier = base64Url(randomBytes(32))
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        )
        val state = base64Url(randomBytes(24))

        val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        return PendingAuthorization(verifier, state, uri)
    }

    /**
     * Exchanges a one-time authorization code. The callback state must match
     * the encrypted pending transaction before the code is exchanged.
     */
    fun exchangeAuthorizationCode(
        code: String,
        returnedState: String?,
        pending: PendingAuthorization
    ): OAuthTokens {
        require(code.isNotBlank()) { "Nextendo returned an empty authorization code." }
        // OAuth state is mandatory: never exchange a code when the callback
        // omitted it or when it does not match the encrypted PKCE transaction.
        require(!returnedState.isNullOrBlank()) {
            "Nextendo OAuth callback did not return the state parameter."
        }
        val expected = pending.state.toByteArray(StandardCharsets.UTF_8)
        val actual = returnedState.toByteArray(StandardCharsets.UTF_8)
        require(MessageDigest.isEqual(expected, actual)) {
            "Nextendo OAuth state validation failed."
        }

        val response = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to CLIENT_ID,
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to pending.codeVerifier
            )
        )
        return parseOAuthTokens(response)
    }

    /**
     * Refreshes the OAuth session. Persist the entire response immediately
     * because refresh tokens may rotate on every successful refresh.
     */
    fun refreshAccessToken(refreshToken: String): OAuthTokens {
        require(refreshToken.isNotBlank()) { "No Nextendo refresh token is stored." }

        val response = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to CLIENT_ID,
                "refresh_token" to refreshToken
            )
        )
        return parseOAuthTokens(response)
    }

    /**
     * Returns the separate NEX game credential. OAuth access tokens must never
     * be placed in the game's BAAS nnex claim.
     */
    fun fetchNexGameToken(accessToken: String): NexGameToken {
        require(accessToken.isNotBlank()) { "No Nextendo access token is available." }

        val response = request(
            url = NEX_TOKEN_URL,
            method = "GET",
            headers = mapOf("Authorization" to "Bearer $accessToken")
        )
        return NextendoGameTokenParser.parse(
            response = response,
            nowEpochSeconds = System.currentTimeMillis() / 1000L
        )
    }

    /** Retrieves OAuth userinfo as raw JSON, without Android's mocked org.json layer. */
    internal fun getUserInfoRaw(accessToken: String): String {
        require(accessToken.isNotBlank()) { "No Nextendo access token is available." }
        return request(
            url = USERINFO_URL,
            method = "GET",
            headers = mapOf("Authorization" to "Bearer $accessToken")
        )
    }

    /** Retrieves OAuth userinfo fields granted by the player. */
    internal fun getUserInfo(accessToken: String): JSONObject =
        JSONObject(getUserInfoRaw(accessToken))

    /**
     * Retrieves the richer game-facing friend snapshot. This route is protected
     * by a Nextendo-issued nx2. game token, not by an OAuth access token.
     */
    internal fun fetchGameFriends(gameToken: String): List<NextendoGameFriend> =
        fetchGameFriendSnapshot(gameToken).friends

    /** Fetches both accepted friends and incoming requests using the server-issued nx2 token. */
    internal fun fetchGameFriendSnapshot(gameToken: String): NextendoGameFriendSnapshot {
        requireGameToken(gameToken)
        val response = request(
            url = "$BASE_URL/api/friends",
            method = "GET",
            headers = mapOf("Authorization" to "Bearer $gameToken")
        )
        return NextendoGameFriendParser.parseSnapshot(response)
    }

    /** Sends a friend request by the player's public Switch-style friend code. */
    internal fun addFriendByCode(gameToken: String, friendCode: String) {
        requireGameToken(gameToken)
        val normalized = friendCode.trim().uppercase()
        require(Regex("^SW-[0-9]{4}-[0-9]{4}-[0-9]{4}$").matches(normalized)) {
            "Use o código de amigo no formato SW-1234-5678-9012."
        }
        postGameJson(
            "/api/friends",
            gameToken,
            JSONObject().put("friend_code", normalized)
        )
    }

    internal fun acceptFriendRequest(gameToken: String, pid: Long) =
        postFriendPid("/api/friends/accept", gameToken, pid)

    internal fun declineFriendRequest(gameToken: String, pid: Long) =
        postFriendPid("/api/friends/decline", gameToken, pid)

    internal fun removeFriend(gameToken: String, pid: Long) =
        postFriendPid("/api/friends/remove", gameToken, pid)

    private fun postFriendPid(path: String, gameToken: String, pid: Long) {
        requireGameToken(gameToken)
        require(pid > 0L) { "O PID do amigo é inválido." }
        postGameJson(path, gameToken, JSONObject().put("pid", pid))
    }

    private fun postGameJson(path: String, gameToken: String, json: JSONObject) {
        request(
            url = "$BASE_URL$path",
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $gameToken",
                "Content-Type" to "application/json; charset=utf-8"
            ),
            body = json.toString().toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun requireGameToken(gameToken: String) {
        require(gameToken.startsWith("nx2.") && gameToken.length > 4) {
            "Esta operação exige um token de jogo nx2. emitido pela Nextendo."
        }
    }

    /**
     * Retrieves the OAuth-scoped identity/friends document.
     *
     * The published account-server implementation validates OAuth access tokens
     * on /api/oauth/userinfo. Its /api/friends route currently uses a different
     * session-token validator, so an OAuth token must not be sent there.
     */
    internal fun getFriends(accessToken: String): JSONObject = getUserInfo(accessToken)

    internal fun postPresence(
        gameToken: String,
        status: Int,
        appField: String,
        appId: String,
        appDetail: String
    ) {
        requireGameToken(gameToken)
        require(status in 0..2) { "Presence status must be 0, 1 or 2." }

        val body = JSONObject()
            .put("status", status)
            .put("app_field", appField)
            .put("app_id", appId)
            // DEN/Citron's friend card reads app_name; NEX's newer parser also
            // accepts app_detail. Publish both labels for mixed client versions.
            .put("app_name", appDetail)
            .put("app_detail", appDetail)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        request(
            url = "$BASE_URL/api/presence",
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $gameToken",
                "Content-Type" to "application/json; charset=utf-8"
            ),
            body = body
        )
    }

    /** Public health ping; null represents HTTP/network failure. */
    internal fun pingBackend(): Long? {
        val started = System.nanoTime()
        return runCatching {
            request(
                url = "$BASE_URL/api/health",
                method = "GET",
                connectTimeoutMs = 3_000,
                readTimeoutMs = 3_000
            )
            ((System.nanoTime() - started) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong())
        }.getOrNull()
    }

    /**
     * Calls a protected Nextendo account endpoint with the signed nx2. bearer
     * credential. The server's accountFromBearer contract accepts web sessions
     * and nx2. tokens; OAuth access tokens are accepted only by /api/oauth/*.
     */
    internal fun requestGameAccountJson(
        path: String,
        gameToken: String,
        method: String = "GET",
        body: ByteArray? = null
    ): String {
        requireGameAccountToken(gameToken)
        validateAccountApiPath(path)
        val headers = linkedMapOf("Authorization" to "Bearer $gameToken")
        if (body != null) {
            headers["Content-Type"] = "application/json; charset=utf-8"
        }
        return request(
            url = "$BASE_URL$path",
            method = method,
            headers = headers,
            body = body
        )
    }

    /** Public image paths only; never follows a server-supplied avatar URL. */
    internal fun requestPublicBytes(path: String): ByteArray {
        val validAvatarPath = Regex("^/api/avatar\\?pid=[1-9][0-9]{0,18}$").matches(path)
        val validGalleryPath = Regex("^/avatars/[A-Za-z0-9_-]{1,80}\\.jpg$").matches(path)
        require(validAvatarPath || validGalleryPath) {
            "Apenas caminhos públicos de avatar Nextendo são permitidos."
        }
        return requestBytes("$BASE_URL$path", accessToken = null, method = "GET", body = null)
    }

    /** Binary BCAT and cloud-save transfer authenticated with the server-issued nx2. token. */
    internal fun requestGameAccountBytes(
        path: String,
        gameToken: String,
        method: String = "GET",
        body: ByteArray? = null
    ): ByteArray {
        requireGameAccountToken(gameToken)
        validateAccountApiPath(path)
        require(method == "GET" || method == "POST") {
            "Método inválido para transferência binária Nextendo."
        }
        return requestBytes(
            url = "$BASE_URL$path",
            accessToken = gameToken,
            method = method,
            body = body
        )
    }

    private fun requireGameAccountToken(gameToken: String) {
        require(gameToken.startsWith("nx2.") && gameToken.length > 4) {
            "Esta operação exige um token de jogo Nextendo nx2. válido; o OAuth access token não substitui essa credencial."
        }
    }

    private fun validateAccountApiPath(path: String) {
        require(
            path.startsWith("/api/") &&
                !path.contains("..") &&
                path.none { it.code < 0x20 } &&
                !path.startsWith("//")
        ) {
            "O caminho da API Nextendo não é permitido."
        }
    }

    private fun requestBytes(
        url: String,
        accessToken: String?,
        method: String,
        body: ByteArray?
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/octet-stream, image/jpeg, application/zip")
            connection.setRequestProperty(CLIENT_ID_HEADER, CLIENT_ID)
            if (!accessToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NO_CONTENT) return byteArrayOf()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BINARY_RESPONSE_BYTES) {
                        "A resposta binária Nextendo excedeu o limite de tamanho."
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()

            if (status !in 200..299) {
                val errorText = runCatching {
                    val errorJson = JSONObject(String(bytes, StandardCharsets.UTF_8))
                    errorJson.optString("error_description")
                        .ifBlank { errorJson.optString("error") }
                }.getOrNull().orEmpty()
                throw NextendoException(
                    status,
                    errorText.ifBlank { "Nextendo request failed (HTTP $status)." }
                )
            }
            require(bytes.size <= MAX_BINARY_RESPONSE_BYTES) {
                "A resposta binária Nextendo excedeu o limite de tamanho."
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun parseOAuthTokens(response: String): OAuthTokens =
        NextendoOAuthTokenParser.parse(
            response = response,
            nowEpochSeconds = System.currentTimeMillis() / 1000L
        )

    private fun postForm(url: String, values: Map<String, String>): String {
        val body = values.entries.joinToString("&") { (key, value) ->
            formEncode(key) + "=" + formEncode(value)
        }.toByteArray(StandardCharsets.UTF_8)

        return request(
            url = url,
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=utf-8"
            ),
            body = body
        )
    }

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(CLIENT_ID_HEADER, CLIENT_ID)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toString("UTF-8")
            }.orEmpty()

            if (status !in 200..299) {
                // Never include authorization headers or token values in error messages.
                val safeMessage = try {
                    JSONObject(payload).optString("error_description")
                        .ifBlank { JSONObject(payload).optString("error") }
                } catch (_: Exception) {
                    ""
                }
                throw NextendoException(
                    status,
                    safeMessage.ifBlank { "Nextendo request failed (HTTP $status)." }
                )
            }

            return payload
        } finally {
            connection.disconnect()
        }
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

    private fun formEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
