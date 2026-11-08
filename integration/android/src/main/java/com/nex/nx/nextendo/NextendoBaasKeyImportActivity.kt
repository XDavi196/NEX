package com.nex.nx.nextendo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Imports the authorized Nextendo BAAS signer PEM selected by the user.
 * The plaintext file is read once and saved encrypted in Android Keystore.
 */
class NextendoBaasKeyImportActivity : Activity() {
    companion object {
        private const val REQUEST_BAAS_KEY = 0x4E59
        private const val MAX_FILE_BYTES = 32 * 1024
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(picker, REQUEST_BAAS_KEY)
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
        if (requestCode != REQUEST_BAAS_KEY) return

        val uri: Uri? = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }

        val pem = runCatching { readPem(uri) }.getOrElse { error ->
            Toast.makeText(
                this,
                error.message ?: "Não foi possível ler o arquivo PEM.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        Toast.makeText(
            this,
            "Validando a chave com o JWKS da Nextendo…",
            Toast.LENGTH_SHORT
        ).show()
        executor.execute {
            val result = runCatching {
                NextendoBaasRuntime.installCitronSigningKey(applicationContext, pem)
            }
            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    result.fold(
                        onSuccess = { status ->
                            val message = when (status) {
                                "citron_baas_id_token_cached" ->
                                    "Chave BAAS Citron validada no JWKS e ACC atualizado."
                                "game_token_unavailable_or_expired" ->
                                    "Chave validada e salva; falta um nx2. válido para atualizar o ACC."
                                else ->
                                    "Chave BAAS Citron validada e salva. Estado da conta: $status."
                            }
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        },
                        onFailure = { error ->
                            Toast.makeText(
                                this,
                                error.message ?: "A chave não corresponde ao JWKS Nextendo.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
                finish()
            }
        }
    }

    private fun readPem(uri: Uri): String {
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
                    "O arquivo de chave BAAS excede o limite de 32 KiB."
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray().toString(StandardCharsets.UTF_8)
    }
}
