package com.bm.backend.services.premiumreport

import com.bm.backend.testing.PremiumReportFixtures as F
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Writes full-document tariff previews to build/premium-report-preview/tariff-*.pdf (synthetic data). */
class PremiumTariffPreviewTest {
    @Test
    fun `write tariff previews`() {
        val dir = File("build/premium-report-preview").apply { mkdirs() }
        val renderer = PdfPremiumReportRenderer(F.CLOCK)
        mapOf(
            "20td-typical" to F.tariffDataset(),
            "20td-zeros" to F.tariffDataset(rows = F.tariff20Rows(zeros = true), zeros = true),
            "30td-typical" to F.documentDataset(),
            "unknown-61td" to F.tariffDataset("6.1TD", "6.1TD"),
            "conflict" to F.tariffDataset("2.0TD", "3.0TD"),
            "inconsistent" to F.tariffDataset(rows = F.tariff20InconsistentRows())
        ).forEach { (name, d) ->
            val file = File(dir, "tariff-$name.pdf")
            file.writeBytes(renderer.render(d))
            assertTrue(file.length() > 0)
        }
    }
}
