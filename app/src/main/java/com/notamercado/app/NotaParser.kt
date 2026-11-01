package com.notamercado.app

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLDecoder
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Retângulo de uma linha de texto na foto (em pixels). */
data class Caixa(val esq: Int, val topo: Int, val dir: Int, val base: Int) {
    val centroY: Int get() = (topo + base) / 2
    val altura: Int get() = (base - topo).coerceAtLeast(1)
}

data class LinhaOcr(val texto: String, val caixa: Caixa?)

data class NotaExtraida(
    val data: LocalDate?,
    val estabelecimento: String?,
    val cnpj: String?,
    val endereco: String?,
    val total: BigDecimal?,
    val chave: String?,
    val avisos: List<String>
)

data class DadosQr(val chave: String?, val total: BigDecimal?, val data: LocalDate?, val dia: Int?)

/**
 * Extrai os dados de um cupom/NFC-e de supermercado a partir do texto reconhecido (OCR)
 * e, quando houver, do QR Code da NFC-e. O QR Code é mais confiável que o OCR, então
 * os dados dele têm prioridade.
 */
object NotaParser {

    // 45,90 | 1.234,56 | 45.90 (OCR às vezes troca vírgula por ponto)
    private val DINHEIRO = Regex("""(\d{1,3}(?:\.\d{3})+|\d+)\s?[,.]\s?(\d{2})(?!\d)""")
    private val DATA = Regex("""(?<!\d)(\d{2})\s?[/.-]\s?(\d{2})\s?[/.-]\s?(\d{4}|\d{2})(?!\d)""")
    private val CNPJ_NUM = Regex("""(?<![\dA-Z])([\dA-Z]{2})[.,\s]?([\dA-Z]{3})[.,\s]?([\dA-Z]{3})\s?[/|]?\s?([\dA-Z]{4})\s?[-.]?\s?(\d{2})(?!\d)""")
    private val CNPJ_SO_DIGITOS = Regex("""(?<!\d)(\d{2})[.,]?(\d{3})[.,]?(\d{3})\s?/\s?(\d{4})\s?-\s?(\d{2})(?!\d)""")

    private val ROTULOS_TOTAL = listOf(
        Regex("""VALOR\s*A\s*PAGAR"""),
        Regex("""VALOR\s*TOTAL"""),
        Regex("""TOTAL\s*A\s*PAGAR"""),
        Regex("""\bTOTAL\b""")
    )
    private val NAO_E_TOTAL = Regex("""ITENS|ITEM|TRIBUT|IMPOSTO|LEI\s*12|DESCONTO|ACRESC|TROCO|QTD|APROX|FEDERA|ESTADUA|MUNICIPA|IBPT""")
    private val NAO_E_VALOR_DA_COMPRA = Regex("""TROCO|DINHEIRO|PAGO|RECEBIDO|TRIBUT|IMPOSTO|LEI\s*12|IBPT|DESCONTO""")

    private val RUIDO_CABECALHO = Regex("""CNPJ|\bI\.?E\.?\b|INSCRI|DOCUMENTO|DANFE|NFC|NF-E|CUPOM|EXTRATO|CONSUMIDOR|AUXILIAR|ELETRONIC|FISCAL|\bSAT\b|CPF|PROTOCOLO|CHAVE|CONSULT|EMISS|SERIE|NUMERO""")
    private val INICIO_ENDERECO = Regex("""^(RUA|AVENIDA|AV|RODOVIA|ROD|ESTRADA|ESTR|PRACA|PCA|PC|TRAVESSA|TRAV|TV|ALAMEDA|AL|LARGO|LGO|R)[.:\s]""")
    private val PISTA_ENDERECO = Regex("""\bCEP\b|BAIRRO|\b\d{5}-?\d{3}\b|,\s*\d{1,5}\b|\bN[O°º]?\.?\s*\d{1,5}\b|\b(BR|PE|PB|AL|BA|CE|RN)\s?-\s?\d{3}\b""")
    private val CIDADE_UF = Regex("""^[A-Z .'-]{3,}\s*[-,/]\s*[A-Z]{2}$""")
    private val FIM_CABECALHO = Regex("""DOCUMENTO\s*AUXILIAR|DANFE|CODIGO|DESCRI|\bITEM\b|\bQTD""")

    fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .uppercase()

