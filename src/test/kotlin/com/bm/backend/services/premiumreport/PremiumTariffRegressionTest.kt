package com.bm.backend.services.premiumreport

import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures as F
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 3.0TD regression: the extracted text of the typical and long full documents equals the text rendered by the base
 * commit 6d71b56 (before report-tariff-applicability), stored as test resources. Branding (2026-11-03) changes only
 * the cover title and replaces the closing data-protection page; everything before that page must stay identical.
 */
class PremiumTariffRegressionTest {
    private val renderer = PdfPremiumReportRenderer(F.CLOCK)

    private fun expected(name: String) =
        PremiumTariffRegressionTest::class.java.getResourceAsStream("/report/tariff/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }.trim()

    private fun assertBase(name: String, actual: String) {
        val base = expected(name)
            .replaceFirst("ESTUDIO COMPLETO DE SUMINISTRO ELÉCTRICO", PremiumReportTexts.TITLE)
            .substringBefore("INFORMACIÓN SOBRE PROTECCIÓN DE DATOS")
        assertEquals(base, actual.substringBefore(PremiumInformationTexts.TITLE))
        assertTrue(actual.contains(PremiumInformationTexts.TITLE))
    }

    @Test
    fun `typical 3_0TD document text equals the base`() =
        assertBase("document-30td-typical.txt", PdfInspection(renderer.render(F.documentDataset())).text)

    @Test
    fun `long 3_0TD document text equals the base`() =
        assertBase("document-30td-long.txt", PdfInspection(renderer.render(F.documentDataset(F.documentLongRows(), offers = 15))).text)
}
