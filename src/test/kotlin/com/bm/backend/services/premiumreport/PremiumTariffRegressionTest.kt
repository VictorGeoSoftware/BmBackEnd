package com.bm.backend.services.premiumreport

import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures as F
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 3.0TD regression: the extracted text of the typical and long full documents equals the text rendered by the base
 * commit 6d71b56 (before report-tariff-applicability), stored as test resources.
 */
class PremiumTariffRegressionTest {
    private val renderer = PdfPremiumReportRenderer(F.CLOCK)

    private fun expected(name: String) =
        PremiumTariffRegressionTest::class.java.getResourceAsStream("/report/tariff/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }.trim()

    @Test
    fun `typical 3_0TD document text equals the base`() =
        assertEquals(expected("document-30td-typical.txt"), PdfInspection(renderer.render(F.documentDataset())).text)

    @Test
    fun `long 3_0TD document text equals the base`() =
        assertEquals(expected("document-30td-long.txt"), PdfInspection(renderer.render(F.documentDataset(F.documentLongRows(), offers = 15))).text)
}
