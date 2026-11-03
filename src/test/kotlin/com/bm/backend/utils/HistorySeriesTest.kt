package com.bm.backend.utils

import com.bm.backend.testing.PremiumReportFixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Full-history series of the reactive/demand summaries (feedback 2026-11-03): every dated row, window figures unchanged. */
class HistorySeriesTest {
    private val history = PremiumReportFixtures.history(PremiumReportFixtures.sampleRows())
    private val coverage = HistoryCoverageCalculator.calculate(history)

    @Test
    fun `reactive history series lists every dated reactive row in date order, the window series stays the window`() {
        val s = ReactiveSummaryCalculator.calculate(history, coverage)
        val rows = history.intervals.indices.filter { history.intervals[it].startDate < history.intervals[it].endDate }
        assertEquals(rows.size, s.historySeries.size)
        assertEquals(s.historySeries.sortedWith(compareBy({ it.start }, { it.endExclusive }, { it.index })), s.historySeries)
        assertTrue(s.historySeries.first().start < s.window!!.start)
        assertTrue(s.series.all { it.start >= s.window!!.start })
        assertTrue(s.historySeries.containsAll(s.series.map { p -> p.copy(inUnresolvedOverlap = s.historySeries.first { it.index == p.index }.inUnresolvedOverlap) }))
        s.historySeries.forEach { p -> assertEquals(history.intervals[p.index].reactive, p.values) }
    }

    @Test
    fun `demand history series lists every dated maximeter row and keeps window statistics`() {
        val s = DemandSummaryCalculator.calculate(history, coverage, null)
        val rows = history.intervals.indices.filter { history.intervals[it].startDate < history.intervals[it].endDate }
        assertEquals(rows.size, s.historySeries.size)
        assertTrue(s.historySeries.first().start < s.window!!.start)
        assertTrue(s.historySeries.size > s.series.size)
        s.historySeries.forEach { p -> assertEquals(history.intervals[p.index].maxDemand, p.values) }
        assertEquals(s.series.map { it.index }, s.series.map { it.index }.filter { i -> s.historySeries.any { it.index == i } })
    }
}
