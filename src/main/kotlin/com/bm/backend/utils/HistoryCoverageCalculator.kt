package com.bm.backend.utils

import com.bm.backend.models.CoverageGap
import com.bm.backend.models.CoverageOverlap
import com.bm.backend.models.FamilyCoverage
import com.bm.backend.models.HistoryCoverage
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.WindowCoverage
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure, never-throwing calculation of the actual coverage of a [StudyHistory].
 * Rows are interpreted only by their own dates (never as months); the input is never modified.
 * Overlaps are disclosed, never resolved.
 */
object HistoryCoverageCalculator {
    const val CALCULATION_ERROR = "calculation_error"
    val WINDOW_MONTHS = listOf(12, 24)

    private data class Span(val index: Int, val start: LocalDate, val endExclusive: LocalDate)

    /**
     * D1 — single boundary convention: an interval covers the half-open range `[startDate, endDate)`.
     * A shared endpoint between consecutive rows is therefore contiguous.
     */
    private fun span(index: Int, interval: StudyHistoryInterval): Span =
        Span(index, interval.startDate, interval.endDate)

    private fun days(start: LocalDate, endExclusive: LocalDate): Long = ChronoUnit.DAYS.between(start, endExclusive)

    fun calculate(history: StudyHistory): HistoryCoverage = try {
        compute(history)
    } catch (e: Exception) {
        HistoryCoverage.unavailable(CALCULATION_ERROR)
    }

    private fun compute(history: StudyHistory): HistoryCoverage {
        if (history.status == StudyHistoryStatus.UNAVAILABLE) {
            return HistoryCoverage.unavailable(history.reason ?: CALCULATION_ERROR)
        }
        val intervals = history.intervals
        val inverted = intervals.indices.filter { intervals[it].startDate > intervals[it].endDate }
        val anchor = intervals.indices
            .filter { it !in inverted && MeasurementFamily.entries.any { f -> hasValue(values(intervals[it], f)) } }
            .maxOfOrNull { intervals[it].endDate }
        val families = MeasurementFamily.entries.associateWith { family(intervals, inverted.toSet(), it, anchor) }
        return HistoryCoverage(StudyHistoryStatus.AVAILABLE, null, anchor, inverted, families)
    }

    private fun values(interval: StudyHistoryInterval, family: MeasurementFamily): PeriodValues = when (family) {
        MeasurementFamily.ACTIVE -> interval.active
        MeasurementFamily.REACTIVE -> interval.reactive
        MeasurementFamily.MAX_DEMAND -> interval.maxDemand
    }

    private fun cells(v: PeriodValues) = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)

    private fun hasValue(v: PeriodValues) = cells(v).any { it != null }

    private val order = compareBy<Span>({ it.start }, { it.endExclusive }, { it.index })

    private fun family(
        intervals: List<StudyHistoryInterval>,
        inverted: Set<Int>,
        family: MeasurementFamily,
        anchor: LocalDate?
    ): FamilyCoverage {
        val candidates = intervals.indices.filter { it !in inverted }
        val usableIdx = candidates.filter { hasValue(values(intervals[it], family)) }
        val missing = candidates.filter { !hasValue(values(intervals[it], family)) }
            .sortedWith(compareBy<Int>({ intervals[it].startDate }, { it }))
        val spans = usableIdx.map { span(it, intervals[it]) }.sortedWith(order)
        val zeroLength = spans.filter { days(it.start, it.endExclusive) == 0L }.map { it.index }
        val nullValues = usableIdx.sumOf { i -> cells(values(intervals[i], family)).count { it == null } }

        val gaps = mutableListOf<CoverageGap>()
        var runningEnd: LocalDate? = null
        for (s in spans) {
            val end = runningEnd
            if (end != null && s.start > end) gaps += CoverageGap(end, s.start, days(end, s.start))
            if (end == null || s.endExclusive > end) runningEnd = s.endExclusive
        }

        val overlaps = mutableListOf<CoverageOverlap>()
        for (a in spans.indices) for (b in a + 1 until spans.size) {
            val x = spans[a]; val y = spans[b]
            if (y.start >= x.endExclusive) break
            val start = maxOf(x.start, y.start)
            val end = minOf(x.endExclusive, y.endExclusive)
            if (end > start) overlaps += CoverageOverlap(x.index, y.index, start, end, days(start, end))
        }

        val windows = if (anchor == null) emptyList() else WINDOW_MONTHS.map { months ->
            val wStart = anchor.minusMonths(months.toLong())
            val contained = mutableListOf<Int>()
            val crossing = mutableListOf<Int>()
            for (s in spans) {
                val inside = s.start >= wStart && s.endExclusive <= anchor
                val intersects = s.start < anchor && s.endExclusive > wStart
                when {
                    inside -> contained += s.index
                    intersects -> crossing += s.index
                }
            }
            val containedSpans = spans.filter { it.index in contained }
            val union = unionDays(containedSpans)
            val hasGap = gapsExist(containedSpans)
            val complete = !hasGap && crossing.isEmpty() && containedSpans.isNotEmpty() &&
                containedSpans.first().start == wStart && union == days(wStart, anchor)
            WindowCoverage(months, wStart, anchor, contained, crossing, union, complete)
        }

        return FamilyCoverage(
            startDate = spans.minOfOrNull { it.start },
            endDate = spans.maxOfOrNull { it.endExclusive },
            usableIntervals = spans.size,
            missingIntervals = missing,
            zeroLengthIntervals = zeroLength,
            nullValues = nullValues,
            coveredDays = unionDays(spans),
            gaps = gaps,
            overlaps = overlaps,
            hasUnresolvedOverlaps = overlaps.isNotEmpty(),
            windows = windows
        )
    }

    private fun unionDays(sorted: List<Span>): Long {
        var total = 0L
        var curStart: LocalDate? = null
        var curEnd: LocalDate? = null
        for (s in sorted) {
            if (curEnd == null || s.start > curEnd) {
                if (curStart != null) total += days(curStart, curEnd!!)
                curStart = s.start; curEnd = s.endExclusive
            } else if (s.endExclusive > curEnd) curEnd = s.endExclusive
        }
        if (curStart != null) total += days(curStart, curEnd!!)
        return total
    }

    private fun gapsExist(sorted: List<Span>): Boolean {
        var end: LocalDate? = null
        for (s in sorted) {
            if (end != null && s.start > end) return true
            if (end == null || s.endExclusive > end) end = s.endExclusive
        }
        return false
    }
}
