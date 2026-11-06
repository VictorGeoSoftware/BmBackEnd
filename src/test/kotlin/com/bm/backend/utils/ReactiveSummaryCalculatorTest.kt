package com.bm.backend.utils

import com.bm.backend.models.FamilyWindowAggregate
import com.bm.backend.models.HistoryCoverage
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.RatioActiveBasis
import com.bm.backend.models.ReactiveRatio
import com.bm.backend.models.ReactiveSeriesPoint
import com.bm.backend.models.ReactiveSummary
import com.bm.backend.models.ReactiveWindowStatus
import com.bm.backend.models.ReactiveWindowSummary
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.StudyHistoryStatus
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic fixtures only (no customer data). */
class ReactiveSummaryCalculatorTest {
    private val cups = "ES0000000000000000AA0F"
    private val base = LocalDate.parse("2025-10-01")
    private val anchor = LocalDate.parse("2026-10-01")
    private val zeros = PeriodValues(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val nulls = PeriodValues(null, null, null, null, null, null)

    private fun pv(vararg v: Double?) = PeriodValues(v[0], v[1], v[2], v[3], v[4], v[5])
    private fun cells(v: PeriodValues) = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)
    private fun row(start: LocalDate, end: LocalDate, active: PeriodValues, reactive: PeriodValues) =
        StudyHistoryInterval(start, end, active, reactive, nulls)
    private fun history(rows: List<StudyHistoryInterval>) = StudyHistory(cups, StudyHistoryStatus.AVAILABLE, null, rows)
    private fun monthly(
        active: (Int) -> PeriodValues = { pv(10.0, 20.0, 30.0, 40.0, 50.0, 60.0) },
        reactive: (Int) -> PeriodValues = { pv(1.0, 2.0, 3.0, 4.0, 5.0, 6.0) }
    ) = (0 until 12).map { i -> row(base.plusMonths(i.toLong()), base.plusMonths(i + 1L), active(i), reactive(i)) }

    private fun calc(h: StudyHistory) = ReactiveSummaryCalculator.calculate(h, HistoryCoverageCalculator.calculate(h))

    private fun assertFinite(s: ReactiveSummary) {
        (s.ratios + s.overallRatio).forEach { r ->
            r.ratio?.let { assertTrue(it.isFinite(), "ratio must be finite") }
        }
    }

    @Test
    fun `A known totals give complete window, shares and hand ratios with stated basis`() {
        val h = history(monthly(reactive = { i -> pv(1.0 + i, 2.0, 3.0, 4.0, 5.0, 6.0) }))
        val s = calc(h)
        val w = assertNotNull(s.window)
        assertEquals(ReactiveWindowStatus.COMPLETE, w.status)
        assertEquals(anchor, s.anchorDate)
        assertEquals(base, w.start); assertEquals(anchor, w.endExclusive)
        val expected = listOf((1..12).sum().toDouble(), 24.0, 36.0, 48.0, 60.0, 72.0)
        assertEquals(expected, cells(w.periodTotals))
        assertEquals(expected.sum(), w.total)
        assertNull(w.partialTotal)
        val shares = assertNotNull(w.shares)
        assertTrue(abs(cells(shares).filterNotNull().sum() - 100.0) < 1e-9)
        assertFalse(w.recordedZero); assertNull(w.reason)
        assertEquals(RatioActiveBasis.HISTORY_ACTIVE_LATEST_12M, s.activeBasis)
        assertEquals(ReactiveWindowStatus.COMPLETE, s.activeWindowStatus)
        assertEquals(base, s.activeWindowStart); assertEquals(anchor, s.activeWindowEndExclusive)
        val activeExpected = listOf(120.0, 240.0, 360.0, 480.0, 600.0, 720.0)
        assertEquals(activeExpected, cells(assertNotNull(s.activeTotals)))
        assertEquals(activeExpected.sum(), s.activeTotal)
        assertNull(s.ratioReason)
        s.ratios.forEachIndexed { p, r ->
            assertEquals(p + 1, r.period)
            assertEquals(expected[p] / activeExpected[p], r.ratio)
            assertNull(r.reason)
        }
        assertEquals(expected.sum() / activeExpected.sum(), s.overallRatio.ratio)
        assertNull(s.overallRatio.period)
        assertEquals(12, s.series.size)
        assertEquals(s.series.map { it.start }.sorted(), s.series.map { it.start })
        assertEquals(1.0 + 2 + 3 + 4 + 5 + 6, s.series.first().total)
        assertFalse(s.series.any { it.inUnresolvedOverlap })
    }

    @Test
    fun `B all-zero reactive is a recorded zero with zero ratios`() {
        val s = calc(history(monthly(reactive = { zeros })))
        val w = assertNotNull(s.window)
        assertEquals(ReactiveWindowStatus.COMPLETE, w.status)
        assertEquals(0.0, w.total)
        assertTrue(w.recordedZero)
        assertEquals(ReactiveSummaryCalculator.ZERO_TOTAL, w.reason)
        assertNull(w.shares)
        s.ratios.forEach { assertEquals(0.0, it.ratio) }
        assertEquals(0.0, s.overallRatio.ratio)
    }

