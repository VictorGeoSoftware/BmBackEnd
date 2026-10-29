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
        assertTrue(ReactiveChartModel.readings(r.series)!!.markers.isEmpty())
        assertTrue(ReactiveChartModel.distribution(r.window!!)!!.markers.isEmpty())
    }

    @Test
    fun `readings chart has one bar per series row with height equal to the row maximum`() {
        val s = summary(PremiumReportFixtures.demandOverlapZeroActiveRows())
        val m = DemandChartModel.readings(s.series)!!
        assertEquals("kW", m.unit)
        assertEquals(s.series.size, m.bars.size)
        assertTrue(m.markers.isEmpty())
        val span = ChronoUnit.DAYS.between(s.series.minOf { it.start }, s.series.maxOf { it.endExclusive }).toDouble()
        s.series.forEachIndexed { i, p ->
            val b = m.bars[i]
            val max = listOf(p.values.p1, p.values.p2, p.values.p3, p.values.p4, p.values.p5, p.values.p6).filterNotNull().max()
            assertEquals(p.index, b.sourceIndex)
            close(max, b.value)
            close(max / m.axisMax * m.plotHeight, b.height)
            close(p.days / span * m.plotWidth, b.width)
            assertEquals(if (p.inOverlap) ChartBarStyle.OUTLINED else ChartBarStyle.FILLED, b.style)
        }
        assertEquals(40.0, m.bars.maxOf { it.value!! })
        assertTrue(m.bars.any { it.label == "31/05/2026 – 29/06/2026: 40 kW" })
    }

    @Test
    fun `readings chart zero rows are markers, empty series has no chart and long series are capped`() {
        assertTrue(DemandChartModel.readings(summary(PremiumReportFixtures.demandZeroRows()).series)!!.bars.all { it.style == ChartBarStyle.ZERO_MARKER })
        assertNull(DemandChartModel.readings(emptyList()))
        val long = DemandChartModel.readings(summary(PremiumReportFixtures.demandLongRows()).series)!!
        assertEquals(ActiveChartModel.READINGS_CAP, long.bars.size)
        assertEquals(36, long.shownRows)
        assertEquals(73, long.totalRows)
    }
}
