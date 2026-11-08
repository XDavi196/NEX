package com.nex.nx.nextendo

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** DEN2-inspired community view backed only by the account's own Nextendo API response. */
internal object NextendoCommunityDialog {
    fun show(
        activity: Activity,
        lobby: NextendoAccountExtrasApi.Lobby?,
        recentPlayers: List<NextendoAccountExtrasApi.LobbyPlayer>,
        lobbyError: String?,
        recentError: String?,
        onRefresh: () -> Unit,
        onAddFriend: (String) -> Unit,
        onReportPlayer: (Long) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun label(value: String, size: Float = 13f, muted: Boolean = false) =
            TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(if (muted) 0xFF9CA3AF.toInt() else 0xFFF4F6F8.toInt())
            }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }

        fun section(title: String) {
            root.addView(label(title, 12f).apply {
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFE30613.toInt())
                setPadding(0, dp(12), 0, dp(7))
            })
        }

        fun playerRow(player: NextendoAccountExtrasApi.LobbyPlayer) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            val identity = buildString {
                append(player.name.ifBlank { "Jogador ${player.pid}" })
                if (player.isMe) append(" · Você")
                else if (player.host) append(" · Host")
                if (player.friendCode.isNotBlank()) append("\n").append(player.friendCode)
                if (player.seenAt.isNotBlank()) append("\nVisto: ").append(player.seenAt)
            }
            row.addView(
                label(identity, 13f).apply { maxLines = 3 },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (player.known && !player.isMe && player.friendCode.isNotBlank()) {
                row.addView(Button(activity).apply {
                    text = "Adicionar"
                    setOnClickListener { onAddFriend(player.friendCode) }
                })
            }
            if (player.known && !player.isMe) {
                row.addView(Button(activity).apply {
                    text = "Reportar"
                    setOnClickListener { onReportPlayer(player.pid) }
                })
            }
            root.addView(row)
            root.addView(android.view.View(activity).apply {
                setBackgroundColor(0xFF303947.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                )
            })
        }

        section("SALA ATUAL")
        when {
            lobbyError != null -> root.addView(label("Não foi possível consultar a sala: $lobbyError", muted = true))
            lobby == null -> root.addView(label("Sala não consultada.", muted = true))
            !lobby.inLobby -> root.addView(label("Nenhuma sala ativa foi registrada para esta conta.", muted = true))
            else -> {
                root.addView(label("Jogo: ${lobby.titleId.ifBlank { "não informado" }}"))
                root.addView(label("Estado: ${lobby.stateCode.ifBlank { lobby.state.ifBlank { "não informado" } }}", muted = true))
                root.addView(label("Jogadores: ${lobby.count}/${lobby.max.takeIf { it > 0 } ?: "—"}", muted = true))
                if (lobby.players.isEmpty()) root.addView(label("A sala não retornou a lista de jogadores.", muted = true))
                else lobby.players.forEach(::playerRow)
            }
        }

        section("JOGADORES RECENTES")
        when {
            recentError != null -> root.addView(label("Não foi possível consultar os jogadores recentes: $recentError", muted = true))
            recentPlayers.isEmpty() -> root.addView(label("Ainda não há jogadores recentes disponíveis.", muted = true))
            else -> recentPlayers.forEach(::playerRow)
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(root)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Nextendo · Salas e jogadores")
            .setView(scroll)
            .setPositiveButton("Atualizar", null)
            .setNegativeButton("Fechar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                onRefresh()
            }
            dialog.window?.setLayout(
                minOf(activity.resources.displayMetrics.widthPixels - dp(28), dp(520)),
                (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
            )
        }
        dialog.show()
    }
}
