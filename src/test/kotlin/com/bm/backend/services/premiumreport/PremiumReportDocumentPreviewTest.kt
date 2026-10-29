package com.bm.backend.services.premiumreport

import com.bm.backend.testing.PremiumReportFixtures as F
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Writes full assembled Premium report previews to build/premium-report-preview/document-*.pdf. */
class PremiumReportDocumentPreviewTest {
    @Test
    fun `write document previews`() {
        val dir = File("build/premium-report-preview").apply { mkdirs() }
        val renderer = PdfPremiumReportRenderer(F.CLOCK)
        mapOf(
            "a-typical" to F.documentDataset(),
            "b-unavailable" to F.documentUnavailableDataset(),
            "c-many-offers" to F.documentDataset(offers = 15),
            "d-long" to F.documentDataset(F.documentLongRows(), offers = 15),
            "e-no-reactive-demand" to F.documentDataset(F.documentActiveOnlyRows())
        ).forEach { (name, d) ->
            val file = File(dir, "document-$name.pdf")
            file.writeBytes(renderer.render(d))
            assertTrue(file.length() > 0)
        }
    }
}
