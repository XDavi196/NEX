// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from DEN's DenChat.kt (Dmr77680/Den2, GPL-3.0-or-later).
// NEX adaptation: own session lookup, explicit third-party consent and NEX UI copy.

package com.nex.nx.nextendo

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Optional private messages between accepted Nextendo friends. */
internal object NextendoChatDialog {
    private const val CONVERSATION_REFRESH_MS = 3_000L
    private val main = Handler(Looper.getMainLooper())

    fun open(
        activity: Activity,
        friend: NextendoSocialApi.Friend,
        allFriendPids: List<Long>
    ) {
        if (activity.isFinishing || activity.isDestroyed || friend.pid <= 0L) return
        val session = runCatching { NextendoSessionStore.load(activity) }.getOrNull()
        val playerPid = session?.pid ?: 0L
        if (playerPid <= 0L) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Conta Nextendo necessária")
                .setMessage("Conecte uma conta Nextendo antes de abrir o chat.")
                .setPositiveButton("Conectar") { _, _ -> NextendoAuthManager.startLogin(activity) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        if (NextendoChatIdentityStore.hasConsent(activity, playerPid)) {
            connectAndOpen(activity, friend, allFriendPids)
            return
        }

        val disclosure = (
            "Este chat usa um serviço independente operado pelo " +
                NextendoChatClient.SERVER_OPERATOR_LABEL + ", não pela Nextendo.\n\n" +
                "Servidor: " + NextendoChatClient.SERVER_URL + " (hospedado na Finlândia/UE).\\n" +
                "Contato do operador e pedidos de exclusão: https://discord.gg/YjdS6wrbUj\\n\\n" +
                "Para funcionar, o NEX enviará seu PID Nextendo, os PIDs da sua lista de amigos, " +
                "as mensagens, confirmações de leitura e ações de bloquear/denunciar. " +
                "As mensagens ficam armazenadas no servidor para que seus amigos possam recebê-las.\n\n" +
                "Sua senha, token OAuth e token nx2 não são enviados a esse serviço. " +
                "Para solicitar a exclusão dos dados mantidos pelo servidor, contate o operador " +
                "pelo Discord do Den2. Ao continuar, você concorda com esse compartilhamento."
            )
        MaterialAlertDialogBuilder(activity)
            .setTitle("Ativar chat externo")
            .setMessage(disclosure)
            .setPositiveButton("Aceitar e abrir") { _, _ ->
                runCatching { NextendoChatIdentityStore.grantConsent(activity, playerPid) }
                    .onSuccess { connectAndOpen(activity, friend, allFriendPids) }
                    .onFailure {
                        Toast.makeText(
                            activity,
                            "Não foi possível guardar sua autorização.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    private fun connectAndOpen(
        activity: Activity,
        friend: NextendoSocialApi.Friend,
        allFriendPids: List<Long>
    ) {
        Toast.makeText(activity, "Sincronizando amigos com o serviço de chat…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = NextendoChatClient.publishFriends(activity, allFriendPids)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (result.code in 200..299) {
                    showConversation(activity, friend)
                } else {
                    val serverMessage = result.json?.optString("error").orEmpty()
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Chat indisponível")
                        .setMessage(
                            serverMessage.ifBlank {
                                "Não foi possível registrar a lista de amigos no servidor externo. " +
                                    "Verifique a conexão ou tente novamente."
                            }
                        )
                        .setPositiveButton("Tentar novamente") { _, _ ->
                            connectAndOpen(activity, friend, allFriendPids)
                        }
                        .setNegativeButton("Fechar", null)
                        .show()
                }
            }
        }.start()
    }

    private fun showConversation(activity: Activity, friend: NextendoSocialApi.Friend) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val friendName = friend.username.ifBlank { "Amigo Nextendo" }

        val status = TextView(activity).apply {
            textSize = 12f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, 0, 0, dp(6))
        }
        val messages = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        val scroll = ScrollView(activity).apply { addView(messages) }
        val input = EditText(activity).apply {
            hint = "Escreva uma mensagem…"
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            maxLines = 4
        }
        val sendButton = Button(activity).apply { text = "Enviar" }
        val inputBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(2))
            addView(status)
            val landscape = activity.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            minimumHeight = dp(if (landscape) 150 else 300)
            addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            addView(
                inputBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        var lastMessageId = 0L
        var lastFriendMessageId = 0L
        var isBlocked = false
        var alive = true
        var busy = false

        fun addBubble(value: String, fromMe: Boolean) {
            val bubble = TextView(activity).apply {
                text = value
                textSize = 14f
                setTextColor(0xFFF4F6F8.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (fromMe) 0x553B82F6 else 0x334B5563)
                }
                setTextIsSelectable(true)
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (fromMe) Gravity.END else Gravity.START
                topMargin = dp(4)
                if (fromMe) marginStart = dp(44) else marginEnd = dp(44)
            }
            messages.addView(bubble, params)
        }

