package com.nex.nx.nextendo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Opens the Android document picker for a user-selected DEN/Citron
 * nextendo_account.txt file. The plaintext file is read once and is not copied
 * to shared storage; accepted credentials are re-encrypted by NextendoSessionStore.
 */
class NextendoAccountImportActivity : Activity() {
    companion object {
        private const val REQUEST_ACCOUNT_FILE = 0x4E58
        private const val MAX_FILE_BYTES = 32 * 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(picker, REQUEST_ACCOUNT_FILE)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Não foi possível abrir o seletor de arquivos do Android.",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    @Deprecated("Uses the Activity result callback for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ACCOUNT_FILE) return

        val uri: Uri? = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }

        val text = runCatching { readText(uri) }.getOrElse { error ->
            Toast.makeText(
                this,
                error.message ?: "Não foi possível ler o arquivo.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        NextendoAuthManager.importDenAccountText(this, text) { result ->
            if (!isFinishing && !isDestroyed) {
                result.fold(
                    onSuccess = { outcome ->
                        val message = if (outcome.gameTokenReady) {
                            "Conta DEN/Citron importada. O NEX obteve uma credencial de jogo usando seu Client ID."
                        } else {
                            "Conta importada e validada. A Nextendo ainda não liberou um token de jogo para o Client ID do NEX."
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this,
                            error.message ?: "Não foi possível importar a conta Nextendo.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
            finish()
        }
    }

    private fun readText(uri: Uri): String {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("O Android não conseguiu abrir o arquivo selecionado.")
        val output = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(4096)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                require(output.size() + read <= MAX_FILE_BYTES) {
                    "O arquivo nextendo_account.txt excede o limite de 32 KiB."
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray().toString(StandardCharsets.UTF_8)
    }
}
