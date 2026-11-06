package com.bm.backend.services.premiumreport

import com.bm.backend.models.StudyHistory
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.activeDataset
import com.bm.backend.testing.PremiumReportFixtures.history
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Writes synthetic active-page previews to build/premium-report-preview/ for manual visual review (pdftoppm). */
class PremiumActivePreviewTest {
    @Test
    fun `write active page previews`() {
        val dir = File("build/premium-report-preview").apply { mkdirs() }
        val cases = mapOf(
            "active-a-complete" to activeDataset(history(PremiumReportFixtures.sampleRows())),
            "active-b-partial" to activeDataset(history(PremiumReportFixtures.partialRows())),
            "active-c-overlap" to activeDataset(history(PremiumReportFixtures.overlapRows())),
            "active-d-unavailable" to activeDataset(StudyHistory.unavailable(PremiumReportFixtures.CUPS, "not_captured")),
            "active-f-long" to activeDataset(history(PremiumReportFixtures.longRows()))
        )
        cases.forEach { (name, ds) ->
            val file = File(dir, "$name.pdf")
            file.writeBytes(PremiumReportPdf.render(listOf(PremiumActiveSection()), ds))
            assertTrue(file.length() > 0)
        }
    }
}
