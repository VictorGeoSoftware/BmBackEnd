package com.bm.backend.services.premiumreport

import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudySnapshot
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.activeDataset
import com.bm.backend.testing.PremiumReportFixtures.history
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Writes synthetic demanded-power page previews to build/premium-report-preview/ for manual visual review (pdftoppm). */
class PremiumDemandPreviewTest {
    @Test
    fun `write demand page previews`() {
        val dir = File("build/premium-report-preview").apply { mkdirs() }
        val cases = mapOf(
            "demand-a-complete" to activeDataset(history(PremiumReportFixtures.demandCompleteRows())),
            "demand-b-incomplete" to activeDataset(history(PremiumReportFixtures.demandIncompleteRows())),
            "demand-c-overlap-zero-active" to activeDataset(history(PremiumReportFixtures.demandOverlapZeroActiveRows())),
            "demand-d-outside" to activeDataset(history(PremiumReportFixtures.demandOutsideRows())),
            "demand-e-snapshot-unavailable" to activeDataset(
                history(PremiumReportFixtures.demandCompleteRows()), StudySnapshot.unavailable(PremiumReportFixtures.CUPS, "not_captured")
            ),
            "demand-f-contracted-missing-zero" to activeDataset(
                history(PremiumReportFixtures.demandCompleteRows()), PremiumReportFixtures.demandSnapshotMissingZero()
            ),
            "demand-g-zero" to activeDataset(history(PremiumReportFixtures.demandZeroRows())),
            "demand-h-inactive" to activeDataset(history(PremiumReportFixtures.demandInactiveRows())),
            "demand-l-long" to activeDataset(history(PremiumReportFixtures.demandLongRows())),
            "demand-n1-no-rows" to activeDataset(history(PremiumReportFixtures.demandNoRows())),
            "demand-n2-unavailable" to activeDataset(StudyHistory.unavailable(PremiumReportFixtures.CUPS, "not_captured"))
        )
        cases.forEach { (name, ds) ->
            val file = File(dir, "$name.pdf")
            file.writeBytes(PremiumReportPdf.render(listOf(PremiumDemandSection()), ds))
            assertTrue(file.length() > 0)
        }
    }
}
