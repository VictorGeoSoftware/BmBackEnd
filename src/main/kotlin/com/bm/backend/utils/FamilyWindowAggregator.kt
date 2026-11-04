package com.bm.backend.utils

import com.bm.backend.models.CoverageGap
import com.bm.backend.models.CoverageOverlap
import com.bm.backend.models.FamilyWindowAggregate
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.TariffPeriodProfile
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Family-agnostic D1 window aggregation: a row covers `[startDate, endDate)`; only rows with at least one
 * non-null cell of the family count; fully contained rows are summed, crossing rows are listed (never prorated).
 * Null cells are counted only over the tariff's [periods] (default P1–P6).
 * An overlap is value-neutral only when one of the pair has all six cells exactly 0.0 (nulls never qualify).
 */
object FamilyWindowAggregator {
    private data class Span(val index: Int, val start: LocalDate, val endExclusive: LocalDate, val cells: List<Double?>)

    fun days(start: LocalDate, endExclusive: LocalDate): Long = ChronoUnit.DAYS.between(start, endExclusive)
    fun cells(v: PeriodValues): List<Double?> = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)
    fun values(c: List<Double?>): PeriodValues = PeriodValues(c[0], c[1], c[2], c[3], c[4], c[5])
    fun isZeroRow(v: PeriodValues): Boolean = cells(v).all { it == 0.0 }

    fun familyValues(interval: StudyHistoryInterval, family: MeasurementFamily): PeriodValues = when (family) {
        MeasurementFamily.ACTIVE -> interval.active
        MeasurementFamily.REACTIVE -> interval.reactive
        MeasurementFamily.MAX_DEMAND -> interval.maxDemand
    }

    /** True when any non-inverted row has at least one non-null cell of [family]. */
    fun hasRows(history: StudyHistory, family: MeasurementFamily, inverted: Set<Int>): Boolean =
        spans(history, family, inverted).isNotEmpty()

    fun aggregate(
        history: StudyHistory,
        family: MeasurementFamily,
        inverted: Set<Int>,
        start: LocalDate,
        end: LocalDate,
        periods: List<Int> = TariffPeriodProfile.ALL_PERIODS
    ): FamilyWindowAggregate {
        val spans = spans(history, family, inverted)
        val contained = spans.filter { it.start >= start && it.endExclusive <= end }
        val crossing = spans.filter { !(it.start >= start && it.endExclusive <= end) && it.start < end && it.endExclusive > start }
        val overlaps = overlaps(contained)
        val byIndex = contained.associateBy { it.index }
        val zero = { i: Int -> byIndex.getValue(i).cells.all { it == 0.0 } }
        val blocking = overlaps.filter { !zero(it.firstIndex) && !zero(it.secondIndex) }
            .flatMap { listOf(it.firstIndex, it.secondIndex) }.toSet()
        val gaps = mutableListOf<CoverageGap>()
        var runningEnd = start
        for (s in contained) {
            if (s.start > runningEnd) gaps += CoverageGap(runningEnd, s.start, days(runningEnd, s.start))
            if (s.endExclusive > runningEnd) runningEnd = s.endExclusive
        }
        if (end > runningEnd) gaps += CoverageGap(runningEnd, end, days(runningEnd, end))
        val sums = (0 until 6).map { p ->
            val present = contained.mapNotNull { it.cells[p] }
            if (present.isEmpty()) null else present.sum()
        }
        return FamilyWindowAggregate(
            start = start,
            endExclusive = end,
            windowDays = days(start, end),
            coveredDays = unionDays(contained),
            contained = contained.map { it.index },
            crossing = crossing.map { it.index },
            gaps = gaps,
            overlaps = overlaps,
            blockingIndices = blocking,
            periodSums = values(sums),
            nullCells = contained.sumOf { s -> periods.count { p -> s.cells[p - 1] == null } },
            nullCellPeriods = periods.filter { p -> contained.any { it.cells[p - 1] == null } }.toSet()
        )
    }

    private fun spans(history: StudyHistory, family: MeasurementFamily, inverted: Set<Int>): List<Span> =
        history.intervals.indices
            .filter { it !in inverted && history.intervals[it].startDate <= history.intervals[it].endDate }
            .map { i -> history.intervals[i].let { Span(i, it.startDate, it.endDate, cells(familyValues(it, family))) } }
            .filter { s -> s.cells.any { it != null } }
            .sortedWith(compareBy<Span>({ it.start }, { it.endExclusive }, { it.index }))

    private fun overlaps(sorted: List<Span>): List<CoverageOverlap> {
        val result = mutableListOf<CoverageOverlap>()
        for (a in sorted.indices) for (b in a + 1 until sorted.size) {
            val x = sorted[a]; val y = sorted[b]
            if (y.start >= x.endExclusive) break
            val s = maxOf(x.start, y.start)
            val e = minOf(x.endExclusive, y.endExclusive)
            if (e > s) result += CoverageOverlap(x.index, y.index, s, e, days(s, e))
        }
        return result
    }

    private fun unionDays(sorted: List<Span>): Long {
        var total = 0L
        var curStart: LocalDate? = null
        var curEnd: LocalDate? = null
        for (s in sorted) {
            val ce = curEnd
            if (ce == null || s.start > ce) {
                if (curStart != null && ce != null) total += days(curStart, ce)
                curStart = s.start; curEnd = s.endExclusive
            } else if (s.endExclusive > ce) curEnd = s.endExclusive
        }
        val ce = curEnd
        if (curStart != null && ce != null) total += days(curStart, ce)
        return total
    }
}
