package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.activeDataset
import com.bm.backend.testing.PremiumReportFixtures.history
import com.bm.backend.utils.HistoryCoverageCalculator
import com.bm.backend.utils.ReactiveSummaryCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Reactive chart geometry (spec report-reactive-page V2–V4), synthetic fixtures only. */
class ReactiveChartModelTest {
    private fun summary(ds: ReportDataset) = ReactiveSummaryCalculator.calculate(ds.history, HistoryCoverageCalculator.calculate(ds.history))
    private fun summary(rows: List<com.bm.backend.models.StudyHistoryInterval>) = summary(activeDataset(history(rows)))

    @Test
    fun `distribution bars equal calculator shares with kVArh labels`() {
        val w = summary(PremiumReportFixtures.reactiveCompleteRows()).window!!
        val m = ReactiveChartModel.distribution(w)!!
        val shares = w.shares!!.let { listOf(it.p1, it.p2, it.p3, it.p4, it.p5, it.p6) }
        assertTrue(m.horizontal)
        assertEquals(6, m.bars.size)
        m.bars.forEachIndexed { i, b ->
            assertEquals(i + 1, b.period)
            assertTrue(abs(b.value!! - shares[i]!!) < 1e-6)
            assertTrue(abs(b.width - shares[i]!! / m.axisMax * m.plotWidth) < 1e-6)
            assertEquals(ChartBarStyle.FILLED, b.style)
        }
        assertEquals("P1 1.200 kVArh (11,0 %)", m.bars[0].label)
        assertEquals("P6 4.800 kVArh (44,0 %)", m.bars[5].label)
    }

    @Test
    fun `distribution is omitted unless complete with shares and null periods are no-data bands`() {
        assertNull(ReactiveChartModel.distribution(summary(PremiumReportFixtures.reactiveZeroRows()).window!!))
        assertNull(ReactiveChartModel.distribution(summary(PremiumReportFixtures.reactiveIncompleteRows()).window!!))
        assertNull(ReactiveChartModel.distribution(summary(PremiumReportFixtures.reactiveOverlapRows()).window!!))
        val nullP4 = PremiumReportFixtures.reactiveMonths(reactive = { PremiumReportFixtures.REACTIVE_MONTH.copy(p4 = null) })
        val m = ReactiveChartModel.distribution(summary(nullP4).window!!)!!
        assertEquals("P4 —", m.bars[3].label)
        assertEquals(ChartBarStyle.NO_DATA, m.bars[3].style)
    }
}
