package com.bm.backend.utils

import com.bm.backend.models.ActiveSummary
import com.bm.backend.models.ActiveWindowKind
import com.bm.backend.models.ActiveWindowStatus
import com.bm.backend.models.AnnualSource
import com.bm.backend.models.CleanedConsumptionData
import com.bm.backend.models.ComparisonStatus
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.StudySnapshot
import com.bm.backend.models.StudySnapshotFields
import com.bm.backend.models.StudySnapshotStatus
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic fixtures only (no customer data). */
class ActiveSummaryCalculatorTest {
    private val cups = "ES0000000000000000AA0F"
    private val zeros = PeriodValues(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val ones = PeriodValues(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)

    private fun d(s: String) = LocalDate.parse(s)
    private fun pv(vararg v: Double?) = PeriodValues(v[0], v[1], v[2], v[3], v[4], v[5])
    private fun row(start: LocalDate, end: LocalDate, active: PeriodValues) =
        StudyHistoryInterval(start, end, active, ones, ones)
    private fun history(rows: List<StudyHistoryInterval>) = StudyHistory(cups, StudyHistoryStatus.AVAILABLE, null, rows)
    private fun monthly(base: LocalDate, from: Int, to: Int, active: (Int) -> PeriodValues) =
        (from until to).map { i -> row(base.plusMonths(i.toLong()), base.plusMonths(i + 1L), active(i - from)) }

    private fun calc(
        h: StudyHistory,
        snapshot: StudySnapshot? = null,
        legacy: CleanedConsumptionData? = null
    ): ActiveSummary = ActiveSummaryCalculator.calculate(h, HistoryCoverageCalculator.calculate(h), snapshot, legacy)

    private fun snapshot(total: Double?, periods: PeriodValues?) = StudySnapshot(
        cups, StudySnapshotStatus.AVAILABLE, null,
        StudySnapshotFields(cups = cups, annualConsumptionKwh = total, annualConsumptionPeriodsKwh = periods), null
    )

    private fun legacy(p: List<Double>) = CleanedConsumptionData(
        cups, "3.0TD", "3.0TD", p.sum(), p[0], p[1], p[2], p[3], p[4], p[5],
        1.0, 1.0, 1.0, 1.0, 1.0, 1.0, "fee", "synthetic.json", "2026-10-29"
    )

    private fun ActiveSummary.window(kind: ActiveWindowKind) = windows.single { it.kind == kind }
    private fun cells(v: PeriodValues) = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)

    // Synthetic period targets reproducing the published sample totals.
    private val latestTargets = listOf(7201, 8225, 7467, 9031, 3684, 37069)
    private val previousTargets = listOf(6500, 7400, 6800, 8100, 3300, 32848)

    /** Splits each integer target over [n] rows (last row takes the remainder). */
    private fun split(targets: List<Int>, n: Int, i: Int): PeriodValues {
        val v = targets.map { t -> val each = t / n; (if (i == n - 1) t - each * (n - 1) else each).toDouble() }
        return pv(*v.toTypedArray())
    }

    /** Sample-shaped fixture: 2023-08-31 → 2026-07-31, split at 2026-03-02, zero row overlapping the last row. */
    private fun sampleRows(): List<StudyHistoryInterval> {
        val base = d("2023-08-31")
        val early = monthly(base, 0, 11) { pv(100.0, 100.0, 100.0, 100.0, 100.0, 100.0) }
        val previous = monthly(base, 11, 23) { split(previousTargets, 12, it) }
        // Latest window: 2025-07-31 .. 2026-05-31 (10 months, one split) + 2026-05-31 → 2026-07-31.
        val latestDates = (23..33).map { base.plusMonths(it.toLong()) }.toMutableList()
        latestDates.add(latestDates.indexOf(d("2026-03-31")), d("2026-03-02"))
        val bounds = latestDates + d("2026-07-31")
        val latest = (0 until bounds.size - 1).map { row(bounds[it], bounds[it + 1], split(latestTargets, bounds.size - 1, it)) }
        val zero = row(d("2026-05-31"), d("2026-06-30"), zeros)
        return early + previous + latest + zero
    }

    private val latestPeriods = pv(7201.0, 8225.0, 7467.0, 9031.0, 3684.0, 37069.0)

