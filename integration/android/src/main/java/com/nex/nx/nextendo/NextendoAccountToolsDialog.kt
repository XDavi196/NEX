package com.nex.nx.nextendo

import android.app.Activity
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton

internal object NextendoAccountToolsDialog {
    fun show(
        activity: Activity,
        profile: NextendoAccountExtrasApi.Profile?,
        history: List<NextendoAccountExtrasApi.HistoryEntry>,
        pingMs: Long?,
        profileError: String?,
        historyError: String?,
        onRefresh: () -> Unit,
        onChangeUsername: () -> Unit,
        onChangeConsoleNickname: () -> Unit,
        onChooseGalleryAvatar: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun text(value: String, size: Float = 13f, muted: Boolean = false) =
            TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(if (muted) 0xFF9CA3AF.toInt() else 0xFFF4F6F8.toInt())
            }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        root.addView(text("PERFIL NEXTENDO", 12f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFE30613.toInt())
            setPadding(0, dp(8), 0, dp(7))
        })
        if (profileError != null) {
            root.addView(text("Perfil: $profileError", muted = true))
        } else if (profile != null) {
            root.addView(text("Conta: ${profile.username.ifBlank { "não informada" }}"))
            root.addView(text("Nickname do console: ${profile.consoleNickname.ifBlank { "não definido" }}", muted = true))
            root.addView(text("Mii: ${if (profile.miiBase64.isNotBlank()) "configurado" else "não configurado"}", muted = true))
            root.addView(text("Avatar: ${if (profile.avatarId.isNotBlank() || profile.imageBase64.isNotBlank()) "configurado" else "não configurado"}", muted = true))
            root.addView(text("Cor: ${profile.colorHex.ifBlank { "padrão" }}", muted = true))
        }

        val editName = MaterialButton(activity).apply {
            text = "Alterar nome da conta"
            isAllCaps = false
            setOnClickListener { onChangeUsername() }
        }
        val editNickname = MaterialButton(activity).apply {
            text = "Alterar nickname do console"
            isAllCaps = false
            setOnClickListener { onChangeConsoleNickname() }
        }
        root.addView(editName, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ).apply { topMargin = dp(8) })
        root.addView(editNickname, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ))
        root.addView(MaterialButton(activity).apply {
            text = "Escolher avatar da galeria"
            isAllCaps = false
            setOnClickListener { onChooseGalleryAvatar() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ))

        root.addView(text("HISTÓRICO DE JOGOS", 12f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFE30613.toInt())
            setPadding(0, dp(18), 0, dp(7))
        })
        if (historyError != null) {
            root.addView(text("Histórico: $historyError", muted = true))
        } else if (history.isEmpty()) {
            root.addView(text("Nenhum histórico foi retornado pela Nextendo.", muted = true))
        } else {
            history.take(100).forEach { item ->
                val hours = item.seconds / 3600L
                val minutes = (item.seconds % 3600L) / 60L
                root.addView(text(item.name.ifBlank { item.titleId }, 14f))
                root.addView(text(
                    "${item.titleId} · ${hours}h ${minutes}min · ${item.lastPlayed.ifBlank { "sem data" }}",
                    11f, true
                ).apply { setPadding(0, dp(2), 0, dp(8)) })
            }
        }
        root.addView(text("Latência do backend: ${pingMs?.let { "$it ms" } ?: "indisponível"}", 12f, true)
            .apply { setPadding(0, dp(14), 0, dp(4)) })

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(root)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Nextendo · Perfil e histórico")
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
                (activity.resources.displayMetrics.heightPixels * 0.8f).toInt()
            )
        }
        dialog.show()
    }
}
