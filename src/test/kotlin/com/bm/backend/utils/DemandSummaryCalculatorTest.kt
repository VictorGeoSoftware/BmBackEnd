package com.bm.backend.utils

import com.bm.backend.models.DemandContractedSource
import com.bm.backend.models.DemandPeriodStatistic
import com.bm.backend.models.DemandSeriesPoint
import com.bm.backend.models.DemandSummary
import com.bm.backend.models.DemandWindowStatus
import com.bm.backend.models.DemandWindowSummary
import com.bm.backend.models.HistoryCoverage
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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic fixtures only (no customer data). */
class DemandSummaryCalculatorTest {
    private val cups = "ES0000000000000000AA0F"
    private val base = LocalDate.parse("2025-10-01")
    private val anchor = LocalDate.parse("2026-10-01")
    private val zeros = PeriodValues(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val nulls = PeriodValues(null, null, null, null, null, null)
    private val energy = PeriodValues(10.0, 20.0, 30.0, 40.0, 50.0, 60.0)

    private fun pv(vararg v: Double?) = PeriodValues(v[0], v[1], v[2], v[3], v[4], v[5])
    private fun d(s: String) = LocalDate.parse(s)
    private fun row(start: LocalDate, end: LocalDate, demand: PeriodValues, active: PeriodValues = energy) =
        StudyHistoryInterval(start, end, active, energy, demand)
    private fun history(rows: List<StudyHistoryInterval>) = StudyHistory(cups, StudyHistoryStatus.AVAILABLE, null, rows)
    private fun monthly(demand: (Int) -> PeriodValues) =
        (0 until 12).map { i -> row(base.plusMonths(i.toLong()), base.plusMonths(i + 1L), demand(i)) }
    private fun snapshot(contracted: PeriodValues?) = StudySnapshot(
        cups, StudySnapshotStatus.AVAILABLE, null,
        StudySnapshotFields(cups = cups, contractedPowerKw = contracted, maxPowerBieKw = 99.0, maxPowerApmKw = 98.0),
        null
    )
    private val contracted151 = pv(15.1, 15.1, 15.1, 15.1, 15.1, 15.1)

    private fun calc(h: StudyHistory, snap: StudySnapshot? = snapshot(contracted151)) =
        DemandSummaryCalculator.calculate(h, HistoryCoverageCalculator.calculate(h), snap)

    private fun close(e: Double, a: Double?) = assertTrue(a != null && abs(e - a) < 1e-9, "expected $e got $a")

    private fun assertFinite(s: DemandSummary) = s.periods.forEach { p ->
        listOfNotNull(p.peakKw, p.meanKw, p.contractedKw, p.peakMinusContractedKw, p.peakPctOfContracted)
            .forEach { assertTrue(it.isFinite()) }
    }

    @Test
    fun `A known statistics on a complete window`() {
        val values = listOf(10.0, 12.5, 16.103, 9.0, 0.0, 11.0, 14.0, 13.0, 12.0, 8.0, 7.0, 16.103)
        val h = history(monthly { i -> pv(values[i], 5.0, 4.0, 3.0, 2.0, 1.0 + i) })
        val s = calc(h)
        assertEquals(StudyHistoryStatus.AVAILABLE, s.status)
        assertEquals(anchor, s.anchorDate)
        val w = assertNotNull(s.window)
        assertEquals(DemandWindowStatus.COMPLETE, w.status)
        assertFalse(w.partial)
        assertEquals(base, w.start); assertEquals(anchor, w.endExclusive)
        assertEquals(w.windowDays, w.coveredDays)
        assertEquals(DemandContractedSource.SNAPSHOT_CONTRACTED_POWER_KW, s.contractedSource)
        assertNull(s.snapshotReason)
        val p1 = s.periods[0]
        assertEquals(1, p1.period); assertEquals(12, p1.readings)
        assertEquals(16.103, p1.peakKw)
        assertEquals(2, p1.peakIndex) // tie: earliest start
        assertEquals(base.plusMonths(2), p1.peakStart); assertEquals(base.plusMonths(3), p1.peakEndExclusive)
        close(values.sum() / 12, p1.meanKw); assertNull(p1.meanReason)
        assertEquals(15.1, p1.contractedKw)
        close(16.103 - 15.1, p1.peakMinusContractedKw)
        close(16.103 / 15.1 * 100, p1.peakPctOfContracted)
        assertNull(p1.comparisonReason); assertNull(p1.reason)
        val p6 = s.periods[5]
        assertEquals(12.0, p6.peakKw); assertEquals(11, p6.peakIndex)
        close((1..12).sum() / 12.0, p6.meanKw)
        assertEquals(12, s.series.size)
        assertEquals((0 until 12).toList(), s.series.map { it.index })
        assertFinite(s)
    }

    @Test
    fun `B zero-energy row with demand overlapping is kept, disclosed and may be the peak`() {
        val first = row(d("2026-05-31"), d("2026-07-31"), pv(8.0, 8.0, 8.0, 8.0, 8.0, 9.0))
        val zeroRow = row(d("2026-05-31"), d("2026-06-30"), pv(8.0, 8.0, 8.0, 8.0, 8.0, 12.5), active = zeros)
        val s = calc(history(listOf(first, zeroRow)))
        val w = assertNotNull(s.window)
        assertTrue(w.status != DemandWindowStatus.UNAVAILABLE)
        assertEquals(2, s.series.size)
        val zp = s.series.single { it.index == 1 }
        assertTrue(zp.activeAllZero); assertTrue(zp.inOverlap)
        assertFalse(s.series.single { it.index == 0 }.activeAllZero)
        assertEquals(1, w.overlapDisclosures.size)
        val p6 = s.periods[5]
        assertEquals(12.5, p6.peakKw); assertEquals(1, p6.peakIndex)
        assertEquals(d("2026-05-31"), p6.peakStart); assertEquals(d("2026-06-30"), p6.peakEndExclusive)
        assertNull(p6.meanKw)
        assertEquals(2, p6.readings)
    }

    @Test
    fun `B2 overlap on a complete window gives mean reason overlap`() {
        val rows = monthly { pv(5.0, 5.0, 5.0, 5.0, 5.0, 5.0) } +
            row(base, base.plusMonths(1), pv(6.0, 6.0, 6.0, 6.0, 6.0, 6.0), active = zeros)
        val s = calc(history(rows))
        assertEquals(DemandWindowStatus.COMPLETE, s.window!!.status)
        assertEquals(DemandSummaryCalculator.OVERLAP, s.periods[0].meanReason)
        assertNull(s.periods[0].meanKw)
        assertEquals(6.0, s.periods[0].peakKw)
    }

    @Test
    fun `C inactive periods are missing, partial nulls counted never zero`() {
        val h = history(monthly { i -> pv(5.0, if (i == 3) null else 4.0, 3.0, null, null, null) })
        val s = calc(h)
        (3..5).forEach { p ->
            val st = s.periods[p]
            assertNull(st.peakKw); assertEquals(0, st.readings)
            assertEquals(DemandSummaryCalculator.NO_READINGS, st.reason)
            assertNull(st.meanKw); assertNull(st.peakMinusContractedKw); assertNull(st.peakPctOfContracted)
            assertEquals(DemandSummaryCalculator.NO_READINGS, st.comparisonReason)
        }
        assertEquals(5.0, s.periods[0].peakKw)
        assertEquals(11, s.periods[1].readings)
        close(4.0, s.periods[1].meanKw)
        assertEquals(12 * 3 + 1, s.window!!.nullCells)
        assertNull(s.series[3].values.p2)
    }

    @Test
    fun `D recorded zero is a reading`() {
        val s = calc(history(monthly { zeros }))
        s.periods.forEach {
            assertEquals(0.0, it.peakKw); assertEquals(0.0, it.meanKw); assertEquals(12, it.readings)
            assertEquals(0.0, it.peakPctOfContracted); assertNull(it.reason)
        }
    }

    @Test
    fun `E gap or crossing gives incomplete partial statistics from contained rows only`() {
        val withGap = monthly { pv(5.0, 5.0, 5.0, 5.0, 5.0, 5.0) }.filterIndexed { i, _ -> i != 4 }
        val g = calc(history(withGap))
        assertEquals(DemandWindowStatus.INCOMPLETE, g.window!!.status)
        assertTrue(g.window!!.partial); assertEquals(1, g.window!!.gaps.size)
        assertEquals(DemandSummaryCalculator.WINDOW_INCOMPLETE, g.periods[0].meanReason)
        assertEquals(5.0, g.periods[0].peakKw)
        assertEquals(15.1, g.periods[0].contractedKw)

        val crossingRow = row(base.minusDays(10), base.plusDays(10), pv(99.0, 99.0, 99.0, 99.0, 99.0, 99.0))
        val rows = listOf(crossingRow) + monthly { pv(5.0, 5.0, 5.0, 5.0, 5.0, 5.0) }.drop(1)
        val c = calc(history(rows))
        val w = c.window!!
        assertEquals(DemandWindowStatus.INCOMPLETE, w.status)
        assertEquals(listOf(0), w.crossing)
        assertEquals(5.0, c.periods[0].peakKw)
        assertTrue(c.series.none { it.index == 0 })
        assertNull(c.periods[0].meanKw)
    }

    @Test
    fun `F snapshot cases`() {
        val h = history(monthly { pv(5.0, 5.0, 5.0, 5.0, 5.0, 5.0) })
        listOf(null, StudySnapshot.unavailable(cups, "x")).forEach { snap ->
            val s = calc(h, snap)
            assertEquals(DemandSummaryCalculator.SNAPSHOT_UNAVAILABLE, s.snapshotReason)
            s.periods.forEach {
                assertNull(it.contractedKw); assertNull(it.peakPctOfContracted)
                assertEquals(DemandSummaryCalculator.SNAPSHOT_UNAVAILABLE, it.comparisonReason)
            }
        }
        val noPowers = calc(h, snapshot(null))
        assertEquals(DemandSummaryCalculator.SNAPSHOT_UNAVAILABLE, noPowers.snapshotReason)
        val s = calc(h, snapshot(pv(15.1, 15.1, null, 0.0, 15.1, 15.1)))
        assertEquals(DemandSummaryCalculator.CONTRACTED_MISSING, s.periods[2].comparisonReason)
        assertNull(s.periods[2].peakPctOfContracted)
        assertEquals(DemandSummaryCalculator.CONTRACTED_ZERO, s.periods[3].comparisonReason)
        assertEquals(0.0, s.periods[3].contractedKw)
        assertNull(s.periods[3].peakPctOfContracted); assertNull(s.periods[3].peakMinusContractedKw)
        assertFinite(s)
    }

    @Test
    fun `G units unchanged`() {
        val s = calc(history(monthly { pv(16.103, 1.0, 1.0, 1.0, 1.0, 1.0) }))
        assertEquals(16.103, s.periods[0].peakKw)
        assertEquals(15.1, s.periods[0].contractedKw)
        assertEquals(16.103, s.series[0].values.p1)
    }

    @Test
    fun `H unavailable inputs`() {
        val unavailable = StudyHistory.unavailable(cups, "history_missing")
        assertEquals("history_missing", calc(unavailable).reason)
        val h = history(monthly { pv(5.0, 5.0, 5.0, 5.0, 5.0, 5.0) })
        val cov = DemandSummaryCalculator.calculate(h, HistoryCoverage.unavailable("cov_x"), null)
        assertEquals(StudyHistoryStatus.UNAVAILABLE, cov.status); assertEquals("cov_x", cov.reason)
        val noAnchor = HistoryCoverageCalculator.calculate(h).copy(anchorDate = null)
        assertEquals(DemandSummaryCalculator.NO_ANCHOR, DemandSummaryCalculator.calculate(h, noAnchor, null).reason)
        val energyOnly = history(monthly { nulls })
        val e = calc(energyOnly)
        assertEquals(StudyHistoryStatus.UNAVAILABLE, e.status)
        assertEquals(DemandSummaryCalculator.NO_DEMAND_ROWS, e.reason)
        assertNull(e.window); assertTrue(e.series.isEmpty())
        val broken = DemandSummaryCalculator.calculate(h, HistoryCoverageCalculator.calculate(h).copy(invertedIntervals = listOf(-1)), null)
        assertTrue(broken.status == StudyHistoryStatus.AVAILABLE || broken.reason == DemandSummaryCalculator.CALCULATION_ERROR)
    }

    @Test
    fun `I parity with coverage classification and purity`() {
        val fixtures = listOf(
            monthly { pv(5.0, 5.0, 5.0, 5.0, 5.0, 5.0) },
            monthly { pv(5.0, 5.0, 5.0, 5.0, 5.0, 5.0) }.filterIndexed { i, _ -> i != 4 },
            listOf(row(base.minusDays(10), base.plusDays(10), zeros)) + monthly { zeros }.drop(1)
        )
        fixtures.forEach { rows ->
            val h = history(rows)
            val before = h.copy(intervals = h.intervals.toList())
            val cov = HistoryCoverageCalculator.calculate(h)
            val s = DemandSummaryCalculator.calculate(h, cov, snapshot(contracted151))
            assertEquals(before, h)
            val wc = cov.families.getValue(MeasurementFamily.MAX_DEMAND).windows.single { it.months == 12 }
            val w = s.window!!
            assertEquals(wc.start, w.start); assertEquals(wc.endExclusive, w.endExclusive)
            assertEquals(wc.contained.sorted(), w.contained.sorted())
            assertEquals(wc.crossing.sorted(), w.crossing.sorted())
            assertEquals(wc.containedCoveredDays, w.coveredDays)
            assertEquals(wc.complete, w.status == DemandWindowStatus.COMPLETE)
        }
        listOf(DemandSummary::class, DemandWindowSummary::class, DemandPeriodStatistic::class, DemandSeriesPoint::class)
            .forEach { k -> assertTrue(k.java.declaredMethods.none { it.name == "serializer" } && k.java.declaredClasses.none { it.simpleName == "\$serializer" }, "${k.simpleName} must not be serializable") }
    }

    @Test
    fun `J no optimisation or penalty vocabulary in model properties`() {
        val banned = listOf("penalty", "excess", "exceed", "recommend", "optimal", "cost", "charge", "bie", "apm")
        listOf(DemandSummary::class, DemandWindowSummary::class, DemandPeriodStatistic::class, DemandSeriesPoint::class)
            .flatMap { k -> k.java.declaredFields.map { it.name.lowercase() } }
            .forEach { name -> banned.forEach { b -> assertFalse(name.contains(b), "$name contains $b") } }
    }
}
