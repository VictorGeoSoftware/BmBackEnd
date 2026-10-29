package com.bm.backend.services.premiumreport

import com.bm.backend.models.StudyHistory
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.activeDataset
import com.bm.backend.testing.PremiumReportFixtures.history
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Writes synthetic reactive-page previews to build/premium-report-preview/ for manual visual review (pdftoppm). */
class PremiumReactivePreviewTest {
    @Test
    fun `write reactive page previews`() {
        val dir = File("build/premium-report-preview").apply { mkdirs() }
        val cases = mapOf(
            "reactive-a-complete" to activeDataset(history(PremiumReportFixtures.reactiveCompleteRows())),
            "reactive-b-zero" to activeDataset(history(PremiumReportFixtures.reactiveZeroRows())),
            "reactive-c-missing" to activeDataset(history(PremiumReportFixtures.reactiveMissingRows())),
            "reactive-c2-outside" to activeDataset(history(PremiumReportFixtures.reactiveOutsideRows())),
            "reactive-d-incomplete" to activeDataset(history(PremiumReportFixtures.reactiveIncompleteRows())),
            "reactive-e-overlap" to activeDataset(history(PremiumReportFixtures.reactiveOverlapRows())),
            "reactive-f-active-unavailable" to activeDataset(history(PremiumReportFixtures.reactiveNoActiveRows())),
            "reactive-g-nulls" to activeDataset(history(PremiumReportFixtures.reactiveNullRows())),
            "reactive-l-long" to activeDataset(history(PremiumReportFixtures.reactiveLongRows())),
            "reactive-n2-unavailable" to activeDataset(StudyHistory.unavailable(PremiumReportFixtures.CUPS, "not_captured"))
        )
        cases.forEach { (name, ds) ->
            val file = File(dir, "$name.pdf")
            file.writeBytes(PremiumReportPdf.render(listOf(PremiumReactiveSection()), ds))
            assertTrue(file.length() > 0)
        }
    }
}