    fun extrair(
        linhasBrutas: List<LinhaOcr>,
        qrCodes: List<String>,
        hoje: LocalDate = LocalDate.now()
    ): NotaExtraida {
        val avisos = mutableListOf<String>()
        val linhas = linhasBrutas
            .filter { it.texto.isNotBlank() }
            .let { l -> if (l.all { it.caixa != null }) l.sortedBy { it.caixa!!.centroY } else l }

        val qr = qrCodes.firstNotNullOfOrNull { lerQr(it) }
        val chave = qr?.chave ?: acharChaveNoTexto(linhas)

        // ---- Total
        var total = qr?.total
        if (total == null) total = acharTotal(linhas)
        if (total == null) {
            total = maiorValor(linhas)
            if (total != null) avisos += "Total estimado pelo maior valor da nota — confira."
        }

        // ---- Data
        var data = qr?.data ?: acharData(linhas, hoje, chave)
        if (data == null && chave != null && qr?.dia != null) {
            data = runCatching { anoMesDaChave(chave)?.atDay(qr.dia) }.getOrNull()
        }

        // ---- CNPJ, estabelecimento e endereço
        val cnpjChave = chave?.substring(6, 20)?.takeIf { cnpjValido(it) }
        val cnpj = cnpjChave ?: acharCnpj(linhas)
        val (loja, endereco) = acharLojaEEndereco(linhas)

        if (chave == null) avisos += "QR Code não lido — dados vieram só do texto da foto."
        if (data == null) avisos += "Data não encontrada."
        if (loja == null) avisos += "Estabelecimento não encontrado."

        return NotaExtraida(
            data = data,
            estabelecimento = loja?.let { limparNome(it) },
            cnpj = cnpj?.let { formatarCnpj(it) },
            endereco = endereco,
            total = total?.setScale(2, RoundingMode.HALF_UP),
            chave = chave,
            avisos = avisos
        )
    }

    // ------------------------------------------------------------------ QR Code

    fun lerQr(raw: String): DadosQr? {
        val bruto = try { URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }

        // Versões 2 e 3 do QR da NFC-e: ...?p=CHAVE|versao|ambiente|...
        Regex("""[?&]p=([^&\s]+)""", RegexOption.IGNORE_CASE).find(bruto)?.let { m ->
            val partes = m.groupValues[1].split('|')
            val ch = partes[0].uppercase().filter { it.isLetterOrDigit() }.takeIf { chaveValida(it) }
            var total: BigDecimal? = null
            var dia: Int? = null
            // Emissão em contingência (offline) traz dia e valor da nota no próprio QR
            if (partes.size >= 6 && Regex("""\d+\.\d{2}""").matches(partes[4])) {
                dia = partes[3].toIntOrNull()?.takeIf { it in 1..31 }
                total = partes[4].toBigDecimalOrNull()
            }
            return DadosQr(ch, total, null, dia)
        }

        // Versão 1: chNFe=...&vNF=...&dhEmi=<data em hexadecimal>
        Regex("""chNFe=([0-9A-Za-z]{44})""", RegexOption.IGNORE_CASE).find(bruto)?.let { m ->
            val ch = m.groupValues[1].uppercase().takeIf { chaveValida(it) }
            val total = Regex("""vNF=([\d.]+)""").find(bruto)?.groupValues?.get(1)?.toBigDecimalOrNull()
            val data = Regex("""dhEmi=([0-9a-fA-F]+)""").find(bruto)?.groupValues?.get(1)?.let { hex ->
                runCatching {
                    val texto = String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                    LocalDate.parse(texto.take(10))
                }.getOrNull()
            }
            return DadosQr(ch, total, data, null)
        }

        // Só a chave de acesso
        val so = Regex("""[0-9A-Z]{44}""").find(bruto.uppercase().replace(" ", ""))?.value
        return so?.takeIf { chaveValida(it) }?.let { DadosQr(it, null, null, null) }
    }

    private fun acharChaveNoTexto(linhas: List<LinhaOcr>): String? {
        val normas = linhas.map { normalizar(it.texto) }
        for (i in normas.indices) {
            if (!normas[i].contains("CHAVE")) continue
            val trecho = (i..min(i + 3, normas.lastIndex)).joinToString("") { j ->
                normas[j].substringAfter("ACESSO").filter { it.isDigit() }
            }
            for (k in 0..(trecho.length - 44).coerceAtLeast(-1)) {
                val c = trecho.substring(k, k + 44)
                if (chaveValida(c)) return c
            }
        }
        return null
    }

    /** Dígito verificador da chave de acesso (módulo 11). Aceita CNPJ alfanumérico. */
    fun chaveValida(c: String): Boolean {
        if (c.length != 44 || !c.all { it.isDigit() || it in 'A'..'Z' } || !c[43].isDigit()) return false
        var peso = 2
        var soma = 0
        for (i in 42 downTo 0) {
            soma += (c[i] - '0') * peso
            peso = if (peso == 9) 2 else peso + 1
        }
        val resto = soma % 11
        val dv = if (resto < 2) 0 else 11 - resto
        return dv == c[43] - '0'
    }

    private fun anoMesDaChave(chave: String): YearMonth? = runCatching {
        YearMonth.of(2000 + chave.substring(2, 4).toInt(), chave.substring(4, 6).toInt())
    }.getOrNull()

