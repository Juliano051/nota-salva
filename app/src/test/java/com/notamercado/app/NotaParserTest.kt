package com.notamercado.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class NotaParserTest {

    private val hoje = LocalDate.of(2026, 11, 1)
    private val chave = "26261175224390000182650010001234561123456784"

    @Test
    fun validaChaveECnpj() {
        assertTrue(NotaParser.chaveValida(chave))
        assertFalse(NotaParser.chaveValida(chave.dropLast(1) + "5"))
        assertTrue(NotaParser.cnpjValido("75224390000182"))
        assertFalse(NotaParser.cnpjValido("75224390000183"))
    }

    @Test
    fun cupomSemQrCode() {
        val texto = listOf(
            "SUPERMERCADO BOM PREÇO LTDA",
            "CNPJ: 75.224.390/0001-82 IE: 0123456-78",
            "AV. CAXANGÁ, 1234 - MADALENA",
            "RECIFE - PE",
            "DOCUMENTO AUXILIAR DA NOTA FISCAL DE CONSUMIDOR ELETRÔNICA",
            "001 ARROZ 5KG 1,000 UN X 27,90 27,90",
            "QTD. TOTAL DE ITENS 3",
            "VALOR TOTAL R$ 58,40",
            "Desconto R$ 2,00",
            "Valor a Pagar R$ 56,40",
            "Cartão de Débito 56,40",
            "Emissão: 01/11/2026 10:15:22"
        ).map { LinhaOcr(it, null) }

        val n = NotaParser.extrair(texto, emptyList(), hoje)
        assertEquals(BigDecimal("56.40"), n.total)
        assertEquals(LocalDate.of(2026, 11, 1), n.data)
        assertEquals("SUPERMERCADO BOM PREÇO LTDA", n.estabelecimento)
        assertEquals("75.224.390/0001-82", n.cnpj)
        assertEquals("AV. CAXANGÁ, 1234 - MADALENA - RECIFE - PE", n.endereco)
        assertNull(n.chave)
    }

    @Test
    fun cupomComQrCodeEValorEmOutraColuna() {
        val linhas = listOf(
            LinhaOcr("ATACADAO S.A.", Caixa(0, 0, 400, 30)),
            LinhaOcr("CNPJ: 75.224.390/0001-82", Caixa(0, 40, 400, 70)),
            LinhaOcr("RUA DO PRINCIPE, 100", Caixa(0, 80, 400, 110)),
            LinhaOcr("VALOR TOTAL R$", Caixa(0, 500, 250, 530)),
            LinhaOcr("123,45", Caixa(600, 502, 700, 532)),
            LinhaOcr("01/11/2026 18:20:01", Caixa(0, 600, 300, 630))
        )
        val qr = "http://nfce.sefaz.pe.gov.br/nfce/consulta?p=$chave|2|1|1|ABCDEF0123"
        val n = NotaParser.extrair(linhas, listOf(qr), hoje)
        assertEquals(BigDecimal("123.45"), n.total)
        assertEquals(LocalDate.of(2026, 11, 1), n.data)
        assertEquals(chave, n.chave)
        assertEquals("75.224.390/0001-82", n.cnpj)
        assertEquals("ATACADAO S.A", n.estabelecimento)
        assertEquals("RUA DO PRINCIPE, 100", n.endereco)
    }

    @Test
    fun qrCodeOfflineTrazTotal() {
        val d = NotaParser.lerQr("https://x/consulta?p=$chave|2|1|01|56.40|4a6f|1|HASH")!!
        assertEquals(chave, d.chave)
        assertEquals(BigDecimal("56.40"), d.total)
        assertEquals(1, d.dia)
    }
}
