package com.bm.backend.utils

import com.bm.backend.models.PeriodValues
import com.bm.backend.models.ReactivePenaltyWindowKind
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.TariffPeriodProfile
import com.bm.backend.models.TariffProfileStatus
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Estimated inductive reactive penalty (spec report-reactive-penalty, BOE-A-2025-26348 prices). */
class ReactivePenaltyCalculatorTest {
    private val all = (1..6).toList()
    private fun pv(vararg v: Double?) = PeriodValues(v[0], v[1], v[2], v[3], v[4], v[5])
    private fun profile(code: String, status: TariffProfileStatus = TariffProfileStatus.SUPPORTED, periods: List<Int> = all) =
        TariffPeriodProfile(code, periods, periods, status)

    private fun eq(e: Double, a: Double?) = assertTrue(a != null && kotlin.math.abs(e - a) < 1e-9, "expected $e got $a")

    @Test
    fun `no penalty when reactive is within 33 percent of active or cos phi is at least 0,95`() {
        eq(0.0, ReactivePenaltyCalculator.periodPenalty(1000.0, 330.0))
        eq(0.0, ReactivePenaltyCalculator.periodPenalty(1000.0, 0.0))
        eq(0.0, ReactivePenaltyCalculator.periodPenalty(0.0, 0.0))
    }

    @Test
    fun `medium price between 0,80 and 0,95 and high price below 0,80 on the excess over 33 percent`() {
        // A=1000, R=620: cos φ = 0,850 → 0,041554 × (620 − 330)
        val medium = ReactivePenaltyCalculator.periodPenalty(1000.0, 620.0)
        assertTrue(1000.0 / sqrt(1000.0 * 1000 + 620.0 * 620) in 0.80..0.95)
        eq(0.041554 * 290.0, medium)
        // A=1000, R=1000: cos φ = 0,707 → 0,062332 × (1000 − 330)
        eq(0.062332 * 670.0, ReactivePenaltyCalculator.periodPenalty(1000.0, 1000.0))
        // A=0, R=10: cos φ = 0 → whole reactive at the high price
        eq(0.062332 * 10.0, ReactivePenaltyCalculator.periodPenalty(0.0, 10.0))
    }

    @Test
    fun `threshold boundaries - cos phi 0,95 is free and 0,80 pays the medium price`() {
        // R = A·tan(acos(c)), nudged to each side of the boundary.
        fun r(a: Double, c: Double) = a * sqrt(1 - c * c) / c
        val below95 = r(1000.0, 0.95) * (1 - 1e-9)
        eq(0.0, ReactivePenaltyCalculator.periodPenalty(1000.0, below95))
        val at80 = r(1000.0, 0.80) * (1 - 1e-9)
        eq(0.041554 * (at80 - 330.0), ReactivePenaltyCalculator.periodPenalty(1000.0, at80))
        val below80 = r(1000.0, 0.80) * (1 + 1e-9)
        eq(0.062332 * (below80 - 330.0), ReactivePenaltyCalculator.periodPenalty(1000.0, below80))
    }

    @Test
    fun `row penalty sums P1 to P5, ignores P6 and is null when a penalised cell is missing`() {
        val a = pv(1000.0, 1000.0, 0.0, 0.0, 0.0, 1000.0)
        val r = pv(1000.0, 620.0, 0.0, 0.0, 0.0, 5000.0)
        eq(0.062332 * 670 + 0.041554 * 290, ReactivePenaltyCalculator.rowPenalty(a, r, (1..5).toList()))
        assertNull(ReactivePenaltyCalculator.rowPenalty(pv(null, 1.0, 1.0, 1.0, 1.0, 1.0), pv(1.0, 1.0, 1.0, 1.0, 1.0, 1.0), (1..5).toList()))
        // A missing P6 value does not matter.
        eq(0.0, ReactivePenaltyCalculator.rowPenalty(pv(1.0, 1.0, 1.0, 1.0, 1.0, null), pv(0.0, 0.0, 0.0, 0.0, 0.0, null), (1..5).toList()))
    }

    @Test
    fun `applies to supported 3_0TD and 6_xTD only`() {
        assertTrue(ReactivePenaltyCalculator.applies(profile("3.0TD")))
        assertTrue(ReactivePenaltyCalculator.applies(profile("6.1TD")))
        assertFalse(ReactivePenaltyCalculator.applies(profile("2.0TD", periods = listOf(1, 2, 3))))
        assertFalse(ReactivePenaltyCalculator.applies(profile("3.0TD", TariffProfileStatus.FALLBACK_INCONSISTENT)))
    }

    @Test
    fun `totals sum the readings contained in each window and are withheld when a reading is not calculable`() {
        val anchor = LocalDate.parse("2026-07-31")
        val start = anchor.minusMonths(24)
        val rows = (0 until 24).map { i ->
            val s = start.plusMonths(i.toLong())
            StudyHistoryInterval(s, s.plusMonths(1), pv(1000.0, 0.0, 0.0, 0.0, 0.0, 0.0), pv(1000.0, 0.0, 0.0, 0.0, 0.0, 0.0), pv(1.0, 1.0, 1.0, 1.0, 1.0, 1.0))
        }
        val history = StudyHistory("ES0", StudyHistoryStatus.AVAILABLE, null, rows)
        val series = ReactiveSummaryCalculator.calculate(history, HistoryCoverageCalculator.calculate(history)).historySeries
        val s = ReactivePenaltyCalculator.calculate(history, series, anchor, profile("3.0TD"))
        assertEquals(24, s.readings.size)
        val perRow = 0.062332 * 670
        val totals = s.totals.associateBy { it.kind }
        eq(12 * perRow, totals.getValue(ReactivePenaltyWindowKind.LAST_12M).penaltyEur)
        eq(12 * perRow, totals.getValue(ReactivePenaltyWindowKind.PREVIOUS_12M).penaltyEur)
        eq(24 * perRow, totals.getValue(ReactivePenaltyWindowKind.LAST_24M).penaltyEur)
        eq(sqrt(0.5), totals.getValue(ReactivePenaltyWindowKind.LAST_24M).cosPhi)
        eq(24000.0, totals.getValue(ReactivePenaltyWindowKind.LAST_24M).reactiveKvarh)

        val broken = rows.toMutableList().also { it[23] = it[23].copy(active = pv(null, 0.0, 0.0, 0.0, 0.0, 0.0)) }
        val h2 = StudyHistory("ES0", StudyHistoryStatus.AVAILABLE, null, broken)
        val s2 = ReactivePenaltyCalculator.calculate(h2, ReactiveSummaryCalculator.calculate(h2, HistoryCoverageCalculator.calculate(h2)).historySeries, anchor, profile("3.0TD"))
        val t2 = s2.totals.associateBy { it.kind }
        assertNull(t2.getValue(ReactivePenaltyWindowKind.LAST_12M).penaltyEur)
        assertEquals(ReactivePenaltyCalculator.NOT_CALCULABLE, t2.getValue(ReactivePenaltyWindowKind.LAST_12M).reason)
        eq(12 * perRow, t2.getValue(ReactivePenaltyWindowKind.PREVIOUS_12M).penaltyEur)
        assertNull(s2.readings.last().penaltyEur)
    }

    @Test
    fun `not applicable tariffs return no readings`() {
        val h = StudyHistory("ES0", StudyHistoryStatus.AVAILABLE, null, emptyList())
        assertFalse(ReactivePenaltyCalculator.calculate(h, emptyList(), LocalDate.parse("2026-07-31"), profile("2.0TD", periods = listOf(1, 2, 3))).applicable)
    }
}
