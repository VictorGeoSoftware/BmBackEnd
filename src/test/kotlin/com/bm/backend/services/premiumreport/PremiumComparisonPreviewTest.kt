package com.bm.backend.services.premiumreport

import com.bm.backend.services.ComparatorReportPdfService
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.ReportComparisonFixtures as F
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Writes Premium comparison previews and the Basic PDF of the same request to build/premium-report-preview/. */
class PremiumComparisonPreviewTest {
    @Test
    fun `write comparison page previews`() {
        val dir = File("build/premium-report-preview").apply { mkdirs() }
        val cases = mapOf(
            "a" to F.pageRequest(), "b" to F.pageScenarioB(), "c" to F.pageScenarioC(), "d" to F.pageScenarioD(),
            "e" to F.pageScenarioOffers(7), "f8" to F.pageScenarioOffers(8), "f15" to F.pageScenarioOffers(15),
            "g" to F.pageScenarioG(), "h" to F.pageScenarioH()
        )
        cases.forEach { (name, c) ->
            val premium = File(dir, "comparison-$name.pdf")
            premium.writeBytes(PremiumReportPdf.render(listOf(PremiumComparisonSection()), PremiumReportFixtures.dataset().copy(comparison = c)))
            val basic = File(dir, "comparison-$name-basic.pdf")
            basic.writeBytes(ComparatorReportPdfService().generate(c))
            assertTrue(premium.length() > 0 && basic.length() > 0)
        }
    }
}
