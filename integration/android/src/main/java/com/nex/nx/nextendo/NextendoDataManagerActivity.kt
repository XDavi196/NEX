package com.nex.nx.nextendo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** User-driven cloud-save, BCAT and profile-media transfers; never silently overwrites game data. */
internal class NextendoDataManagerActivity : Activity() {
    private enum class PendingImport { NONE, PROFILE_JPEG, MII }
    private enum class PendingExport { NONE, BCAT }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var titleIdInput: EditText
    private var pendingImport = PendingImport.NONE
    private var pendingExport = PendingExport.NONE
    private var pendingBytes: ByteArray? = null
    private var pendingFileName = "NEX-export.bin"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (NextendoSessionStore.load(this) == null) {
            Toast.makeText(this, "Conecte uma conta Nextendo primeiro.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        content.addView(TextView(this).apply {
            text = "Exportação manual de BCAT e importação de mídia de perfil. O serviço Nextendo de cloud saves foi removido em setembro de 2026; os saves locais do jogo não são afetados."
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })
        titleIdInput = EditText(this).apply {
            hint = "Title ID (16 dígitos hexadecimais)"
            setSingleLine(true)
        }
        content.addView(titleIdInput)
        addAction(content, "Exportar BCAT (ZIP)") { exportBcat() }
        addAction(content, "Atualizar foto do perfil (JPEG)") { chooseProfileJpeg() }
        addAction(content, "Atualizar Mii (arquivo binário)") { chooseMii() }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        title = "NEX · Dados Nextendo"
    }

    private fun addAction(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        parent.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun currentTitleId(): String? {
        val value = titleIdInput.text.toString().trim().removePrefix("0x")
        if (!Regex("^[0-9A-Fa-f]{16}$").matches(value)) {
            titleIdInput.error = "Informe exatamente 16 dígitos hexadecimais."
            return null
        }
        return value.uppercase()
    }

    private fun sessionForGameApi(): NextendoSessionStore.Session {
        var session = NextendoSessionRefresher.getValidSession(this, 60L)
        val now = System.currentTimeMillis() / 1000L
        if (
            !session.nexGameToken.startsWith("nx2.") ||
            session.nexGameTokenExpiresAtEpochSeconds <= now
        ) {
            val gameToken = try {
                NextendoAuthClient.fetchNexGameToken(session.accessToken)
            } catch (error: NextendoAuthClient.NextendoException) {
                if (error.httpStatus != 401) throw error
                val refreshed = NextendoSessionRefresher.refreshAfterUnauthorized(
                    context = this,
                    rejectedAccessToken = session.accessToken
                )
                NextendoAuthClient.fetchNexGameToken(refreshed.accessToken)
            }
            NextendoSessionStore.saveNexGameToken(this, gameToken)
            session = NextendoSessionStore.load(this)
                ?: throw IllegalStateException("A sessão Nextendo não está disponível.")
        }
        require(
            session.nexGameToken.startsWith("nx2.") &&
                session.nexGameTokenExpiresAtEpochSeconds > System.currentTimeMillis() / 1000L
        ) { "A conta ainda não tem um token de jogo nx2. válido." }
        return session
    }

    private fun withSession(block: (NextendoSessionStore.Session) -> Unit) {
        executor.execute {
            runCatching { block(sessionForGameApi()) }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        error.message ?: "A transferência não foi concluída.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun exportBcat() {
        val id = currentTitleId() ?: return
        withSession { session ->
            val bytes = NextendoAccountExtrasApi.downloadBcatSeed(session.nexGameToken, id)
            if (bytes.isEmpty()) throw IllegalStateException("A Nextendo não retornou um BCAT para esse Title ID.")
            runOnUiThread { prepareExport(bytes, "$id-bcat.zip", PendingExport.BCAT) }
        }
    }

    private fun chooseProfileJpeg() {
        pendingImport = PendingImport.PROFILE_JPEG
        openDocument("image/jpeg")
    }

    private fun chooseMii() {
        pendingImport = PendingImport.MII
        openDocument("*/*")
    }

    private fun openDocument(mimeType: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    private fun prepareExport(bytes: ByteArray, name: String, kind: PendingExport) {
        pendingBytes?.fill(0)
        pendingBytes = bytes
        pendingFileName = name
        pendingExport = kind
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (name.endsWith(".zip")) "application/zip" else "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    @Deprecated("Handled for compatibility with the pinned Eden Activity host.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) {
            clearPending()
            return
        }
        val uri = data.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> writeExport(uri)
            REQUEST_IMPORT -> readImport(uri)
        }
    }

    private fun writeExport(uri: Uri) {
        val bytes = pendingBytes ?: run {
            clearPending()
            return
        }
        val snapshot = bytes.copyOf()
        executor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(snapshot)
                    output.flush()
                } ?: throw IllegalStateException("O Android não abriu o destino escolhido.")
            }
            snapshot.fill(0)
            runOnUiThread {
                result.fold(
                    onSuccess = {
                        Toast.makeText(this, "Arquivo exportado: $pendingFileName", Toast.LENGTH_LONG).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(this, error.message ?: "Falha ao exportar arquivo.", Toast.LENGTH_LONG).show()
                    }
                )
                clearPending()
            }
        }
    }

    private fun readImport(uri: Uri) {
        val kind = pendingImport
        if (kind == PendingImport.NONE) {
            clearPending()
            return
        }
        pendingImport = PendingImport.NONE
        executor.execute {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_IMPORT_BYTES) {
                            "O arquivo selecionado excede o limite de 64 MiB."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw IllegalStateException("Não foi possível ler o arquivo selecionado.")
            }
            bytes.fold(
                onSuccess = { payload ->
                    val result = runCatching {
                        val session = sessionForGameApi()
                        when (kind) {
                            PendingImport.PROFILE_JPEG -> {
                                require(payload.size >= 3 &&
                                    payload[0] == 0xFF.toByte() &&
                                    payload[1] == 0xD8.toByte() &&
                                    payload[2] == 0xFF.toByte()
                                ) { "Selecione uma imagem JPEG válida." }
                                val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
                                NextendoAccountExtrasApi.updateProfile(
                                    gameToken = session.nexGameToken,
                                    imageBase64 = encoded
                                )
                                "Foto do perfil atualizada."
                            }
                            PendingImport.MII -> {
                                require(payload.isNotEmpty() && payload.size <= 128 * 1024) {
                                    "O arquivo Mii está vazio ou excede 128 KiB."
                                }
                                val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
                                NextendoAccountExtrasApi.updateProfile(
                                    gameToken = session.nexGameToken,
                                    miiBase64 = encoded
                                )
                                "Mii atualizado."
                            }
                            PendingImport.NONE -> throw IllegalStateException("Nenhuma operação foi selecionada.")
                        }
                    }
                    payload.fill(0)
                    runOnUiThread { Toast.makeText(this, result.getOrElse { it.message ?: "Falha na transferência." }, Toast.LENGTH_LONG).show() }
                },
                onFailure = { error ->
                    runOnUiThread {
                        Toast.makeText(this, error.message ?: "Não foi possível ler o arquivo.", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    private fun clearPending() {
        pendingBytes?.fill(0)
        pendingBytes = null
        pendingImport = PendingImport.NONE
        pendingExport = PendingExport.NONE
    }

    override fun onDestroy() {
        clearPending()
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_IMPORT = 0x4E31
        private const val REQUEST_EXPORT = 0x4E32
        private const val MAX_IMPORT_BYTES = 64 * 1024 * 1024
    }
}
