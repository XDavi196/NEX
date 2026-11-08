package com.nex.nx.nextendo

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Data returned by DEN/Citron's account and community endpoints. */
internal object NextendoAccountExtrasApi {
    data class LobbyPlayer(
        val pid: Long,
        val name: String,
        val known: Boolean,
        val avatarUrl: String,
        val friendCode: String,
        val host: Boolean,
        val isMe: Boolean,
        val titleId: String,
        val seenAt: String
    )

    data class Lobby(
        val inLobby: Boolean,
        val titleId: String,
        val type: String,
        val state: String,
        val stateCode: String,
        val id: Long,
        val count: Int,
        val max: Int,
        val players: List<LobbyPlayer>
    )

    data class Profile(
        val username: String,
        val imageBase64: String,
        val consoleNickname: String,
        val miiBase64: String,
        val avatarId: String,
        val colorHex: String
    )

    data class HistoryEntry(
        val titleId: String,
        val name: String,
        val iconBase64: String,
        val seconds: Long,
        val lastPlayed: String
    )

    data class SaveUploadResult(
        val ok: Boolean,
        val kept: Boolean = false,
        val tooLarge: Boolean = false,
        val error: String = ""
    )

    private const val MAX_PROFILE_IMAGE_BASE64 = 1_500_000
    private const val MAX_MII_BASE64 = 128_000
    private val titleIdPattern = Regex("^[0-9A-Fa-f]{16}$")

    /** Reads the account's current lobby. This reports server state; it does not create a room. */
    /** Lists recent encounters. No player IP address is requested or stored. */
    /** Public avatar lookup by numeric PID only; server-provided avatar URLs are never followed. */
    /** Public gallery avatar lookup with a path-safe server avatar identifier. */
    fun fetchGalleryAvatar(avatarId: String): ByteArray {
        require(Regex("^[A-Za-z0-9_-]{1,80}$").matches(avatarId)) {
            "O identificador do avatar Nextendo é inválido."
        }
        return NextendoAuthClient.requestPublicBytes("/avatars/$avatarId.jpg")
    }

    fun fetchProfile(gameToken: String): Profile {
        return parseProfile(
            NextendoAuthClient.requestGameAccountJson("/api/profile", gameToken)
        )
    }

    /**
     * Updates the whole server profile blob. Existing fields are copied first because
     * the endpoint replaces the blob wholesale rather than merging individual fields.
     * A null parameter means "leave unchanged"; an empty string explicitly clears it.
     */
    fun updateProfile(
        gameToken: String,
        consoleNickname: String? = null,
        imageBase64: String? = null,
        miiBase64: String? = null,
        avatarId: String? = null,
        colorHex: String? = null
    ) {
        imageBase64?.let {
            require(it.length <= MAX_PROFILE_IMAGE_BASE64) { "A imagem de perfil excede o limite." }
        }
        miiBase64?.let {
            require(it.length <= MAX_MII_BASE64) { "Os dados do Mii excedem o limite." }
        }
        avatarId?.let {
            require(it.isEmpty() || Regex("^[A-Za-z0-9_-]{1,80}$").matches(it)) {
                "O ID de avatar é inválido."
            }
        }
        colorHex?.let {
            require(it.isEmpty() || Regex("^#[0-9A-Fa-f]{6}$").matches(it)) {
                "A cor deve estar no formato #RRGGBB."
            }
        }

        val current = fetchProfile(gameToken)
        val body = linkedMapOf<String, JsonElement>()
        fun preserve(key: String, changed: String?, existing: String) {
            val value = changed ?: existing
            if (changed != null || value.isNotEmpty()) body[key] = JsonPrimitive(value)
        }
        preserve("name", consoleNickname, current.consoleNickname)
        preserve("image", imageBase64, current.imageBase64)
        preserve("mii", miiBase64, current.miiBase64)
        preserve("avatar", avatarId, current.avatarId)
        preserve("color", colorHex, current.colorHex)

        NextendoAuthClient.requestGameAccountJson(
            path = "/api/profile",
            gameToken = gameToken,
            method = "POST",
            body = JsonObject(body).toString().toByteArray(Charsets.UTF_8)
        )
    }

    fun setUsername(gameToken: String, username: String) {
        val normalized = username.trim()
        require(Regex("^[A-Za-z0-9_-]{3,16}$").matches(normalized)) {
            "O nome da conta deve ter de 3 a 16 caracteres: letras, números, _ ou -."
        }
        val body = JsonObject(mapOf("Username" to JsonPrimitive(normalized)))
        NextendoAuthClient.requestGameAccountJson(
            path = "/api/username",
            gameToken = gameToken,
            method = "PUT",
            body = body.toString().toByteArray(Charsets.UTF_8)
        )
    }

    fun fetchHistory(gameToken: String): List<HistoryEntry> {
        return parseHistory(
            NextendoAuthClient.requestGameAccountJson("/api/history", gameToken)
        )
    }

    fun syncHistory(gameToken: String, entries: List<HistoryEntry>) {
        require(entries.size <= 2_000) { "O histórico contém entradas demais para sincronizar." }
        val items = entries.map { entry ->
            require(titleIdPattern.matches(entry.titleId)) { "Um Title ID do histórico é inválido." }
            require(entry.seconds >= 0L) { "O tempo de jogo não pode ser negativo." }
            require(entry.name.length <= 160 && entry.iconBase64.length <= 350_000) {
                "Um item do histórico excede o limite de dados."
            }
            val values = linkedMapOf<String, JsonElement>(
                "title_id" to JsonPrimitive(entry.titleId.uppercase()),
                "seconds" to JsonPrimitive(entry.seconds),
                "last_played" to JsonPrimitive(entry.lastPlayed)
            )
            if (entry.name.isNotBlank()) values["name"] = JsonPrimitive(entry.name)
            if (entry.iconBase64.isNotBlank()) values["icon"] = JsonPrimitive(entry.iconBase64)
            JsonObject(values)
        }
        val body = JsonObject(mapOf("history" to JsonArray(items)))
        NextendoAuthClient.requestGameAccountJson(
            path = "/api/history",
            gameToken = gameToken,
            method = "PUT",
            body = body.toString().toByteArray(Charsets.UTF_8)
        )
    }

