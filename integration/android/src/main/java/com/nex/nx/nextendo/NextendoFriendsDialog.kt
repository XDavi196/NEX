package com.nex.nx.nextendo

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** NEX-owned friends view, following the compact player-row layout familiar to Den2 users. */
object NextendoFriendsDialog {
    private const val COLOR_PLAYING = 0xFF22C55E.toInt()
    private const val COLOR_ONLINE = 0xFFF59E0B.toInt()
    private const val COLOR_OFFLINE = 0xFF9CA3AF.toInt()
    private const val NEX_RED = 0xFFE30613.toInt()

    fun show(
        activity: Activity,
        result: NextendoSocialApi.FriendList,
        onRefresh: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }

        val onlineCount = result.friends.count { it.presenceStatus == 1 || it.presenceStatus == 2 }
        val playingCount = result.friends.count { it.presenceStatus == 2 }
        val unknownCount = result.friends.count { it.presenceStatus < 0 }
        content.addView(
            TextView(activity).apply {
                text = "$onlineCount online · $playingCount jogando · $unknownCount sem presença · ${result.friends.size} amigos"
                textSize = 13f
                setTextColor(0xFF9CA3AF.toInt())
                setPadding(0, dp(4), 0, dp(14))
            }
        )

        fun addSectionTitle(label: String) {
            content.addView(
                TextView(activity).apply {
                    text = label
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(NEX_RED)
                    setPadding(0, dp(10), 0, dp(6))
                }
            )
        }

        fun decodeAvatar(encoded: String) = runCatching {
            if (encoded.isBlank() || encoded.length > 1_500_000) return@runCatching null
            val payload = encoded.substringAfter("base64,", encoded).trim()
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()

        fun friendRow(friend: NextendoSocialApi.Friend, isRequest: Boolean = false): View {
            val name = friend.username.ifBlank {
                if (friend.pid > 0L) "PID ${friend.pid}" else "Jogador Nextendo"
            }
            val statusColor = when {
                isRequest -> COLOR_ONLINE
                friend.presenceStatus == 2 -> COLOR_PLAYING
                friend.presenceStatus == 1 -> COLOR_ONLINE
                else -> COLOR_OFFLINE
            }
            val avatarFrame = FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF34383F.toInt())
                }
                clipToOutline = true
            }
            val bitmap = decodeAvatar(friend.imageBase64)
            if (bitmap != null) {
                avatarFrame.addView(
                    ImageView(activity).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        clipToOutline = true
                    },
                    FrameLayout.LayoutParams(dp(48), dp(48))
                )
            } else {
                avatarFrame.addView(
                    TextView(activity).apply {
                        text = name.firstOrNull()?.uppercase() ?: "N"
                        textSize = 18f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    },
                    FrameLayout.LayoutParams(dp(48), dp(48))
                )
            }

            avatarFrame.addView(
                View(activity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(statusColor)
                        setStroke(dp(2), 0xFF202329.toInt())
                    }
                },
                FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM or Gravity.END)
            )

            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            texts.addView(
                TextView(activity).apply {
                    text = name
                    textSize = 15f
                    setTextColor(0xFFF3F4F6.toInt())
                    maxLines = 1
                }
            )

            val detail = when {
                isRequest -> "Pedido de amizade recebido"
                friend.presenceStatus == 2 -> friend.presenceLabel()
                friend.presenceStatus == 1 -> "Online"
                friend.presenceStatus == 0 -> "Offline"
                else -> friend.presenceLabel()
            }
            texts.addView(
                TextView(activity).apply {
                    text = detail
                    textSize = 12f
                    setTextColor(statusColor)
                    maxLines = 1
                }
            )
            if (friend.friendCode.isNotBlank()) {
                texts.addView(
                    TextView(activity).apply {
                        text = friend.friendCode
                        textSize = 11f
                        setTextColor(0xFF9CA3AF.toInt())
                        maxLines = 1
                    }
                )
            }

            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(0x10222B36)
                    setStroke(dp(1), 0x223E4652)
                }
                addView(avatarFrame, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(
                    texts,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }.also { row ->
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = dp(7)
                row.layoutParams = params
            }
        }

        addSectionTitle("AMIGOS")
        if (result.friends.isEmpty()) {
            content.addView(
                TextView(activity).apply {
                    text = "Nenhum amigo foi retornado pela Nextendo. Atualize a lista ou confira se a permissão friends foi aprovada no login."
                    textSize = 13f
                    setTextColor(0xFF9CA3AF.toInt())
                    setPadding(0, dp(8), 0, dp(16))
                }
            )
        } else {
            result.friends.forEach { content.addView(friendRow(it)) }
        }

        if (result.requests.isNotEmpty()) {
            addSectionTitle("PEDIDOS RECEBIDOS (${result.requests.size})")
            result.requests.forEach { content.addView(friendRow(it, isRequest = true)) }
            content.addView(
                TextView(activity).apply {
                    text = "Os pedidos aparecem aqui para consulta. Aceitar ou recusar ainda não está habilitado no NEX."
                    textSize = 11f
                    setTextColor(0xFF9CA3AF.toInt())
                    setPadding(0, dp(4), 0, dp(8))
                }
            )
        }

        val visibleRows = result.friends.size + result.requests.size
        val scrollHeight = (dp(90) * visibleRows + dp(110)).coerceIn(dp(190), dp(520))
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                scrollHeight
            )
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Nextendo Network · Amigos")
            .setView(scroll)
            .setPositiveButton("Atualizar", null)
            .setNegativeButton("Fechar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                onRefresh()
            }
        }
        dialog.show()
    }
}