    // ------------------------------------------------------------------ Total

    private fun acharTotal(linhas: List<LinhaOcr>): BigDecimal? {
        val normas = linhas.map { normalizar(it.texto) }
        for (rotulo in ROTULOS_TOTAL) {
            for (i in linhas.indices) {
                val n = normas[i]
                val m = rotulo.find(n) ?: continue
                if (NAO_E_TOTAL.containsMatchIn(n)) continue
                valorPerto(linhas, normas, i, m.range.last + 1)?.let { return it }
            }
        }
        return null
    }

    private fun valorPerto(linhas: List<LinhaOcr>, normas: List<String>, i: Int, fimRotulo: Int): BigDecimal? {
        // 1) Na mesma linha, depois do rótulo
        DINHEIRO.findAll(normas[i].substring(fimRotulo)).lastOrNull()?.let { v -> paraDecimal(v)?.let { return it } }

        // 2) Na mesma "altura" da foto, à direita (o valor costuma ficar em outra coluna)
        val c = linhas[i].caixa
        if (c != null) {
            val mesmaAltura = linhas.indices
                .filter { j ->
                    val o = linhas[j].caixa
                    j != i && o != null && o.esq >= c.esq &&
                        abs(o.centroY - c.centroY) < max(c.altura, o.altura) * 0.6
                }
                .sortedBy { linhas[it].caixa!!.esq }
            for (j in mesmaAltura) {
                if (NAO_E_TOTAL.containsMatchIn(normas[j])) continue
                DINHEIRO.findAll(normas[j]).lastOrNull()?.let { v -> paraDecimal(v)?.let { return it } }
            }
        }

        // 3) Nas linhas logo abaixo
        for (j in (i + 1)..min(i + 2, linhas.lastIndex)) {
            if (Regex("[A-Z]{4,}").containsMatchIn(normas[j])) continue
            DINHEIRO.find(normas[j])?.let { v -> paraDecimal(v)?.let { return it } }
        }
        return null
    }

    private fun maiorValor(linhas: List<LinhaOcr>): BigDecimal? =
        linhas.map { normalizar(it.texto) }
            .filterNot { NAO_E_VALOR_DA_COMPRA.containsMatchIn(it) }
            .flatMap { n -> DINHEIRO.findAll(n).mapNotNull { paraDecimal(it) }.toList() }
            .filter { it < BigDecimal(100000) }
            .maxOrNull()

    private fun paraDecimal(m: MatchResult): BigDecimal? {
        val inteiro = m.groupValues[1].replace(".", "")
        val v = "$inteiro.${m.groupValues[2]}".toBigDecimalOrNull() ?: return null
        return v.takeIf { it > BigDecimal.ZERO && it < BigDecimal(1000000) }
    }

    // ------------------------------------------------------------------ Data

    private fun acharData(linhas: List<LinhaOcr>, hoje: LocalDate, chave: String?): LocalDate? {
        val anoMes = chave?.let { anoMesDaChave(it) }
        data class Candidata(val data: LocalDate, val pontos: Int)

        val candidatas = mutableListOf<Candidata>()
        for (linha in linhas) {
            val n = normalizar(linha.texto)
            for (m in DATA.findAll(n)) {
                val dia = m.groupValues[1].toInt()
                val mes = m.groupValues[2].toInt()
                var ano = m.groupValues[3].toInt()
                if (ano < 100) ano += 2000
                val d = runCatching { LocalDate.of(ano, mes, dia) }.getOrNull() ?: continue
                if (d.isAfter(hoje.plusDays(1)) || d.isBefore(hoje.minusYears(2))) continue
                var pontos = 0
                if (Regex("""EMISS|\bDATA\b|\bDT\b""").containsMatchIn(n)) pontos += 2
                if (anoMes != null && YearMonth.from(d) == anoMes) pontos += 3
                if (Regex("""VALID|VENC|NASC|AUTORIZ""").containsMatchIn(n)) pontos -= 3
                candidatas += Candidata(d, pontos)
            }
        }
        return candidatas.maxByOrNull { it.pontos }?.data
    }

    // ------------------------------------------------------------------ CNPJ

    private fun acharCnpj(linhas: List<LinhaOcr>): String? {
        val normas = linhas.map { normalizar(it.texto) }
        // Primeiro nas linhas que dizem "CNPJ"
        for (n in normas.filter { it.contains("CNPJ") }) {
            val texto = n.substringAfter("CNPJ")
            for (m in CNPJ_NUM.findAll(texto)) {
                val c = m.groupValues.drop(1).joinToString("").replace('O', '0')
                if (cnpjValido(c)) return c
            }
        }
        // Depois em qualquer linha, só com o formato completo 00.000.000/0000-00
        for (n in normas) {
            if (n.contains("CPF") || n.contains("CONSUMIDOR")) continue
            for (m in CNPJ_SO_DIGITOS.findAll(n)) {
                val c = m.groupValues.drop(1).joinToString("")
                if (cnpjValido(c)) return c
            }
        }
        return null
    }

