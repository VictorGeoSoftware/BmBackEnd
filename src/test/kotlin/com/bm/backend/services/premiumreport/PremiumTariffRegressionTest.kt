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

    /**
     * The history pages were reworked by feedback 2026-11-03 (report-history-pages-revision), so only the cover
     * (up to the active page) and the comparison pages are compared with the base; the rest has dedicated tests.
     */
    private fun assertBase(name: String, actual: String) {
        val base = expected(name).replaceFirst("ESTUDIO COMPLETO DE SUMINISTRO ELÉCTRICO", PremiumReportTexts.TITLE)
        val cover = { t: String -> t.substringBefore(PremiumActiveTexts.HISTORY_AVAILABLE) }
        // Page numbers move with the reworked history pages; the comparison content itself must not change.
        val comparison = { t: String ->
            COMPARISON + t.substringAfter(COMPARISON).substringBefore("INFORMACIÓN ").replace(Regex("Página \\d+ de \\d+"), "")
        }
        assertEquals(cover(base), cover(actual))
        assertTrue(base.contains(COMPARISON))
        assertEquals(comparison(base), comparison(actual))
        assertTrue(actual.contains(PremiumInformationTexts.TITLE))
    }

    private companion object {
        const val COMPARISON = "COMPARATIVO ANUAL"
    }

    @Test
    fun `typical 3_0TD document text equals the base`() =
        assertBase("document-30td-typical.txt", PdfInspection(renderer.render(F.documentDataset())).text)

    @Test
    fun `long 3_0TD document text equals the base`() =
        assertBase("document-30td-long.txt", PdfInspection(renderer.render(F.documentDataset(F.documentLongRows(), offers = 15))).text)
}
