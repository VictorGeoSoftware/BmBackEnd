package com.bm.backend.utils

import com.bm.backend.models.ActiveWindowKind
import com.bm.backend.models.ReportDataset
import com.bm.backend.testing.PremiumReportFixtures as F
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 2.0TD scenarios of the summary calculators with the tariff's periods (spec report-tariff-applicability). */
class TariffSummaryCalculatorsTest {
    private val energy = listOf(1, 2, 3)
    private val power = listOf(1, 2)

    private fun cov(ds: ReportDataset) = HistoryCoverageCalculator.calculate(ds.history)

    private fun both(check: (ReportDataset) -> Unit) {
        check(F.tariffDataset())
        check(F.tariffDataset(rows = F.tariff20Rows(zeros = true), zeros = true))
    }

    @Test
    fun `active compare rows and null cells follow energy periods`() = both { ds ->
        val s = ActiveSummaryCalculator.calculate(ds.history, cov(ds), ds.snapshot, ds.report.consumptionData, energy)
        s.comparisons.forEach { c -> assertEquals(energy, c.periods.map { it.period }) }
        val latest = s.windows.first { it.kind == ActiveWindowKind.LATEST_12M }
        assertEquals(0, latest.nullCells)
        val total = (0 until 12).sumOf { 60.0 + it + 55.0 + 130.0 }
        assertEquals(total, latest.total)
    }

    @Test
    fun `active default periods keep six comparison rows`() {
        val ds = F.tariffDataset()
        val s = ActiveSummaryCalculator.calculate(ds.history, cov(ds), ds.snapshot, ds.report.consumptionData)
        s.comparisons.forEach { c -> assertEquals((1..6).toList(), c.periods.map { it.period }) }
        assertEquals(36, s.windows.first { it.kind == ActiveWindowKind.LATEST_12M }.nullCells)
    }

    @Test
    fun `reactive ratios only for energy periods and total ratio computed`() = both { ds ->
        val s = ReactiveSummaryCalculator.calculate(ds.history, cov(ds), energy)
        assertEquals(energy, s.ratios.map { it.period })
        s.ratios.forEach { assertNotNull(it.ratio, "$it") }
        assertNotNull(s.overallRatio?.ratio)
        assertNull(s.overallRatio?.reason)
        assertEquals(0, s.window?.nullCells)
    }

    @Test
    fun `reactive default periods still block the total ratio on null P4 to P6`() {
        val ds = F.tariffDataset()
        val s = ReactiveSummaryCalculator.calculate(ds.history, cov(ds))
        assertEquals(ReactiveSummaryCalculator.PERIOD_NULL_CELLS, s.overallRatio?.reason)
    }

    @Test
    fun `demand statistics only for power periods`() = both { ds ->
        val s = DemandSummaryCalculator.calculate(ds.history, cov(ds), ds.snapshot, power)
        assertEquals(power, s.periods.map { it.period })
        assertEquals(4.4, s.periods[1].peakKw)
        assertEquals(4.6, s.periods[1].contractedKw)
        s.periods.forEach { assertNull(it.reason) }
        assertEquals(0, s.window?.nullCells)
    }
}
