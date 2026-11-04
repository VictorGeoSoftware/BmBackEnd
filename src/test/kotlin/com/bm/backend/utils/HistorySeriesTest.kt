package com.bm.backend.utils

import com.bm.backend.testing.PremiumReportFixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 24-month series of the reactive/demand summaries (ReportHistoryRange, decision 2026-11-04); window figures unchanged. */
class HistorySeriesTest {
    private val history = PremiumReportFixtures.history(PremiumReportFixtures.sampleRows())
    private val coverage = HistoryCoverageCalculator.calculate(history)

    @Test
    fun `reactive series lists every reactive row of the last 24 months in date order, the window series stays the window`() {
        val s = ReactiveSummaryCalculator.calculate(history, coverage)
        val rows = ReportHistoryRange.indices(history, coverage.anchorDate)
        assertEquals(rows.size, s.historySeries.size)
        assertEquals(s.historySeries.sortedWith(compareBy({ it.start }, { it.endExclusive }, { it.index })), s.historySeries)
        assertTrue(s.historySeries.first().start < s.window!!.start)
        assertTrue(s.historySeries.all { it.start >= ReportHistoryRange.start(coverage.anchorDate!!) })
        assertTrue(s.series.all { it.start >= s.window!!.start })
        assertTrue(s.historySeries.containsAll(s.series.map { p -> p.copy(inUnresolvedOverlap = s.historySeries.first { it.index == p.index }.inUnresolvedOverlap) }))
        s.historySeries.forEach { p -> assertEquals(history.intervals[p.index].reactive, p.values) }
    }

    @Test
    fun `demand series lists every maximeter row of the last 24 months and keeps window statistics`() {
        val s = DemandSummaryCalculator.calculate(history, coverage, null)
        val rows = ReportHistoryRange.indices(history, coverage.anchorDate)
        assertEquals(rows.size, s.historySeries.size)
        assertTrue(s.historySeries.first().start < s.window!!.start)
        assertTrue(s.historySeries.all { it.start >= ReportHistoryRange.start(coverage.anchorDate!!) })
        assertTrue(s.historySeries.size > s.series.size)
        s.historySeries.forEach { p -> assertEquals(history.intervals[p.index].maxDemand, p.values) }
        assertEquals(s.series.map { it.index }, s.series.map { it.index }.filter { i -> s.historySeries.any { it.index == i } })
    }
}
