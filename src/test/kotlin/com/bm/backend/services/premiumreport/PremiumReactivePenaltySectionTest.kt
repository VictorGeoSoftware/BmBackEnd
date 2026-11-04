package com.bm.backend.services.premiumreport

import com.bm.backend.services.premiumreport.PremiumReactiveTexts as T
import com.bm.backend.testing.PdfInspection
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.activeDataset
import com.bm.backend.testing.PremiumReportFixtures.history
import com.bm.backend.utils.ReactivePenaltyPrices
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reactive penalty block on the reactive page (spec report-reactive-penalty), synthetic fixtures only. */
class PremiumReactivePenaltySectionTest {
    private fun text(ds: com.bm.backend.models.ReportDataset) = PdfInspection(PremiumReportPdf.render(listOf(PremiumReactiveSection()), ds)).text
    private fun squash(s: String) = s.replace(Regex("\\s+"), "")
    private fun has(text: String, part: String) = squash(part) in squash(text)

    @Test
    fun `3_0TD shows the penalty column, totals, chart and the BOE note`() {
        val t = text(activeDataset(history(PremiumReportFixtures.reactivePenaltyRows())))
        listOf("${T.COL_PENALTY} (€)", T.PENALTY_TITLE, T.PENALTY_CHART_TITLE, T.LAST_12M, T.PREVIOUS_12M, T.LAST_24M,
            T.penaltyNote(ReactivePenaltyPrices.BOE_REFERENCE)).forEach { assertTrue(has(t, it), "missing '$it' in $t") }
        // Per reading: P1 0,062332 × 670 + P2 0,041554 × 290 = 41,76 + 12,05 = 53,81 €; P3–P5 and P6 add nothing.
        assertTrue(has(t, "53,81 €"), t)
        // 12 readings in the last year: 12 × 53,8131 = 645,76 € (rounded once, on the total).
        assertTrue(has(t, "645,76 €"), t)
        assertFalse(has(t, T.PENALTY_NOT_APPLICABLE))
    }

    @Test
    fun `recorded low reactive gives 0,00 euros, never a missing figure`() {
        val t = text(activeDataset(history(PremiumReportFixtures.reactiveZeroRows())))
        assertTrue(has(t, "0,00 €"), t)
        assertFalse(has(t, T.PENALTY_MISSING_FOOTNOTE))
    }

    @Test
    fun `2_0TD gets a single not-applicable note and no penalty column`() {
        val t = text(PremiumReportFixtures.tariffDataset())
        assertTrue(has(t, T.READINGS_TABLE_TITLE), t)
        assertTrue(has(t, T.PENALTY_NOT_APPLICABLE), t)
        assertFalse(has(t, "${T.COL_PENALTY} (€)"))
        assertFalse(has(t, T.PENALTY_CHART_TITLE))
        assertFalse(has(t, T.LAST_24M))
    }

    @Test
    fun `6_1TD is calculated on the page`() {
        val base = activeDataset(history(PremiumReportFixtures.reactivePenaltyRows()))
        val ds = base.copy(
            comparison = base.comparison.copy(tariffName = "6.1TD"),
            snapshot = base.snapshot.copy(fields = base.snapshot.fields!!.copy(tariff = "6.1TD"))
        )
        val t = text(ds)
        assertTrue(has(t, "53,81 €"), t)
        assertFalse(has(t, T.PENALTY_NOT_APPLICABLE))
    }

    @Test
    fun `a fallback profile shows the not-applicable note`() {
        val t = text(PremiumReportFixtures.tariffDataset("9.9TD", "9.9TD"))
        assertTrue(has(t, T.PENALTY_NOT_APPLICABLE), t)
    }

    @Test
    fun `overlapping readings withhold the totals`() {
        val t = text(activeDataset(history(PremiumReportFixtures.reactiveOverlapRows())))
        assertTrue(has(t, T.PENALTY_NO_FIGURE_OVERLAP), t)
    }
}