    fun downloadBcatSeed(gameToken: String, titleId: String): ByteArray {
        val id = normalizedTitleId(titleId)
        return NextendoAuthClient.requestGameAccountBytes("/api/bcat/$id", gameToken)
    }

    fun hashBcatSeedHex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val alphabet = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { value ->
                val unsigned = value.toInt() and 0xFF
                append(alphabet[unsigned ushr 4])
                append(alphabet[unsigned and 0x0F])
            }
        }
    }

    /** Returns null when the server reports that no cloud save exists yet. */
    fun pullSave(gameToken: String, titleId: String): ByteArray? {
        val id = normalizedTitleId(titleId)
        val bytes = NextendoAuthClient.requestGameAccountBytes("/api/save/$id", gameToken)
        return bytes.takeIf { it.isNotEmpty() }
    }

    fun pushSave(gameToken: String, titleId: String, data: ByteArray): SaveUploadResult {
        val id = normalizedTitleId(titleId)
        require(data.isNotEmpty()) { "O save está vazio; o upload foi cancelado." }
        return try {
            val response = NextendoAuthClient.requestGameAccountBytes(
                path = "/api/save/$id",
                gameToken = gameToken,
                method = "POST",
                body = data
            )
            val json = runCatching {
                Json.parseToJsonElement(String(response, Charsets.UTF_8)) as? JsonObject
            }.getOrNull()
            SaveUploadResult(ok = true, kept = json?.boolean("kept") ?: false)
        } catch (error: NextendoAuthClient.NextendoException) {
            SaveUploadResult(
                ok = false,
                tooLarge = error.httpStatus == 413,
                error = error.message.orEmpty()
            )
        }
    }

    /** Measures the public health endpoint; null means network or service failure. */
    fun pingBackend(): Long? = NextendoAuthClient.pingBackend()

    private fun normalizedTitleId(value: String): String {
        val normalized = value.trim().removePrefix("0x")
        require(titleIdPattern.matches(normalized)) {
            "O Title ID deve conter exatamente 16 dígitos hexadecimais."
        }
        return normalized.uppercase()
    }

    internal fun parseMyLobby(response: String): Lobby {
        val root = parseObject(response, "lobby")
        if (root.boolean("in_lobby") != true) {
            return Lobby(false, "", "", "", "", 0L, 0, 0, emptyList())
        }
        val lobbyData = root["lobby"] as? JsonObject
        return Lobby(
            inLobby = true,
            titleId = root.string("title_id"),
            type = lobbyData?.string("type").orEmpty(),
            state = lobbyData?.string("state").orEmpty(),
            stateCode = lobbyData?.string("state_code").orEmpty(),
            id = lobbyData?.long("id") ?: 0L,
            count = (lobbyData?.int("count") ?: 0).coerceAtLeast(0),
            max = (lobbyData?.int("max") ?: 0).coerceAtLeast(0),
            players = parsePlayers(root["players"] as? JsonArray)
        )
    }

    internal fun parseRecentPlayers(response: String): List<LobbyPlayer> {
        val root = parseObject(response, "recent players")
        return parsePlayers(root["players"] as? JsonArray)
    }

    internal fun parseProfile(response: String): Profile {
        val root = parseObject(response, "profile")
        val profile = root["profile"] as? JsonObject
        return Profile(
            username = root.string("username"),
            imageBase64 = profile?.string("image").orEmpty(),
            consoleNickname = profile?.string("name").orEmpty(),
            miiBase64 = profile?.string("mii").orEmpty(),
            avatarId = profile?.string("avatar").orEmpty(),
            colorHex = profile?.string("color").orEmpty()
        )
    }

    internal fun parseHistory(response: String): List<HistoryEntry> {
        val root = parseObject(response, "history")
        val entries = (root["history"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val titleId = item.string("title_id")
            val seconds = item.long("seconds") ?: return@mapNotNull null
            if (!titleIdPattern.matches(titleId) || seconds < 0L) return@mapNotNull null
            HistoryEntry(
                titleId = titleId.uppercase(),
                name = item.string("name"),
                iconBase64 = item.string("icon"),
                seconds = seconds,
                lastPlayed = item.string("last_played")
            )
        }
        return entries.sortedByDescending { it.lastPlayed }.take(2_000)
    }

    private fun parsePlayers(array: JsonArray?): List<LobbyPlayer> =
        array.orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val pid = item.long("pid") ?: return@mapNotNull null
            if (pid <= 0L) return@mapNotNull null
            LobbyPlayer(
                pid = pid,
                name = item.string("name"),
                known = item.boolean("known") ?: false,
                // Kept for display only. Avatar download always rebuilds the URL from PID.
                avatarUrl = item.string("avatar_url"),
                friendCode = item.string("friend_code"),
                host = item.boolean("host") ?: false,
                isMe = item.boolean("is_me") ?: false,
                titleId = item.string("title_id"),
                seenAt = item.string("seen_at")
            )
        }.distinctBy { it.pid }.take(300)

    private fun parseObject(response: String, label: String): JsonObject =
        runCatching {
            Json.parseToJsonElement(response) as? JsonObject
        }.getOrNull() ?: throw IllegalArgumentException("A resposta Nextendo de $label não é JSON válido.")

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.boolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull
}