        fun poll() {
            if (!alive || busy) return
            busy = true
            val afterId = lastMessageId
            Thread {
                val result = NextendoChatClient.messages(activity, friend.pid, afterId)
                activity.runOnUiThread {
                    busy = false
                    if (!alive || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val rows = result.json?.optJSONArray("messages")
                    if (result.code !in 200..299 || rows == null) {
                        status.text = "Não foi possível atualizar a conversa."
                        return@runOnUiThread
                    }
                    isBlocked = result.json?.optBoolean("blocked") ?: false
                    var added = false
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index) ?: continue
                        val id = row.optLong("id")
                        if (id <= lastMessageId) continue
                        val fromMe = row.optBoolean("from_me")
                        addBubble(row.optString("text").take(500), fromMe)
                        lastMessageId = id
                        if (!fromMe) lastFriendMessageId = id
                        added = true
                    }
                    status.text = if (messages.childCount == 0) {
                        "Ainda não há mensagens. Diga olá!"
                    } else {
                        if (isBlocked) "Conversa bloqueada." else ""
                    }
                    if (added) {
                        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                        Thread {
                            NextendoChatClient.markRead(activity, friend.pid, lastMessageId)
                        }.start()
                    }
                }
            }.start()
        }

        sendButton.setOnClickListener {
            val message = input.text.toString().trim()
            if (message.isEmpty()) return@setOnClickListener
            sendButton.isEnabled = false
            Thread {
                val result = NextendoChatClient.sendMessage(activity, friend.pid, message)
                activity.runOnUiThread {
                    if (!alive || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    sendButton.isEnabled = true
                    if (result.code in 200..299) {
                        input.setText("")
                        poll()
                    } else {
                        val error = result.json?.optString("error").orEmpty()
                        Toast.makeText(
                            activity,
                            error.ifBlank { "Não foi possível enviar a mensagem." },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendButton.performClick()
                true
            } else {
                false
            }
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(friendName)
            .setView(content)
            .setNegativeButton("Fechar", null)
            .setNeutralButton("Opções", null)
            .create()

        fun showOptions() {
            val choices = arrayOf(
                if (isBlocked) "Desbloquear este amigo" else "Bloquear este amigo",
                "Denunciar a última mensagem",
                "Desativar chat neste dispositivo"
            )
            MaterialAlertDialogBuilder(activity)
                .setTitle(friendName)
                .setItems(choices) { _, selected ->
                    when (selected) {
                        0 -> {
                            val next = !isBlocked
                            Thread {
                                val result = NextendoChatClient.setBlocked(activity, friend.pid, next)
                                activity.runOnUiThread {
                                    if (result.code in 200..299) {
                                        isBlocked = next
                                        Toast.makeText(
                                            activity,
                                            if (next) "Amigo bloqueado." else "Amigo desbloqueado.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        poll()
                                    } else {
                                        Toast.makeText(activity, "Não foi possível alterar o bloqueio.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }.start()
                        }
                        1 -> {
                            if (lastFriendMessageId <= 0L) {
                                Toast.makeText(activity, "Não há mensagem recebida para denunciar.", Toast.LENGTH_LONG).show()
                            } else {
                                Thread {
                                    val result = NextendoChatClient.report(
                                        activity,
                                        friend.pid,
                                        lastFriendMessageId
                                    )
                                    activity.runOnUiThread {
                                        Toast.makeText(
                                            activity,
                                            if (result.code in 200..299) "Denúncia enviada." else "Não foi possível enviar a denúncia.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }.start()
                            }
                        }
                        2 -> {
                            MaterialAlertDialogBuilder(activity)
                                .setTitle("Desativar chat?")
                                .setMessage(
                                    "O NEX deixará de acessar o servidor de chat neste dispositivo. " +
                                        "Isso não apaga mensagens já armazenadas pelo operador externo. " +
                                        "Para pedir a exclusão dos dados, contate o desenvolvedor do Den2."
                                )
                                .setPositiveButton("Desativar") { _, _ ->
                                    NextendoChatIdentityStore.revokeConsent(activity)
                                    dialog.dismiss()
                                    Toast.makeText(activity, "Chat externo desativado.", Toast.LENGTH_LONG).show()
                                }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        }
                    }
                }
                .setNegativeButton("Fechar", null)
                .show()
        }

        val ticker = object : Runnable {
            override fun run() {
                if (!alive) return
                poll()
                main.postDelayed(this, CONVERSATION_REFRESH_MS)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { showOptions() }
            ticker.run()
        }
        dialog.setOnDismissListener {
            alive = false
            main.removeCallbacks(ticker)
        }
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val decor = dialog.window?.decorView
        if (decor != null) {
            val landscape = activity.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            decor.viewTreeObserver.addOnGlobalLayoutListener {
                val visible = android.graphics.Rect()
                decor.getWindowVisibleDisplayFrame(visible)
                val keyboardVisible = visible.bottom < activity.resources.displayMetrics.heightPixels * 0.85
                val desiredHeight = if (keyboardVisible) 0 else dp(if (landscape) 150 else 300)
                if (content.minimumHeight != desiredHeight) {
                    content.minimumHeight = desiredHeight
                    scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            }
        }
    }
}
