package com.nex.nx.nextendo

import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Read-only account profile client using the player's OAuth access token. */
object NextendoAccountApi {
    data class PlayerProfile(
        val pid: Long,
        val username: String,
        val friendCode: String,
        val avatarUrl: String
    )

    fun fetchOwnProfile(accessToken: String, pid: Long): PlayerProfile {
        require(accessToken.isNotBlank()) { "A conta Nextendo não está autenticada." }
        require(pid > 0L) { "O PID do jogador ainda não está disponível." }

        val uri = Uri.parse("https://nextendo.network/api/profile")
            .buildUpon()
            .appendQueryParameter("user", pid.toString())
            .build()

        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                NextendoAuthClient.CLIENT_ID_HEADER,
                NextendoAuthClient.CLIENT_ID
            )
            connection.setRequestProperty("Authorization", "Bearer $accessToken")

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toString("UTF-8")
            }.orEmpty()

            if (status !in 200..299) {
                throw IllegalStateException("A consulta de perfil Nextendo falhou (HTTP $status).")
            }

            val json = JSONObject(payload)
            val returnedPid = json.optLong("pid", pid)
            require(returnedPid == pid) {
                "O Nextendo devolveu um perfil diferente do jogador conectado."
            }

            return PlayerProfile(
                pid = returnedPid,
                username = json.optString("username"),
                friendCode = json.optString("friend_code"),
                avatarUrl = json.optString("avatar_url")
            )
        } finally {
            connection.disconnect()
        }
    }
}