    @Test
    fun `C missing reactive is unavailable, never zero`() {
        val s = calc(history(monthly(reactive = { nulls })))
        assertEquals(StudyHistoryStatus.UNAVAILABLE, s.status)
        assertEquals(ReactiveSummaryCalculator.NO_REACTIVE_ROWS, s.reason)
        assertNull(s.window)
        assertTrue(s.ratios.isEmpty()); assertNull(s.overallRatio.ratio)
        assertTrue(s.series.isEmpty())
    }

    @Test
    fun `D partial nulls are counted, never zero, and block only those periods`() {
        val s = calc(history(monthly(reactive = { i -> if (i < 3) pv(1.0, 2.0, 3.0, null, null, null) else pv(1.0, 2.0, 3.0, 4.0, 5.0, 6.0) })))
        val w = assertNotNull(s.window)
        assertEquals(9, w.nullCells)
        assertFalse(w.recordedZero)
        assertEquals(36.0, w.periodTotals.p4)
        (1..3).forEach { p -> assertNotNull(s.ratios[p - 1].ratio) }
        (4..6).forEach { p ->
            assertNull(s.ratios[p - 1].ratio)
            assertEquals(ReactiveSummaryCalculator.PERIOD_NULL_CELLS, s.ratios[p - 1].reason)
        }
        assertNull(s.overallRatio.ratio)
        assertEquals(ReactiveSummaryCalculator.PERIOD_NULL_CELLS, s.overallRatio.reason)
        assertNull(s.series.first().values.p4)
    }

    @Test
    fun `E gap or crossing row gives incomplete partial total without ratios`() {
        val gap = calc(history(monthly().filterIndexed { i, _ -> i != 5 }))
        val gw = assertNotNull(gap.window)
        assertEquals(ReactiveWindowStatus.INCOMPLETE, gw.status)
        assertNull(gw.total); assertNull(gw.shares)
        assertEquals(11 * 21.0, gw.partialTotal)
        assertEquals(1, gw.gaps.size)
        assertTrue(gw.coveredDays < gw.windowDays)
        assertEquals(ReactiveSummaryCalculator.REACTIVE_INCOMPLETE, gap.ratioReason)
        assertTrue(gap.ratios.all { it.ratio == null && it.reason == ReactiveSummaryCalculator.REACTIVE_INCOMPLETE })

        val rows = monthly().toMutableList()
        rows[0] = row(base.minusDays(10), base.plusMonths(1), rows[0].active, rows[0].reactive)
        val crossing = calc(history(rows))
        val cw = assertNotNull(crossing.window)
        assertEquals(ReactiveWindowStatus.INCOMPLETE, cw.status)
        assertEquals(listOf(0), cw.crossing)
        assertFalse(crossing.series.any { it.index == 0 })
        assertEquals(ReactiveSummaryCalculator.REACTIVE_INCOMPLETE, crossing.overallRatio.reason)
    }

    @Test
    fun `F reactive complete with active gap gives active_incomplete but reactive totals`() {
        val s = calc(history(monthly(active = { i -> if (i == 4) nulls else pv(10.0, 20.0, 30.0, 40.0, 50.0, 60.0) })))
        val w = assertNotNull(s.window)
        assertEquals(ReactiveWindowStatus.COMPLETE, w.status)
        assertEquals(12 * 21.0, w.total)
        assertEquals(ReactiveWindowStatus.INCOMPLETE, s.activeWindowStatus)
        assertNull(s.activeTotal); assertNull(s.activeTotals)
        assertEquals(ReactiveSummaryCalculator.ACTIVE_INCOMPLETE, s.ratioReason)
        assertTrue((s.ratios + s.overallRatio).all { it.ratio == null && it.reason == ReactiveSummaryCalculator.ACTIVE_INCOMPLETE })
    }

    @Test
    fun `G1 overlap with an all-zero reactive row is neutral and disclosed`() {
        val rows = monthly() + row(base.plusMonths(2), base.plusMonths(3), zeros, zeros)
        val s = calc(history(rows))
        val w = assertNotNull(s.window)
        assertEquals(ReactiveWindowStatus.COMPLETE, w.status)
        assertEquals(1, w.overlapDisclosures.size)
        assertEquals(12 * 21.0, w.total)
        assertFalse(s.series.any { it.inUnresolvedOverlap })
    }

    @Test
    fun `G2 blank-zero or non-zero reactive overlaps are unresolved`() {
        listOf(pv(0.0, null, null, null, null, null), pv(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)).forEach { extra ->
            val rows = monthly() + row(base.plusMonths(2), base.plusMonths(3), zeros, extra)
            val s = calc(history(rows))
            val w = assertNotNull(s.window)
            assertEquals(ReactiveWindowStatus.OVERLAP_UNRESOLVED, w.status)
            assertEquals(ReactiveSummaryCalculator.OVERLAP_UNRESOLVED, w.reason)
            assertNull(w.total); assertNull(w.partialTotal); assertNull(w.shares)
            assertEquals(ReactiveSummaryCalculator.REACTIVE_OVERLAP, s.ratioReason)
            assertTrue((s.ratios + s.overallRatio).all { it.ratio == null })
            assertEquals(setOf(2, 12), s.series.filter { it.inUnresolvedOverlap }.map { it.index }.toSet())
        }
    }

