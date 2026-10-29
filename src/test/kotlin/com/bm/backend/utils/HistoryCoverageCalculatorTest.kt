package com.bm.backend.utils

import com.bm.backend.models.CoverageGap
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.MeasurementFamily.ACTIVE
import com.bm.backend.models.MeasurementFamily.MAX_DEMAND
import com.bm.backend.models.MeasurementFamily.REACTIVE
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.StudyHistoryStatus
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryCoverageCalculatorTest {
    private val cups = "ES0000000000000000AA0F"
    private val full = PeriodValues(10.0, 20.0, 30.0, 40.0, 50.0, 60.0)
    private val threePeriods = PeriodValues(10.0, 20.0, 30.0, null, null, null)
    private val zeros = PeriodValues(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val blank = PeriodValues(null, null, null, null, null, null)

    private fun d(s: String) = LocalDate.parse(s)

    private fun row(
        start: String,
        end: String,
        active: PeriodValues = full,
        reactive: PeriodValues = full,
        maxDemand: PeriodValues = full
    ) = StudyHistoryInterval(d(start), d(end), active, reactive, maxDemand)

    private fun history(vararg rows: StudyHistoryInterval) =
        StudyHistory(cups, StudyHistoryStatus.AVAILABLE, null, rows.toList())

    private fun contiguous(start: LocalDate, count: Int): List<StudyHistoryInterval> =
        (0 until count).map { i ->
            StudyHistoryInterval(start.plusMonths(i.toLong()), start.plusMonths(i + 1L), full, full, full)
        }

    @Test
    fun `A non-month rows are measured by their own dates`() {
        val c = HistoryCoverageCalculator.calculate(
            history(row("2025-01-01", "2025-01-18"), row("2025-01-18", "2025-03-04"), row("2025-03-04", "2025-06-04"))
        )
        val active = c.families.getValue(ACTIVE)
        assertEquals(154L, active.coveredDays)
        assertEquals(d("2025-01-01"), active.startDate)
        assertEquals(d("2025-06-04"), active.endDate)
        assertEquals(3, active.usableIntervals)
        assertTrue(active.gaps.isEmpty())
        assertTrue(active.overlaps.isEmpty())
        assertFalse(active.hasUnresolvedOverlaps)
    }

    @Test
    fun `B more than 24 rows are classified against the 24-month window by date`() {
        val rows = contiguous(d("2024-01-31"), 30)
        val c = HistoryCoverageCalculator.calculate(StudyHistory(cups, StudyHistoryStatus.AVAILABLE, null, rows))
        assertEquals(d("2026-07-31"), c.anchorDate)
        val w24 = c.families.getValue(ACTIVE).windows.single { it.months == 24 }
        assertEquals(d("2024-07-31"), w24.start)
        assertEquals(d("2026-07-31"), w24.endExclusive)
        assertEquals((6 until 30).toList(), w24.contained)
        assertTrue(w24.crossing.isEmpty())
        assertTrue(w24.complete)
        assertEquals(w24.containedCoveredDays, java.time.temporal.ChronoUnit.DAYS.between(w24.start, w24.endExclusive))
        val w12 = c.families.getValue(ACTIVE).windows.single { it.months == 12 }
        assertEquals(d("2025-07-31"), w12.start)
        assertEquals((18 until 30).toList(), w12.contained)
        assertTrue(w12.complete)
    }

    @Test
    fun `C supplier-change split with shared endpoint is contiguous`() {
        val c = HistoryCoverageCalculator.calculate(history(row("2026-02-01", "2026-03-02"), row("2026-03-02", "2026-04-01")))
        MeasurementFamily.entries.forEach { f ->
            val fam = c.families.getValue(f)
            assertTrue(fam.gaps.isEmpty())
            assertTrue(fam.overlaps.isEmpty())
            assertEquals(59L, fam.coveredDays)
        }
    }

    @Test
    fun `D missing days are reported as a gap`() {
        val c = HistoryCoverageCalculator.calculate(history(row("2025-01-01", "2025-01-31"), row("2025-02-10", "2025-03-01")))
        assertEquals(listOf(CoverageGap(d("2025-01-31"), d("2025-02-10"), 10)), c.families.getValue(ACTIVE).gaps)
        assertEquals(49L, c.families.getValue(ACTIVE).coveredDays)
    }

    @Test
    fun `E overlap is disclosed and never double counted`() {
        val h = history(
            row("2026-05-31", "2026-07-31", active = full, reactive = blank, maxDemand = full),
            row("2026-05-31", "2026-06-30", active = zeros, reactive = blank, maxDemand = threePeriods)
        )
        val before = h.copy(intervals = h.intervals.toList())
        val c = HistoryCoverageCalculator.calculate(h)
        listOf(ACTIVE, MAX_DEMAND).forEach { f ->
            val fam = c.families.getValue(f)
            assertEquals(61L, fam.coveredDays)
            assertTrue(fam.hasUnresolvedOverlaps)
            val o = fam.overlaps.single()
            assertEquals(setOf(0, 1), setOf(o.firstIndex, o.secondIndex))
            assertEquals(d("2026-05-31"), o.start)
            assertEquals(d("2026-06-30"), o.endExclusive)
            assertEquals(30L, o.days)
            assertEquals(2, fam.usableIntervals)
        }
        assertEquals(listOf(0, 1), c.families.getValue(REACTIVE).missingIntervals)
        assertFalse(c.families.getValue(REACTIVE).hasUnresolvedOverlaps)
        assertEquals(before, h)
    }

    @Test
    fun `F blank cells are per family and nulls are only counted`() {
        val c = HistoryCoverageCalculator.calculate(
            history(row("2025-01-01", "2025-02-01", active = threePeriods, reactive = blank, maxDemand = threePeriods))
        )
        assertEquals(listOf(0), c.families.getValue(REACTIVE).missingIntervals)
        assertEquals(0, c.families.getValue(REACTIVE).usableIntervals)
        assertEquals(0L, c.families.getValue(REACTIVE).coveredDays)
        assertNull(c.families.getValue(REACTIVE).startDate)
        val active = c.families.getValue(ACTIVE)
        assertEquals(1, active.usableIntervals)
        assertTrue(active.missingIntervals.isEmpty())
        assertEquals(3, active.nullValues)
    }

    @Test
    fun `G unsorted rows give identical results with original indices`() {
        val a = row("2025-01-01", "2025-01-31")
        val b = row("2025-02-10", "2025-03-01")
        val x = row("2025-02-20", "2025-03-10")
        val sorted = HistoryCoverageCalculator.calculate(history(a, b, x)).families.getValue(ACTIVE)
        val shuffledHistory = history(x, a, b)
        val shuffled = HistoryCoverageCalculator.calculate(shuffledHistory).families.getValue(ACTIVE)
        assertEquals(sorted.gaps, shuffled.gaps)
        assertEquals(sorted.coveredDays, shuffled.coveredDays)
        assertEquals(sorted.overlaps.map { it.start to it.days }, shuffled.overlaps.map { it.start to it.days })
        val o = shuffled.overlaps.single()
        assertEquals(2 to 0, o.firstIndex to o.secondIndex)
        assertEquals(listOf(x, a, b), shuffledHistory.intervals)
    }

    @Test
    fun `H short history is incomplete and crossing intervals are listed not prorated`() {
        val short = contiguous(d("2025-11-30"), 8)
        val c = HistoryCoverageCalculator.calculate(StudyHistory(cups, StudyHistoryStatus.AVAILABLE, null, short))
        val fam = c.families.getValue(ACTIVE)
        assertEquals(d("2025-11-30"), fam.startDate)
        assertFalse(fam.windows.single { it.months == 12 }.complete)

        val crossing = HistoryCoverageCalculator.calculate(
            history(row("2025-07-01", "2025-08-31"), row("2025-08-31", "2026-07-31"))
        )
        assertEquals(d("2026-07-31"), crossing.anchorDate)
        val w12 = crossing.families.getValue(ACTIVE).windows.single { it.months == 12 }
        assertEquals(listOf(0), w12.crossing)
        assertEquals(listOf(1), w12.contained)
        assertEquals(334L, w12.containedCoveredDays)
        assertFalse(w12.complete)
        val w24 = crossing.families.getValue(ACTIVE).windows.single { it.months == 24 }
        assertEquals(listOf(0, 1), w24.contained)
        assertFalse(w24.complete)
    }

    @Test
    fun `I inverted, zero-length, empty and unavailable never throw`() {
        val c = HistoryCoverageCalculator.calculate(
            history(row("2025-03-01", "2025-02-01"), row("2025-01-01", "2025-01-01"), row("2025-01-01", "2025-02-01"))
        )
        assertEquals(listOf(0), c.invertedIntervals)
        val fam = c.families.getValue(ACTIVE)
        assertEquals(listOf(1), fam.zeroLengthIntervals)
        assertEquals(2, fam.usableIntervals)
        assertEquals(31L, fam.coveredDays)
        assertTrue(fam.overlaps.isEmpty())
        assertEquals(d("2025-02-01"), c.anchorDate)

        val empty = HistoryCoverageCalculator.calculate(history())
        assertEquals(StudyHistoryStatus.AVAILABLE, empty.status)
        assertNull(empty.anchorDate)
        assertEquals(0L, empty.families.getValue(ACTIVE).coveredDays)
        assertTrue(empty.families.getValue(ACTIVE).windows.isEmpty())

        val unavailable = HistoryCoverageCalculator.calculate(StudyHistory.unavailable(cups, "missing"))
        assertEquals(StudyHistoryStatus.UNAVAILABLE, unavailable.status)
        assertEquals("missing", unavailable.reason)
        assertTrue(unavailable.families.isEmpty())
    }
}
