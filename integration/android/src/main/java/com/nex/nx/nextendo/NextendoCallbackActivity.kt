package com.nex.nx.nextendo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.yuzu.yuzu_emu.ui.main.MainActivity

/**
 * Receives the registered com.nex.nx://oauth/callback URI from the system browser.
 * The PKCE state is validated and the authorization code is exchanged off the UI thread.
 */
class NextendoCallbackActivity : Activity() {
    private var callbackStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent)
    }

    private fun handleCallback(callbackIntent: Intent?) {
        if (callbackStarted) return
        callbackStarted = true

        NextendoAuthManager.completeLogin(this, callbackIntent?.data) { result ->
            val message = result.fold(
                onSuccess = { outcome ->
                    if (outcome.gameTokenReady) {
                        "Conta Nextendo conectada. Token de jogo recebido; multiplayer ainda está em integração."
                    } else {
                        "Conta Nextendo conectada. Não foi possível obter o token de jogo; tente atualizar a sessão."
                    }
                },
                onFailure = {
                    "Não foi possível conectar ao Nextendo. Abra as configurações e tente novamente."
                }
            )

            Toast.makeText(this, message, Toast.LENGTH_LONG).show()

            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launchIntent)
            finish()
        }
    }
}
