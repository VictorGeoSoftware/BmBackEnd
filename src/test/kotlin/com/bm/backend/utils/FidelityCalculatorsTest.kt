package com.bm.backend.utils

import com.bm.backend.models.DemandSeriesPoint
import com.bm.backend.models.PeriodValues
import com.bm.backend.testing.PremiumReportFixtures
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 24-month active totals and demand averages (spec report-original-fidelity). */
class FidelityCalculatorsTest {
    private fun point(i: Int, v: PeriodValues) = LocalDate.parse("2025-01-01").plusMonths(i.toLong()).let {
        DemandSeriesPoint(i, it, it.plusMonths(1), 30, v, false, false)
    }

    @Test
    fun `demand averages exclude zero readings, all-zero periods average 0 and missing periods have none`() {
        val a = DemandRangeCalculator.averages(listOf(
            point(0, PeriodValues(50.0, 0.0, null, 0.0, 10.0, 40.0)),
            point(1, PeriodValues(0.0, 0.0, null, 0.0, 20.0, 60.0)),
            point(2, PeriodValues(70.0, 0.0, null, 0.0, 0.0, 50.0))
        ))
        assertEquals(60.0, a.means.p1)
        assertEquals(0.0, a.means.p2)
        assertNull(a.means.p3)
        assertEquals(15.0, a.means.p5)
        assertEquals(50.0, a.means.p6)
        assertEquals(listOf(2, 0, 0, 0, 2, 3), a.counts)
    }

    @Test
    fun `active 24-month totals equal the column sums of the contained readings and shares add to 100`() {
        val history = PremiumReportFixtures.history(PremiumReportFixtures.sampleRows())
        val coverage = HistoryCoverageCalculator.calculate(history)
        val r = ActiveRangeCalculator.calculate(history, coverage, (1..6).toList())
        assertNull(r.reason)
        val rows = ReportHistoryRange.indices(history, coverage.anchorDate).map { history.intervals[it].active }
        val p1 = rows.mapNotNull { it.p1 }.sum()
        assertEquals(p1, r.periodTotals!!.p1!!, 1e-6)
        val shares = r.shares!!
        assertEquals(100.0, listOf(shares.p1, shares.p2, shares.p3, shares.p4, shares.p5, shares.p6).sumOf { it!! }, 1e-9)
        assertNotNull(r.total)
    }

    @Test
    fun `an unresolved active overlap in the range withholds the totals`() {
        val history = PremiumReportFixtures.history(PremiumReportFixtures.overlapRows())
        val r = ActiveRangeCalculator.calculate(history, HistoryCoverageCalculator.calculate(history), (1..6).toList())
        assertEquals(ActiveRangeCalculator.OVERLAP, r.reason)
        assertTrue(r.total == null && r.shares == null)
    }
}