    /** Valida CNPJ numérico ou alfanumérico (regra da Receita: valor = código ASCII - 48). */
    fun cnpjValido(c: String): Boolean {
        if (c.length != 14 || c.toSet().size == 1) return false
        if (!c.substring(0, 12).all { it.isDigit() || it in 'A'..'Z' } || !c.substring(12).all { it.isDigit() }) return false
        fun dv(base: String): Int {
            val pesos = if (base.length == 12) intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
            else intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
            val soma = base.indices.sumOf { (base[it] - '0') * pesos[it] }
            val r = soma % 11
            return if (r < 2) 0 else 11 - r
        }
        val d1 = dv(c.substring(0, 12))
        val d2 = dv(c.substring(0, 12) + d1)
        return c[12] - '0' == d1 && c[13] - '0' == d2
    }

    fun formatarCnpj(c: String): String =
        "${c.substring(0, 2)}.${c.substring(2, 5)}.${c.substring(5, 8)}/${c.substring(8, 12)}-${c.substring(12)}"

    // ------------------------------------------------------------------ Estabelecimento e endereço

    private fun letras(s: String) = s.count { it.isLetter() }

    private fun ehEndereco(n: String) = INICIO_ENDERECO.containsMatchIn(n) || PISTA_ENDERECO.containsMatchIn(n)

    private fun pareceNome(original: String): Boolean {
        val n = normalizar(original)
        return letras(n) >= 3 && !RUIDO_CABECALHO.containsMatchIn(n) && !ehEndereco(n) &&
            !DINHEIRO.containsMatchIn(n) && !DATA.containsMatchIn(n)
    }

    private fun acharLojaEEndereco(linhas: List<LinhaOcr>): Pair<String?, String?> {
        if (linhas.isEmpty()) return null to null
        val normas = linhas.map { normalizar(it.texto) }
        val limite = min(normas.size, 15)
        val iCnpj = (0 until limite).firstOrNull { normas[it].contains("CNPJ") }

        var loja: String? = null
        if (iCnpj != null) {
            // Alguns cupons trazem "CNPJ: 00.000.000/0000-00 NOME DA LOJA" na mesma linha
            val resto = linhas[iCnpj].texto
                .replace(Regex("""(?i)\bI\.?\s?E\.?\s*:?\s*[\d./\-]+"""), " ")
                .replace(Regex("""(?i)CNPJ\s*:?"""), " ")
                .replace(Regex("""[\dA-Z]{2}[.,]?[\dA-Z]{3}[.,]?[\dA-Z]{3}\s?/\s?[\dA-Z]{4}\s?-?\s?\d{2}"""), " ")
                .replace(Regex("""[\d./\-]{6,}"""), " ")
                .trim().trim('-', ':', ' ', '|')
            if (letras(resto) >= 4 && pareceNome(resto)) loja = resto
            if (loja == null) {
                loja = (iCnpj - 1 downTo max(0, iCnpj - 3))
                    .map { linhas[it].texto.trim() }
                    .firstOrNull { pareceNome(it) }
            }
        }
        if (loja == null) {
            loja = (0 until min(5, linhas.size)).map { linhas[it].texto.trim() }.firstOrNull { pareceNome(it) }
        }

        // Endereço: linhas logo depois do CNPJ (ou no topo do cupom)
        val partes = mutableListOf<String>()
        val inicio = if (iCnpj != null) iCnpj + 1 else 0
        for (j in inicio until min(inicio + 6, linhas.size)) {
            val n = normas[j]
            if (partes.isNotEmpty() && FIM_CABECALHO.containsMatchIn(n)) break
            val t = linhas[j].texto.trim()
            if (t == loja) continue
            val ehCidade = partes.isNotEmpty() && CIDADE_UF.matches(n.trim())
            if (n.contains("TOTAL")) break
            if (letras(n) < 3) continue
            if ((ehEndereco(n) && !n.contains("CNPJ") && !n.contains("CPF")) || ehCidade) {
                partes += t
                if (partes.size == 3) break
            }
        }
        // Endereço às vezes vem antes do CNPJ
        if (partes.isEmpty() && iCnpj != null) {
            for (j in max(0, iCnpj - 3) until iCnpj) {
                val t = linhas[j].texto.trim()
                if (t != loja && ehEndereco(normas[j])) partes += t
            }
        }
        val endereco = partes.joinToString(" - ").replace(Regex("\\s+"), " ").ifBlank { null }
        return loja to endereco
    }

    private fun limparNome(s: String): String =
        s.replace(Regex("\\s+"), " ").trim().trim('-', ':', '.', '|', ' ')
}