    @Test
    fun `G3 non-neutral active-only overlap gives active_overlap`() {
        val rows = monthly() + row(base.plusMonths(2), base.plusMonths(3), pv(1.0, 1.0, 1.0, 1.0, 1.0, 1.0), nulls)
        val s = calc(history(rows))
        assertEquals(ReactiveWindowStatus.COMPLETE, s.window?.status)
        assertEquals(ReactiveWindowStatus.OVERLAP_UNRESOLVED, s.activeWindowStatus)
        assertEquals(ReactiveSummaryCalculator.ACTIVE_OVERLAP, s.ratioReason)
    }

    @Test
    fun `G per-family neutrality - active-neutral row is not reactive-neutral`() {
        val rows = monthly() + row(base.plusMonths(2), base.plusMonths(3), zeros, pv(1.0, 1.0, 1.0, 1.0, 1.0, 1.0))
        val s = calc(history(rows))
        assertEquals(ReactiveWindowStatus.OVERLAP_UNRESOLVED, s.window?.status)
        assertEquals(ReactiveWindowStatus.COMPLETE, s.activeWindowStatus)
    }

    @Test
    fun `H zero active period gives active_zero and never NaN or Infinity`() {
        val s = calc(history(monthly(active = { pv(10.0, 20.0, 30.0, 40.0, 50.0, 0.0) })))
        val p6 = s.ratios[5]
        assertNull(p6.ratio); assertEquals(ReactiveSummaryCalculator.ACTIVE_ZERO, p6.reason)
        assertNotNull(s.overallRatio.ratio)
        assertFinite(s)
        val allZero = calc(history(monthly(active = { zeros })))
        assertTrue((allZero.ratios + allZero.overallRatio).all { it.ratio == null && it.reason == ReactiveSummaryCalculator.ACTIVE_ZERO })
        assertFinite(allZero)
    }

    @Test
    fun `I unavailable inputs never throw`() {
        val unavailable = StudyHistory.unavailable(cups, "history_missing")
        assertEquals("history_missing", calc(unavailable).reason)
        assertEquals("cov_x", ReactiveSummaryCalculator.calculate(history(monthly()), HistoryCoverage.unavailable("cov_x")).reason)
        val empty = calc(history(emptyList()))
        assertEquals(StudyHistoryStatus.UNAVAILABLE, empty.status)
        assertEquals(ReactiveSummaryCalculator.NO_ANCHOR, empty.reason)
        val broken = HistoryCoverageCalculator.calculate(history(monthly())).copy(invertedIntervals = listOf(-1))
        assertEquals(StudyHistoryStatus.AVAILABLE, ReactiveSummaryCalculator.calculate(history(monthly()), broken).status)
        val bogusAnchor = HistoryCoverage(StudyHistoryStatus.AVAILABLE, null, null, emptyList(), emptyMap())
        assertEquals(ReactiveSummaryCalculator.NO_ANCHOR, ReactiveSummaryCalculator.calculate(history(monthly()), bogusAnchor).reason)
    }

    @Test
    fun `J classification matches coverage and inputs are unchanged`() {
        val fixtures = listOf(
            monthly(),
            monthly().filterIndexed { i, _ -> i != 3 },
            monthly().toMutableList().also { it[0] = row(base.minusDays(5), base.plusMonths(1), it[0].active, it[0].reactive) }
        )
        fixtures.forEach { rows ->
            val h = history(rows)
            val copy = h.copy(intervals = h.intervals.toList())
            val cov = HistoryCoverageCalculator.calculate(h)
            val covCopy = cov.copy()
            val s = ReactiveSummaryCalculator.calculate(h, cov)
            val wc = cov.families.getValue(MeasurementFamily.REACTIVE).windows.single { it.months == 12 }
            val w = assertNotNull(s.window)
            assertEquals(wc.contained.sorted(), w.contained.sorted())
            assertEquals(wc.crossing.sorted(), w.crossing.sorted())
            assertEquals(wc.complete, w.status == ReactiveWindowStatus.COMPLETE)
            assertEquals(wc.start, w.start); assertEquals(wc.endExclusive, w.endExclusive)
            assertEquals(copy, h); assertEquals(covCopy, cov)
        }
    }

    @Test
    fun `K models carry no penalty or compliance vocabulary`() {
        val forbidden = listOf("penal", "cost", "cosphi", "phi", "complian", "threshold", "verdict", "limit", "fine", "diagnos")
        listOf(
            ReactiveSummary::class, ReactiveWindowSummary::class, ReactiveRatio::class,
            ReactiveSeriesPoint::class, FamilyWindowAggregate::class
        ).forEach { k ->
            k.java.declaredFields.map { it.name.lowercase() }.forEach { name ->
                forbidden.forEach { f -> assertFalse(name.contains(f), "${k.simpleName}.$name contains $f") }
            }
        }
    }
}