    @Test
    fun `A sample reconciliation with neutral zero overlap`() {
        val rows = sampleRows()
        assertEquals(d("2026-02-28"), rows.first { it.endDate == d("2026-03-02") }.startDate)
        val s = calc(history(rows), snapshot(72677.0, latestPeriods), legacy(latestTargets.map { it.toDouble() }))
        assertEquals(StudyHistoryStatus.AVAILABLE, s.status)
        assertEquals(d("2026-07-31"), s.anchorDate)
        assertFalse(s.partial)
        val prev = s.window(ActiveWindowKind.PREVIOUS_12M)
        assertEquals(d("2024-07-31"), prev.start)
        assertEquals(d("2025-07-31"), prev.endExclusive)
        assertEquals(ActiveWindowStatus.COMPLETE, prev.status)
        assertEquals(64948.0, prev.total)
        val latest = s.window(ActiveWindowKind.LATEST_12M)
        assertEquals(ActiveWindowStatus.COMPLETE, latest.status)
        assertEquals(72677.0, latest.total)
        assertNull(latest.partialTotal)
        assertEquals(latestPeriods, latest.periodTotals)
        assertEquals(1, latest.overlapDisclosures.size)
        assertEquals(d("2026-05-31"), latest.overlapDisclosures.single().start)
        assertEquals(d("2026-06-30"), latest.overlapDisclosures.single().endExclusive)
        assertEquals(100.0, cells(latest.shares!!).sumOf { it!! }, 1e-9)
        val sips = s.comparisons.single { it.source == AnnualSource.SIPS_SNAPSHOT }
        assertEquals(ComparisonStatus.COMPARED, sips.status)
        assertTrue(sips.periods.all { it.matches == true })
        assertEquals(true, sips.total.matches)
        assertEquals((72677.0 - 64948.0) / 64948.0 * 100, s.changePct!!, 1e-12)
        assertNull(s.changeReason)

        // D1 classification matches the coverage calculator's 12-month window.
        val h = history(rows)
        val cw = HistoryCoverageCalculator.calculate(h).families.getValue(MeasurementFamily.ACTIVE).windows.single { it.months == 12 }
        assertEquals(cw.start, latest.start)
        assertEquals(cw.endExclusive, latest.endExclusive)
        assertEquals(cw.contained.sorted(), latest.contained.sorted())
        assertEquals(cw.crossing.sorted(), latest.crossing.sorted())
        assertEquals(cw.complete, latest.status == ActiveWindowStatus.COMPLETE)
        assertEquals(cw.containedCoveredDays, latest.coveredDays)
    }

    @Test
    fun `B different source values are kept alongside history`() {
        val sipsPeriods = latestPeriods.copy(p6 = 37169.0)
        val legacyValues = latestTargets.map { it.toDouble() }.toMutableList().also { it[0] = 7000.0 }
        val s = calc(history(sampleRows()), snapshot(72777.0, sipsPeriods), legacy(legacyValues))
        val sips = s.comparisons.single { it.source == AnnualSource.SIPS_SNAPSHOT }
        val p6 = sips.periods.single { it.period == 6 }
        assertEquals(37169.0, p6.sourceKwh)
        assertEquals(37069.0, p6.historyKwh)
        assertEquals(-100.0, p6.differenceKwh)
        assertEquals(-100.0 / 37169.0 * 100, p6.differencePct!!, 1e-12)
        assertEquals(false, p6.matches)
        assertEquals(true, sips.periods.single { it.period == 1 }.matches)
        val leg = s.comparisons.single { it.source == AnnualSource.LEGACY_ANNUAL }
        assertEquals(ComparisonStatus.COMPARED, leg.status)
        assertEquals(201.0, leg.periods.single { it.period == 1 }.differenceKwh)
        assertEquals(true, leg.periods.single { it.period == 6 }.matches)
    }

    @Test
    fun `C crossing row is excluded never prorated`() {
        val first = (0 until 23).map { i -> row(d("2023-08-01").plusMonths(i.toLong()), d("2023-08-01").plusMonths(i + 1L), ones) }
        val crossing = row(d("2025-07-01"), d("2025-08-31"), pv(1000.0, 1000.0, 1000.0, 1000.0, 1000.0, 1000.0))
        val after = monthly(d("2025-08-31"), 0, 11) { ones }
        val rows = first + crossing + after
        val s = calc(history(rows), snapshot(66.0, ones))
        val latest = s.window(ActiveWindowKind.LATEST_12M)
        val prev = s.window(ActiveWindowKind.PREVIOUS_12M)
        assertTrue(23 in latest.crossing)
        assertTrue(23 in prev.crossing)
        assertFalse(23 in latest.contained)
        assertEquals(ActiveWindowStatus.INCOMPLETE, latest.status)
        assertEquals(ActiveWindowStatus.INCOMPLETE, prev.status)
        assertEquals(66.0, latest.partialTotal)
        assertNull(latest.total)
        assertNull(latest.shares)
        assertNull(prev.total)
        assertNull(s.changePct)
        assertEquals("latest_incomplete", s.changeReason)
        val sips = s.comparisons.single { it.source == AnnualSource.SIPS_SNAPSHOT }
        assertEquals(ComparisonStatus.NOT_COMPARABLE, sips.status)
        assertEquals(66.0, sips.total.sourceKwh)
        assertNull(sips.total.historyKwh)
        assertNull(sips.total.differenceKwh)
        assertTrue(s.partial)
    }

