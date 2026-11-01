package com.notamercado.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val ptBR = Locale("pt", "BR")
    private val fmtData = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val moeda = NumberFormat.getCurrencyInstance(ptBR)
    private val tipoJson = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val reconhecedor by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val leitorQr by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tvMesTitulo: TextView
    private lateinit var tvMesTotal: TextView
    private lateinit var tvMesInfo: TextView
    private lateinit var btnFoto: MaterialButton
    private lateinit var btnGaleria: MaterialButton
    private lateinit var btnSalvar: MaterialButton
    private lateinit var progresso: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var imgNota: ImageView
    private lateinit var tvAvisos: TextView
    private lateinit var tilData: TextInputLayout
    private lateinit var tilLocal: TextInputLayout
    private lateinit var tilTotal: TextInputLayout
    private lateinit var etData: TextInputEditText
    private lateinit var etLocal: TextInputEditText
    private lateinit var etCnpj: TextInputEditText
    private lateinit var etEndereco: TextInputEditText
    private lateinit var etTotal: TextInputEditText

    private var fotoPendente: Uri? = null
    private var imagemAtual: Uri? = null
    private var imagemReduzida: Bitmap? = null
    private var chave: String? = null

    private val tirarFoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = fotoPendente
        if (ok && uri != null) processar(uri)
    }

    private val escolherImagem = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) processar(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("config", MODE_PRIVATE)

        tvMesTitulo = findViewById(R.id.tvMesTitulo)
        tvMesTotal = findViewById(R.id.tvMesTotal)
        tvMesInfo = findViewById(R.id.tvMesInfo)
        btnFoto = findViewById(R.id.btnFoto)
        btnGaleria = findViewById(R.id.btnGaleria)
        btnSalvar = findViewById(R.id.btnSalvar)
        progresso = findViewById(R.id.progresso)
        tvStatus = findViewById(R.id.tvStatus)
        imgNota = findViewById(R.id.imgNota)
        tvAvisos = findViewById(R.id.tvAvisos)
        tilData = findViewById(R.id.tilData)
        tilLocal = findViewById(R.id.tilLocal)
        tilTotal = findViewById(R.id.tilTotal)
        etData = findViewById(R.id.etData)
        etLocal = findViewById(R.id.etLocal)
        etCnpj = findViewById(R.id.etCnpj)
        etEndereco = findViewById(R.id.etEndereco)
        etTotal = findViewById(R.id.etTotal)

        savedInstanceState?.let { s ->
            fotoPendente = s.getString("fotoPendente")?.let(Uri::parse)
            imagemAtual = s.getString("imagemAtual")?.let(Uri::parse)
            chave = s.getString("chave")
        }

        btnFoto.setOnClickListener { abrirCamera() }
        btnGaleria.setOnClickListener { escolherImagem.launch("image/*") }
        btnSalvar.setOnClickListener { salvar() }

        if (savedInstanceState == null) {
            if (etData.text.isNullOrEmpty()) etData.setText(LocalDate.now().format(fmtData))
            val compartilhada = if (intent?.action == Intent.ACTION_SEND)
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) else null
            if (compartilhada != null) processar(compartilhada)
            if (url().isBlank()) abrirConfiguracoes()
        }
    }

    override fun onResume() {
        super.onResume()
        atualizarResumo()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("fotoPendente", fotoPendente?.toString())
        outState.putString("imagemAtual", imagemAtual?.toString())
        outState.putString("chave", chave)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { reconhecedor.close() }
        runCatching { leitorQr.close() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.acao_config -> { abrirConfiguracoes(); true }
        R.id.acao_planilha -> { abrirPlanilha(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------ Captura e leitura

    private fun abrirCamera() {
        val pasta = File(cacheDir, "fotos").apply { mkdirs() }
        val umaSemana = 7L * 24 * 3600 * 1000
        pasta.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > umaSemana }?.forEach { it.delete() }
        val arquivo = File(pasta, "nota_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", arquivo)
        fotoPendente = uri
        try {
            tirarFoto.launch(uri)
        } catch (e: ActivityNotFoundException) {
            erro("Nenhum app de câmera encontrado.")
        }
    }

    private fun processar(uri: Uri) {
        imagemAtual = uri
        limparCampos()
        ocupado(true, "Lendo a nota…")
        lifecycleScope.launch {
            try {
                val reduzida = withContext(Dispatchers.IO) { ImagemUtil.reduzir(this@MainActivity, uri) }
                imagemReduzida = reduzida
                imgNota.setImageBitmap(reduzida)
                imgNota.isVisible = true

                val (linhas, qrs) = withContext(Dispatchers.Default) { lerImagem(uri) }
                if (linhas.isEmpty() && qrs.isEmpty()) {
                    erro("Não encontrei texto na foto. Tente de novo com mais luz e a nota bem esticada.")
                    return@launch
                }
                preencher(NotaParser.extrair(linhas, qrs))
            } catch (e: Exception) {
                erro("Não consegui ler a imagem: ${e.message}")
            } finally {
                ocupado(false)
            }
        }
    }

    /** Roda OCR e leitura de QR Code no próprio aparelho (precisa rodar fora da thread principal). */
    private fun lerImagem(uri: Uri): Pair<List<LinhaOcr>, List<String>> {
        val imagem = InputImage.fromFilePath(this, uri)
        val texto = Tasks.await(reconhecedor.process(imagem))
        val linhas = texto.textBlocks.flatMap { bloco ->
            bloco.lines.map { l ->
                LinhaOcr(l.text, l.boundingBox?.let { Caixa(it.left, it.top, it.right, it.bottom) })
            }
        }
        val qrs = runCatching {
            Tasks.await(leitorQr.process(imagem)).mapNotNull { it.rawValue }
        }.getOrDefault(emptyList())
        return linhas to qrs
    }

    private fun preencher(n: NotaExtraida) {
        chave = n.chave
        etData.setText(n.data?.format(fmtData) ?: "")
        etLocal.setText(n.estabelecimento ?: "")
        etCnpj.setText(n.cnpj ?: "")
        etEndereco.setText(n.endereco ?: "")
        etTotal.setText(n.total?.let { formatarValor(it) } ?: "")

        if (n.data == null) tilData.error = "Não encontrei — preencha"
        if (n.estabelecimento == null) tilLocal.error = "Não encontrei — preencha"
        if (n.total == null) tilTotal.error = "Não encontrei — preencha"

        val avisos = n.avisos.toMutableList()
        if (n.chave != null) avisos.add(0, "NFC-e identificada pelo QR Code ✔")
        avisos += "Confira os campos antes de gravar."
        tvAvisos.text = avisos.joinToString("\n") { "• $it" }
        tvAvisos.isVisible = true
    }

    // ------------------------------------------------------------------ Gravação

    private fun salvar(forcar: Boolean = false) {
        val url = url()
        if (url.isBlank()) { abrirConfiguracoes(); return }
        limparErros()

        val data = runCatching { LocalDate.parse(etData.text.toString().trim(), fmtData) }.getOrNull()
        if (data == null) { tilData.error = "Use o formato dd/mm/aaaa"; return }
        val total = lerValor(etTotal.text.toString())
        if (total == null) { tilTotal.error = "Valor inválido"; return }
        val local = etLocal.text.toString().trim()
        if (local.isEmpty()) { tilLocal.error = "Informe o estabelecimento"; return }

        ocupado(true, "Gravando na planilha…")
        lifecycleScope.launch {
            try {
                var foto: String? = null
                if (enviarFoto()) {
                    foto = withContext(Dispatchers.IO) {
                        runCatching {
                            val bmp = imagemReduzida ?: imagemAtual?.let { ImagemUtil.reduzir(this@MainActivity, it) }
                            bmp?.let { ImagemUtil.paraBase64Jpeg(it) }
                        }.getOrNull()
                    }
                }
                val corpo = JSONObject().apply {
                    put("token", token())
                    put("data", data.format(fmtData))
                    put("estabelecimento", local)
                    put("cnpj", etCnpj.text.toString().trim())
                    put("endereco", etEndereco.text.toString().trim())
                    put("total", total.toPlainString())
                    put("chave", chave ?: "")
                    put("forcar", forcar)
                    if (foto != null) put("fotoBase64", foto)
                }
                val req = Request.Builder().url(url).post(corpo.toString().toRequestBody(tipoJson)).build()
                val resp = withContext(Dispatchers.IO) { chamar(req) }
                when {
                    resp.optBoolean("ok") -> {
                        mostrarResumo(resp)
                        Toast.makeText(this@MainActivity, "Nota gravada na planilha!", Toast.LENGTH_LONG).show()
                        novaNota()
                    }
                    resp.optBoolean("duplicata") -> AlertDialog.Builder(this@MainActivity)
                        .setTitle("Nota repetida?")
                        .setMessage(resp.optString("msg"))
                        .setPositiveButton("Gravar mesmo assim") { _, _ -> salvar(forcar = true) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                    else -> erro(resp.optString("msg", "Erro desconhecido ao gravar."))
                }
            } catch (e: IOException) {
                erro(e.message ?: "Falha de conexão.")
            } finally {
                ocupado(false)
            }
        }
    }

    private fun chamar(req: Request): JSONObject {
        http.newCall(req).execute().use { r ->
            val corpo = r.body?.string().orEmpty()
            try {
                return JSONObject(corpo)
            } catch (e: JSONException) {
                throw IOException(
                    if (corpo.contains("<html", ignoreCase = true))
                        "O Apps Script não devolveu dados. Confira se a implantação está com acesso \"Qualquer pessoa\" e se a URL termina em /exec."
                    else "Resposta inesperada da planilha (HTTP ${r.code})."
                )
            }
        }
    }

    private fun atualizarResumo() {
        val base = url().toHttpUrlOrNull()
        if (base == null) {
            tvMesTitulo.text = "Mercado no mês"
            tvMesTotal.text = "—"
            tvMesInfo.text = "Toque em ⋮ › Conectar planilha"
            return
        }
        val u = base.newBuilder()
            .addQueryParameter("token", token())
            .addQueryParameter("mes", YearMonth.now().toString())
            .build()
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { chamar(Request.Builder().url(u).get().build()) }
                if (r.optBoolean("ok")) mostrarResumo(r) else tvMesInfo.text = r.optString("msg")
            } catch (e: IOException) {
                tvMesInfo.text = "Erro: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun mostrarResumo(r: JSONObject) {
        val ym = runCatching { YearMonth.parse(r.optString("mes")) }.getOrElse { YearMonth.now() }
        val nomeMes = ym.format(DateTimeFormatter.ofPattern("MMMM 'de' yyyy", ptBR))
        tvMesTitulo.text = "Mercado em $nomeMes"
        tvMesTotal.text = moeda.format(r.optDouble("totalMes", 0.0))
        val n = r.optInt("compras")
        tvMesInfo.text = if (n == 1) "1 compra lançada" else "$n compras lançadas"
        r.optString("planilha").takeIf { it.startsWith("http") }?.let {
            prefs.edit().putString("planilhaUrl", it).apply()
        }
    }

    // ------------------------------------------------------------------ Configuração

    private fun url() = prefs.getString("url", "").orEmpty()
    private fun token() = prefs.getString("token", "").orEmpty()
    private fun enviarFoto() = prefs.getBoolean("foto", true)

    private fun abrirConfiguracoes() {
        val v = layoutInflater.inflate(R.layout.dialog_config, null)
        val etUrl = v.findViewById<TextInputEditText>(R.id.etUrl)
        val etToken = v.findViewById<TextInputEditText>(R.id.etToken)
        val cbFoto = v.findViewById<CheckBox>(R.id.cbFoto)
        etUrl.setText(url())
        etToken.setText(token())
        cbFoto.isChecked = enviarFoto()
        AlertDialog.Builder(this)
            .setTitle("Conectar à planilha")
            .setView(v)
            .setPositiveButton("Salvar") { _, _ ->
                prefs.edit()
                    .putString("url", etUrl.text.toString().trim())
                    .putString("token", etToken.text.toString().trim())
                    .putBoolean("foto", cbFoto.isChecked)
                    .apply()
                atualizarResumo()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun abrirPlanilha() {
        val link = prefs.getString("planilhaUrl", null)
        if (link == null) { erro("Grave uma nota (ou reabra o app) para eu descobrir o link da planilha."); return }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    }

    // ------------------------------------------------------------------ Utilidades de tela

    private fun ocupado(sim: Boolean, msg: String = "") {
        progresso.isVisible = sim
        tvStatus.isVisible = sim
        tvStatus.text = msg
        btnFoto.isEnabled = !sim
        btnGaleria.isEnabled = !sim
        btnSalvar.isEnabled = !sim
    }

    private fun erro(msg: String) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun limparErros() {
        tilData.error = null; tilLocal.error = null; tilTotal.error = null
    }

    private fun limparCampos() {
        limparErros()
        chave = null
        listOf(etData, etLocal, etCnpj, etEndereco, etTotal).forEach { it.setText("") }
        tvAvisos.isVisible = false
    }

    private fun novaNota() {
        limparCampos()
        etData.setText(LocalDate.now().format(fmtData))
        imagemAtual = null
        imagemReduzida = null
        imgNota.setImageDrawable(null)
        imgNota.isVisible = false
    }

    private fun formatarValor(v: BigDecimal) = String.format(ptBR, "%.2f", v)

    private fun lerValor(s: String): BigDecimal? {
        val t = s.trim().replace("R$", "").replace(" ", "")
        if (t.isEmpty()) return null
        val normal = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
        return normal.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
            ?.setScale(2, RoundingMode.HALF_UP)
    }
}
