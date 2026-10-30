package com.bm.backend.services.premiumreport

import com.bm.backend.models.ActiveWindowKind
import com.bm.backend.models.StudyHistory
import com.bm.backend.testing.PremiumReportFixtures
import com.bm.backend.utils.ActiveSummaryCalculator
import com.bm.backend.utils.HistoryCoverageCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Geometry of the active-energy charts, computed from summary/history data only (synthetic fixtures). */
class ActiveChartModelTest {
    private fun summary(h: StudyHistory) =
        ActiveSummaryCalculator.calculate(h, HistoryCoverageCalculator.calculate(h), null, null)

    @Test
    fun `distribution bars equal calculator shares and labels carry kWh and percent`() {
        val s = summary(PremiumReportFixtures.history(PremiumReportFixtures.sampleRows()))
        val latest = s.windows.single { it.kind == ActiveWindowKind.LATEST_12M }
        val model = ActiveChartModel.distribution(latest)!!
        val shares = listOf(latest.shares!!.p1, latest.shares!!.p2, latest.shares!!.p3, latest.shares!!.p4, latest.shares!!.p5, latest.shares!!.p6)
        assertEquals(6, model.bars.size)
        model.bars.forEachIndexed { i, b ->
            assertEquals(i + 1, b.period)
            assertEquals(shares[i]!!, b.value!!, 1e-6)
            assertEquals(b.value!! / model.axisMax * model.plotWidth, b.width, 1e-6)
        }
        assertEquals("P1 7.201 kWh (9,9 %)", model.bars[0].label)
        assertTrue(model.axisMax >= shares.maxOf { it!! })
        assertEquals("0 %", model.valueTicks.first().label)
    }

    @Test
    fun `distribution is omitted unless the window is complete with shares`() {
        val s = summary(PremiumReportFixtures.history(PremiumReportFixtures.partialRows()))
        assertNull(ActiveChartModel.distribution(s.windows.first()))
    }

    @Test
    fun `readings chart has one bar per source row on a true date axis`() {
        val rows = PremiumReportFixtures.sampleRows()
        val h = PremiumReportFixtures.history(rows)
        val model = ActiveChartModel.readings(h, HistoryCoverageCalculator.calculate(h))!!
        assertEquals(rows.size, model.bars.size)
        assertEquals(rows.size, model.totalRows)
        assertEquals(rows.size, model.shownRows)
        val start = rows.minOf { it.startDate }
        val span = ChronoUnit.DAYS.between(start, rows.maxOf { it.endDate }).toDouble()
        model.bars.forEach { b ->
            val r = rows[b.sourceIndex!!]
            val days = ChronoUnit.DAYS.between(r.startDate, r.endDate).toDouble()
            val total = listOf(r.active.p1, r.active.p2, r.active.p3, r.active.p4, r.active.p5, r.active.p6).filterNotNull().sum()
            assertEquals(total / days, b.value!!, 1e-6)
            assertEquals(model.plotLeft + ChronoUnit.DAYS.between(start, r.startDate) / span * model.plotWidth, b.x, 1e-6)
            assertEquals(days / span * model.plotWidth, b.width, 1e-6)
            assertEquals(b.value!! / model.axisMax * model.plotHeight, b.height, 1e-6)
        }
        // The 2026-03-15 split stays as two bars, and no bar is an artificial spike.
        assertTrue(model.bars.any { rows[it.sourceIndex!!].endDate == LocalDate.parse("2026-03-15") })
        assertTrue(model.bars.any { rows[it.sourceIndex!!].startDate == LocalDate.parse("2026-03-15") })
        assertTrue(model.bars.mapNotNull { it.value }.max() < 250.0)
        // The zero row is a zero marker; the overlapping rows are outlined.
        val zero = model.bars.single { it.sourceIndex == rows.lastIndex }
        assertEquals(ChartBarStyle.ZERO_MARKER, zero.style)
        assertTrue(model.bars.count { it.style == ChartBarStyle.OUTLINED } >= 1)
        assertEquals("kWh/día", model.unit)
    }

    @Test
    fun `a short reading keeps its true narrow extent and its own kWh per day`() {
        // Dedicated short-row case (2 days), kept out of the preview fixtures.
        val d = LocalDate.parse("2026-01-01")
        val rows = listOf(
            PremiumReportFixtures.row(d, d.plusDays(30), PremiumReportFixtures.pv(300.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
            PremiumReportFixtures.row(d.plusDays(30), d.plusDays(32), PremiumReportFixtures.pv(6055.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        )
        val h = PremiumReportFixtures.history(rows)
        val model = ActiveChartModel.readings(h, HistoryCoverageCalculator.calculate(h))!!
        val short = model.bars.single { it.sourceIndex == 1 }
        assertEquals(3027.5, short.value!!, 1e-9)
        assertEquals(2.0 / 32 * model.plotWidth, short.width, 1e-6)
        assertTrue(short.label.endsWith("3.027,5 kWh/día"), short.label)
        assertEquals("10,0 kWh/día", model.bars.single { it.sourceIndex == 0 }.label.substringAfter(": "))
        assertEquals("5.000,0", model.valueTicks.last().label)
    }

    @Test
    fun `readings chart caps at 36 rows and null rows are gaps`() {
        val rows = PremiumReportFixtures.longRows().toMutableList()
        rows[39] = rows[39].copy(active = PremiumReportFixtures.pv(null, null, null, null, null, null))
        val h = PremiumReportFixtures.history(rows)
        val model = ActiveChartModel.readings(h, HistoryCoverageCalculator.calculate(h))!!
        assertEquals(40, model.totalRows)
        assertEquals(36, model.shownRows)
        assertEquals(36, model.bars.size)
        assertEquals((4..39).toList(), model.bars.map { it.sourceIndex })
        val gap = model.bars.single { it.sourceIndex == 39 }
        assertEquals(ChartBarStyle.NO_DATA, gap.style)
        assertNull(gap.value)
    }

    @Test
    fun `axis maximum is a nice 1-2-5 ceiling and models are deterministic`() {
        assertEquals(500.0, ActiveChartModel.niceCeiling(321.0), 1e-9)
        assertEquals(200.0, ActiveChartModel.niceCeiling(200.0), 1e-9)
        assertEquals(1.0, ActiveChartModel.niceCeiling(0.0), 1e-9)
        assertEquals(10.0, ActiveChartModel.niceCeiling(6.0), 1e-9)
        val h = PremiumReportFixtures.history(PremiumReportFixtures.sampleRows())
        assertEquals(
            ActiveChartModel.readings(h, HistoryCoverageCalculator.calculate(h)),
            ActiveChartModel.readings(h, HistoryCoverageCalculator.calculate(h))
        )
    }

    @Test
    fun `unavailable history has no readings chart`() {
        val h = StudyHistory.unavailable(PremiumReportFixtures.CUPS, "not_captured")
        assertNull(ActiveChartModel.readings(h, HistoryCoverageCalculator.calculate(h)))
    }
}