    @Test
    fun `D short history is partial without projection`() {
        val rows = monthly(d("2025-11-01"), 0, 8) { ones }
        val s = calc(history(rows))
        assertTrue(s.partial)
        assertEquals(d("2025-11-01"), s.historyStart)
        assertEquals(d("2026-07-01"), s.historyEnd)
        val latest = s.window(ActiveWindowKind.LATEST_12M)
        assertEquals(ActiveWindowStatus.INCOMPLETE, latest.status)
        assertEquals(ChronoUnit.DAYS.between(d("2025-11-01"), d("2026-07-01")), latest.coveredDays)
        assertEquals(ChronoUnit.DAYS.between(d("2025-07-01"), d("2026-07-01")), latest.windowDays)
        assertEquals(48.0, latest.partialTotal)
        assertEquals(1, latest.gaps.size)
        assertEquals(ActiveWindowStatus.UNAVAILABLE, s.window(ActiveWindowKind.PREVIOUS_12M).status)
        val fieldNames = ActiveSummary::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fieldNames.none { "project" in it || "estimat" in it || "annualis" in it })
    }

    @Test
    fun `E non-neutral overlap blocks firm figures`() {
        val rows = monthly(d("2025-07-01"), 0, 12) { pv(10.0, 10.0, 10.0, 10.0, 10.0, 10.0) } +
            row(d("2026-01-15"), d("2026-02-15"), pv(900.0, 900.0, 900.0, 900.0, 900.0, 900.0))
        val s = calc(history(rows), snapshot(720.0, null))
        val latest = s.window(ActiveWindowKind.LATEST_12M)
        assertEquals(ActiveWindowStatus.OVERLAP_UNRESOLVED, latest.status)
        assertNull(latest.total)
        assertNull(latest.partialTotal)
        assertNull(latest.shares)
        assertEquals(13, latest.contained.size)
        assertTrue(latest.overlapDisclosures.isNotEmpty())
        val excluded = latest.overlapDisclosures.flatMap { listOf(it.firstIndex, it.secondIndex) }.toSet()
        assertTrue(s.highIntervals.none { it.index in excluded })
        assertTrue(12 !in s.highIntervals.map { it.index })
        // A6: no COMPLETE/INCOMPLETE window exists (previous is UNAVAILABLE), so no ranking.
        assertEquals(ActiveWindowStatus.UNAVAILABLE, s.window(ActiveWindowKind.PREVIOUS_12M).status)
        assertTrue(s.highIntervals.isEmpty())
        assertEquals(ComparisonStatus.NOT_COMPARABLE, s.comparisons.single { it.source == AnnualSource.SIPS_SNAPSHOT }.status)
    }

    @Test
    fun `E2 overlap with a partially blank zero row is not value-neutral`() {
        val rows = monthly(d("2025-07-01"), 0, 12) { pv(10.0, 10.0, 10.0, 10.0, 10.0, 10.0) } +
            row(d("2026-01-01"), d("2026-02-01"), pv(0.0, null, null, null, null, null))
        val latest = calc(history(rows)).window(ActiveWindowKind.LATEST_12M)
        assertEquals(ActiveWindowStatus.OVERLAP_UNRESOLVED, latest.status)
        assertNull(latest.total)
        assertNull(latest.partialTotal)
        assertNull(latest.shares)
    }

    @Test
    fun `F2 zero history against non-zero source is minus 100 percent`() {
        val rows = monthly(d("2025-07-01"), 0, 12) { zeros }
        val sips = calc(history(rows), snapshot(500.0, null)).comparisons.single { it.source == AnnualSource.SIPS_SNAPSHOT }
        assertEquals(-500.0, sips.total.differenceKwh)
        assertEquals(-100.0, sips.total.differencePct)
    }

    @Test
    fun `F zero total gives null shares and percentages`() {
        val rows = monthly(d("2024-07-01"), 0, 24) { zeros }
        val s = calc(history(rows), snapshot(0.0, zeros))
        val latest = s.window(ActiveWindowKind.LATEST_12M)
        assertEquals(ActiveWindowStatus.COMPLETE, latest.status)
        assertEquals(0.0, latest.total)
        assertNull(latest.shares)
        assertEquals("zero_total", latest.reason)
        val sips = s.comparisons.single { it.source == AnnualSource.SIPS_SNAPSHOT }
        assertEquals(ComparisonStatus.COMPARED, sips.status)
        assertTrue((sips.periods + sips.total).all { it.differencePct == null && it.differenceKwh == 0.0 })
        assertNull(s.changePct)
        assertEquals("previous_zero", s.changeReason)
    }

    @Test
    fun `G null cells are never treated as zero`() {
        val rows = monthly(d("2025-07-01"), 0, 12) { i ->
            if (i % 2 == 0) pv(10.0, 20.0, 30.0, null, null, null) else pv(10.0, 20.0, 30.0, 5.0, 5.0, null)
        }
        val latest = calc(history(rows)).window(ActiveWindowKind.LATEST_12M)
        assertEquals(ActiveWindowStatus.COMPLETE, latest.status)
        assertEquals(6 * 3 + 6 * 1, latest.nullCells)
        assertNull(latest.periodTotals.p6)
        assertEquals(30.0, latest.periodTotals.p4)
        assertEquals(120.0 + 240.0 + 360.0 + 30.0 + 30.0, latest.total)
        val shares = latest.shares!!
        assertNull(shares.p6)
        assertEquals(100.0, cells(shares).filterNotNull().sum(), 1e-9)
    }

    @Test
    fun `H high intervals ranked by kwh per day top 3 with deterministic ties`() {
        val rows = listOf(
            row(d("2025-07-01"), d("2025-10-01"), pv(920.0, 0.0, 0.0, 0.0, 0.0, 0.0)), // 92 d, 10/d, biggest raw total
            row(d("2025-10-01"), d("2025-10-11"), pv(0.0, 300.0, 0.0, 0.0, 0.0, 0.0)), // 10 d, 30/d
            row(d("2025-10-11"), d("2025-10-21"), pv(0.0, 0.0, 200.0, 0.0, 0.0, 0.0)), // 10 d, 20/d
            row(d("2025-10-21"), d("2025-10-31"), pv(0.0, 0.0, 0.0, 200.0, 0.0, 0.0)), // 10 d, 20/d (tie, later)
            row(d("2025-10-31"), d("2026-07-01"), pv(0.0, 0.0, 0.0, 0.0, 0.0, 243.0))
        )
        val s = calc(history(rows))
        assertEquals(3, s.highIntervalCount)
        assertEquals(listOf(1, 2, 3), s.highIntervals.map { it.index })
        assertEquals(30.0, s.highIntervals[0].kwhPerDay)
        assertEquals(2, s.highIntervals[0].dominantPeriod)
        assertEquals(10L, s.highIntervals[0].days)
        assertEquals(300.0, s.highIntervals[0].totalKwh)
    }

    @Test
    fun `I unavailable inputs never throw`() {
        val unavailable = StudyHistory.unavailable(cups, "history_missing")
        val u = calc(unavailable, null, null)
        assertEquals(StudyHistoryStatus.UNAVAILABLE, u.status)
        assertEquals("history_missing", u.reason)
        assertTrue(u.comparisons.all { it.status == ComparisonStatus.SOURCE_UNAVAILABLE })
        val empty = calc(history(emptyList()))
        assertEquals(StudyHistoryStatus.UNAVAILABLE, empty.status)
        assertNotNull(empty.reason)
        val rows = monthly(d("2025-07-01"), 0, 12) { ones }
        val s = calc(history(rows), StudySnapshot.unavailable(cups, "snapshot_missing"), null)
        assertTrue(s.comparisons.all { it.status == ComparisonStatus.SOURCE_UNAVAILABLE })
        assertEquals(2, s.comparisons.size)
        val mismatched = ActiveSummaryCalculator.calculate(history(rows), com.bm.backend.models.HistoryCoverage.unavailable("x"), null, null)
        assertEquals(StudyHistoryStatus.UNAVAILABLE, mismatched.status)
    }

    @Test
    fun `J inputs are unchanged`() {
        val rows = sampleRows()
        val h = history(rows)
        val before = h.copy(intervals = rows.toList())
        val coverage = HistoryCoverageCalculator.calculate(h)
        val coverageBefore = coverage.copy()
        val snap = snapshot(72677.0, latestPeriods)
        val leg = legacy(latestTargets.map { it.toDouble() })
        ActiveSummaryCalculator.calculate(h, coverage, snap, leg)
        assertEquals(before, h)
        assertEquals(coverageBefore, coverage)
        assertEquals(snapshot(72677.0, latestPeriods), snap)
        assertEquals(legacy(latestTargets.map { it.toDouble() }), leg)
    }
}
