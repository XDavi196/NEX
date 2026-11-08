package com.nex.nx.nextendo

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * NEX account dashboard, adapted to Eden's Android UI and the public Nextendo
 * account contract. It displays only data returned by authenticated APIs or
 * local diagnostics; unsupported actions are intentionally not simulated.
 */
internal object NextendoDashboardDialog {
    data class Snapshot(
        val username: String,
        val friendCode: String,
        val pid: Long,
        val gameTokenReady: Boolean,
        val gameTokenDiagnostic: String,
        val presenceDiagnostic: String,
        val baasDiagnostic: String,
        val routeDiagnostic: String,
        val installedRouteCount: Int,
        val prudpDiagnostics: List<String>,
        val onlinePlayers: Long?,
        val registeredAccounts: Long?,
        val gamesOnline: Long?,
        val profileRefreshFailed: Boolean
    )

    private const val COLOR_BACKGROUND = 0xFF101318.toInt()
    private const val COLOR_CARD = 0xFF191E26.toInt()
    private const val COLOR_CARD_ALT = 0xFF202630.toInt()
    private const val COLOR_BORDER = 0xFF303947.toInt()
    private const val COLOR_TEXT = 0xFFF4F6F8.toInt()
    private const val COLOR_MUTED = 0xFFA2ACB9.toInt()
    private const val COLOR_RED = 0xFFE30613.toInt()
    private const val COLOR_GREEN = 0xFF35C77A.toInt()
    private const val COLOR_AMBER = 0xFFFFB547.toInt()

    private val dialogHolder = arrayOfNulls<AlertDialog>(1)

    fun show(
        activity: Activity,
        snapshot: Snapshot,
        onFriends: () -> Unit,
        onNetworkSettings: () -> Unit,
        onCheckOnlineAccess: () -> Unit,
        onCopyFriendCode: () -> Unit,
        onDisconnect: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
            TextView(activity).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }

        fun roundedBackground(color: Int, stroke: Int = 0): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(color)
                if (stroke != 0) setStroke(dp(1), stroke)
            }

        fun card(): MaterialCardView =
            MaterialCardView(activity).apply {
                radius = dp(17).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = COLOR_BORDER
                setCardBackgroundColor(COLOR_CARD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            }

        fun addSection(parent: LinearLayout, title: String, subtitle: String? = null) {
            parent.addView(
                text(title.uppercase(), 11f, COLOR_RED, true).apply {
                    letterSpacing = 0.08f
                    setPadding(dp(3), dp(12), dp(3), dp(6))
                }
            )
            if (!subtitle.isNullOrBlank()) {
                parent.addView(
                    text(subtitle, 12f, COLOR_MUTED).apply {
                        setPadding(dp(3), 0, dp(3), dp(8))
                    }
                )
            }
        }

        fun infoRow(
            parent: LinearLayout,
            title: String,
            value: String,
            valueColor: Int = COLOR_TEXT
        ) {
            parent.addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), dp(8), dp(2), dp(8))
                    addView(
                        text(title, 13f, COLOR_MUTED),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        text(value, 13f, valueColor, true).apply {
                            gravity = Gravity.END
                            maxLines = 2
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            )
        }

        fun actionButton(
            label: String,
            primary: Boolean = false,
            enabled: Boolean = true,
            action: () -> Unit
        ): MaterialButton =
            MaterialButton(activity).apply {
                text = label
                isAllCaps = false
                isEnabled = enabled
                cornerRadius = dp(13)
                setTextColor(if (primary) Color.WHITE else COLOR_TEXT)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (primary) COLOR_RED else COLOR_CARD_ALT
                )
                strokeWidth = if (primary) 0 else dp(1)
                strokeColor = android.content.res.ColorStateList.valueOf(COLOR_BORDER)
                setOnClickListener { action() }
                minHeight = dp(46)
                insetTop = 0
                insetBottom = 0
            }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(COLOR_BACKGROUND)
        }

        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(14))
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("NEX", 22f, COLOR_TEXT, true))
                    addView(text("NEXTENDO NETWORK", 10f, COLOR_MUTED, true))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                TextView(activity).apply {
                    text = "CONECTADO"
                    textSize = 10f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(COLOR_GREEN)
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    background = roundedBackground(0x2235C77A, 0x5535C77A)
                }
            )
        }
        root.addView(heading)

        val playerCard = card()
        val playerContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        playerContent.addView(text("PERFIL DO JOGADOR", 10f, COLOR_MUTED, true))
        playerContent.addView(
            text(snapshot.username.ifBlank { "Conta Nextendo" }, 20f, COLOR_TEXT, true).apply {
                setPadding(0, dp(5), 0, dp(3))
            }
        )
        playerContent.addView(
            text(
                snapshot.friendCode.ifBlank { "Friend Code indisponível" },
                13f,
                COLOR_RED,
                true
            ).apply {
                setPadding(0, 0, 0, dp(7))
            }
        )
        playerContent.addView(
            text(
                if (snapshot.pid > 0L) "PID " + snapshot.pid else "PID aguardando credencial de jogo",
                11f,
                COLOR_MUTED
            )
        )
        if (snapshot.profileRefreshFailed) {
            playerContent.addView(
                text(
                    "Não foi possível atualizar o perfil. Os dados locais foram mantidos.",
                    11f,
                    COLOR_AMBER
                ).apply { setPadding(0, dp(8), 0, 0) }
            )
        }
        playerCard.addView(playerContent)
        root.addView(playerCard)

        addSection(root, "Atividade da rede", "Estatísticas públicas da Nextendo")
        val metrics = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun metric(label: String, value: Long?): MaterialCardView {
            val metricCard = MaterialCardView(activity).apply {
                radius = dp(13).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = COLOR_BORDER
                setCardBackgroundColor(COLOR_CARD_ALT)
            }
            metricCard.addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(5), dp(11), dp(5), dp(11))
                    addView(text(value?.toString() ?: "—", 20f, COLOR_TEXT, true))
                    addView(text(label, 10f, COLOR_MUTED))
                }
            )
            return metricCard
        }
        val metricValues = listOf(
            metric("Jogadores", snapshot.onlinePlayers),
            metric("Contas", snapshot.registeredAccounts),
            metric("Jogos", snapshot.gamesOnline)
        )
        metricValues.forEachIndexed { index, view ->
            metrics.addView(
                view,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                    if (index < 2) rightMargin = dp(6)
                }
            )
        }
        root.addView(metrics)

        addSection(root, "Conexão e autenticação")
        val statusCard = card()
        val statusContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        infoRow(
            statusContent,
            "Token de jogo nx2.",
            if (snapshot.gameTokenReady) "DISPONÍVEL" else "PENDENTE",
            if (snapshot.gameTokenReady) COLOR_GREEN else COLOR_AMBER
        )
        infoRow(statusContent, "Diagnóstico nx2.", snapshot.gameTokenDiagnostic.ifBlank { "não verificado" })
        infoRow(statusContent, "Presença", snapshot.presenceDiagnostic, COLOR_AMBER)
        infoRow(statusContent, "Token BAAS / ACC", snapshot.baasDiagnostic, COLOR_AMBER)
        infoRow(
            statusContent,
            "Rotas de rede",
            if (snapshot.installedRouteCount > 0) {
                snapshot.installedRouteCount.toString() + " hosts"
            } else {
                snapshot.routeDiagnostic
            },
            if (snapshot.installedRouteCount > 0) COLOR_GREEN else COLOR_AMBER
        )
        statusCard.addView(statusContent)
        root.addView(statusCard)

        addSection(
            root,
            "Diagnóstico PRUDP",
            "Somente metadados de SYN/CONNECT; payloads, tickets e tokens não são exibidos."
        )
        val prudpCard = card()
        val prudpContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val recentPrudpPackets = snapshot.prudpDiagnostics.takeLast(8).asReversed()
        if (recentPrudpPackets.isEmpty()) {
            prudpContent.addView(
                text(
                    "Nenhum handshake PRUDP do Nextendo foi observado nesta sessão.",
                    12f,
                    COLOR_MUTED
                ).apply {
                    setPadding(dp(2), dp(8), dp(2), dp(8))
                }
            )
        } else {
            recentPrudpPackets.forEachIndexed { index, diagnostic ->
                prudpContent.addView(
                    text(diagnostic, 11f, COLOR_TEXT).apply {
                        typeface = Typeface.MONOSPACE
                        setPadding(dp(2), dp(7), dp(2), dp(7))
                    }
                )
                if (index < recentPrudpPackets.lastIndex) {
                    prudpContent.addView(
                        android.view.View(activity).apply {
                            setBackgroundColor(COLOR_BORDER)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(1)
                            )
                        }
                    )
                }
            }
        }
        prudpCard.addView(prudpContent)
        root.addView(prudpCard)

        addSection(root, "Multiplayer")
        val gameCard = card()
        val gameContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        gameContent.addView(text("Super Mario Maker 2", 15f, COLOR_TEXT, true))
        gameContent.addView(
            text(
                "Integração em andamento. A autenticação BAAS e a sessão do jogo ainda precisam de validação no servidor.",
                12f,
                COLOR_MUTED
            ).apply { setPadding(0, dp(5), 0, 0) }
        )
        gameCard.addView(gameContent)
        root.addView(gameCard)

        addSection(root, "Ações")
        val actionRow1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actionRow1.addView(
            actionButton("Amigos", true) {
                dialogDismissAndRun(dialogHolder[0], onFriends)
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) }
        )
        actionRow1.addView(
            actionButton("Configurar rede") {
                dialogDismissAndRun(dialogHolder[0], onNetworkSettings)
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) }
        )
        root.addView(actionRow1)

        val actionRow2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actionRow2.addView(
            actionButton("Copiar Friend Code", enabled = snapshot.friendCode.isNotBlank()) {
                onCopyFriendCode()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) }
        )
        actionRow2.addView(
            actionButton("Desconectar") {
                dialogDismissAndRun(dialogHolder[0], onDisconnect)
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) }
        )
        root.addView(actionRow2)

        val onlineCheckButton = actionButton("Verificar elegibilidade online", primary = true) {
            dialogDismissAndRun(dialogHolder[0], onCheckOnlineAccess)
        }
        root.addView(
            onlineCheckButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(8) }
        )

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(560)
            )
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(scroll)
            .setPositiveButton("Fechar", null)
            .create()
        dialogHolder[0] = dialog
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(
                minOf(activity.resources.displayMetrics.widthPixels - dp(28), dp(500)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun dialogDismissAndRun(dialogRef: AlertDialog?, action: () -> Unit) {
        dialogRef?.dismiss()
        action()
    }
}
