package com.bm.backend.services.premiumreport

import com.bm.backend.models.ReportDataset
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.testing.PremiumReportFixtures.activeDataset
import com.bm.backend.testing.PremiumReportFixtures.activeSnapshot
import com.bm.backend.testing.PremiumReportFixtures.history
import com.bm.backend.utils.DemandSummaryCalculator
import com.bm.backend.utils.HistoryCoverageCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Demanded-power chart geometry (spec report-demand-page V3/V4), synthetic fixtures only. */
class DemandChartModelTest {
    private fun summary(rows: List<StudyHistoryInterval>, ds: ReportDataset = activeDataset(history(rows), activeSnapshot())) =
        DemandSummaryCalculator.calculate(ds.history, HistoryCoverageCalculator.calculate(ds.history), ds.snapshot)

    private fun close(e: Double, a: Double?) = assertTrue(a != null && abs(e - a) < 1e-6, "expected $e got $a")

    @Test
    fun `comparison bars equal peaks and markers equal contracted power in kW`() {
        val s = summary(PremiumReportFixtures.demandCompleteRows())
        val m = DemandChartModel.comparison(s.periods)
        assertTrue(m.horizontal)
        assertEquals("kW", m.unit)
        assertEquals(ActiveChartModel.niceCeiling(34.75), m.axisMax)
        assertEquals(6, m.bars.size)
        assertEquals(6, m.markers.size)
        s.periods.forEachIndexed { i, p ->
            val b = m.bars[i]
            assertEquals(i + 1, b.period)
            close(p.peakKw!!, b.value)
            close(p.peakKw!! / m.axisMax * m.plotWidth, b.width)
            assertEquals(ChartBarStyle.FILLED, b.style)
            val k = m.markers[i]
            assertEquals(i + 1, k.period)
            close(p.contractedKw!!, k.value)
            close(m.plotLeft + p.contractedKw!! / m.axisMax * m.plotWidth, k.x)
        }
        assertEquals("P1 16,103 kW", m.bars[0].label)
        assertEquals("P6 34,75 kW", m.bars[5].label)
    }

    @Test
    fun `comparison null peak is a no-data band, missing contracted has no marker, zero contracted sits on the axis`() {
        val ds = activeDataset(history(PremiumReportFixtures.demandInactiveRows()), PremiumReportFixtures.demandSnapshotMissingZero())
        val s = summary(emptyList(), ds)
        val m = DemandChartModel.comparison(s.periods)
        assertEquals("P4 —", m.bars[3].label)
        assertEquals(ChartBarStyle.NO_DATA, m.bars[3].style)
        assertEquals(listOf(1, 2, 4, 5, 6), m.markers.map { it.period })
        close(m.plotLeft, m.markers.single { it.period == 2 }.x)
    }

    @Test
    fun `existing chart models carry no markers`() {
        val rows = PremiumReportFixtures.reactiveCompleteRows()
        val r = com.bm.backend.utils.ReactiveSummaryCalculator.calculate(history(rows), HistoryCoverageCalculator.calculate(history(rows)))
        assertTrue(StackedReadingsChart.model(r.series.map { ReadingSlot(it.start, it.endExclusive, it.values, false) }, (1..6).toList(), "kVArh").markers.isEmpty())
    }
}
