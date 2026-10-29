package com.bm.backend.utils

import com.bm.backend.models.ActiveSummary
import com.bm.backend.models.ActiveWindowKind
import com.bm.backend.models.ActiveWindowStatus
import com.bm.backend.models.ActiveWindowSummary
import com.bm.backend.models.AnnualSource
import com.bm.backend.models.AnnualSourceComparison
import com.bm.backend.models.CleanedConsumptionData
import com.bm.backend.models.ComparisonStatus
import com.bm.backend.models.CoverageGap
import com.bm.backend.models.CoverageOverlap
import com.bm.backend.models.HighConsumptionInterval
import com.bm.backend.models.HistoryCoverage
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.StudySnapshot
import com.bm.backend.models.StudySnapshotStatus
import com.bm.backend.models.ValueComparison
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Pure, never-throwing active-energy summary over comparable date windows (spec report-active-summary, A1–A8).
 * D1: a row covers `[startDate, endDate)`; only fully contained rows are summed, crossing rows are never prorated.
 * Null cells are never treated as 0; nothing is projected; source values are never replaced.
 */
object ActiveSummaryCalculator {
    const val CALCULATION_ERROR = "calculation_error"
    const val NO_ANCHOR = "no_anchor"
    const val NO_ACTIVE_ROWS = "no_active_rows"
    const val OVERLAP_UNRESOLVED = "overlap_unresolved"
    const val ZERO_TOTAL = "zero_total"
    const val LATEST_INCOMPLETE = "latest_incomplete"
    const val PREVIOUS_INCOMPLETE = "previous_incomplete"
    const val PREVIOUS_ZERO = "previous_zero"
    const val MATCH_TOLERANCE_KWH = 0.5

    private data class Span(val index: Int, val start: LocalDate, val endExclusive: LocalDate, val cells: List<Double?>)

    private fun days(start: LocalDate, endExclusive: LocalDate): Long = ChronoUnit.DAYS.between(start, endExclusive)
    private fun cells(v: PeriodValues) = listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6)
    private fun values(c: List<Double?>) = PeriodValues(c[0], c[1], c[2], c[3], c[4], c[5])
    private fun isZeroRow(s: Span) = s.cells.all { it == 0.0 }

    fun calculate(
        history: StudyHistory,
        coverage: HistoryCoverage,
        snapshot: StudySnapshot?,
        legacy: CleanedConsumptionData?
    ): ActiveSummary = try {
        compute(history, coverage, snapshot, legacy)
    } catch (e: Exception) {
        ActiveSummary.unavailable(CALCULATION_ERROR)
    }

    private fun compute(
        history: StudyHistory,
        coverage: HistoryCoverage,
        snapshot: StudySnapshot?,
        legacy: CleanedConsumptionData?
    ): ActiveSummary {
        val sources = sources(snapshot, legacy)
        val unavailableReason = when {
            history.status == StudyHistoryStatus.UNAVAILABLE -> history.reason ?: CALCULATION_ERROR
            coverage.status == StudyHistoryStatus.UNAVAILABLE -> coverage.reason ?: CALCULATION_ERROR
            coverage.anchorDate == null -> NO_ANCHOR
            else -> null
        }
        if (unavailableReason != null) {
            return ActiveSummary.unavailable(unavailableReason).copy(comparisons = compare(sources, null))
        }
        val anchor = coverage.anchorDate!!
        val inverted = coverage.invertedIntervals.toSet()
        val spans = history.intervals.indices
            .filter { it !in inverted && history.intervals[it].startDate <= history.intervals[it].endDate }
            .map { i -> history.intervals[i].let { Span(i, it.startDate, it.endDate, cells(it.active)) } }
            .filter { s -> s.cells.any { it != null } }
            .sortedWith(compareBy<Span>({ it.start }, { it.endExclusive }, { it.index }))

        val latest = window(ActiveWindowKind.LATEST_12M, anchor.minusMonths(12), anchor, spans)
        val previous = window(ActiveWindowKind.PREVIOUS_12M, anchor.minusMonths(24), anchor.minusMonths(12), spans)
        val (changePct, changeReason) = change(latest, previous)
        val active = coverage.families[MeasurementFamily.ACTIVE]

        return ActiveSummary(
            status = StudyHistoryStatus.AVAILABLE,
            reason = null,
            anchorDate = anchor,
            partial = latest.status != ActiveWindowStatus.COMPLETE,
            historyStart = active?.startDate,
            historyEnd = active?.endDate,
            windows = listOf(latest, previous),
            changePct = changePct,
            changeReason = changeReason,
            highIntervals = highIntervals(listOf(latest, previous), spans),
            highIntervalCount = ActiveSummary.HIGH_INTERVAL_COUNT,
            comparisons = compare(sources, latest)
        )
    }

    private fun window(kind: ActiveWindowKind, start: LocalDate, end: LocalDate, spans: List<Span>): ActiveWindowSummary {
        val contained = spans.filter { it.start >= start && it.endExclusive <= end }
        val crossing = spans.filter { !(it.start >= start && it.endExclusive <= end) && it.start < end && it.endExclusive > start }
        val windowDays = days(start, end)
        val overlaps = overlaps(contained)
        val byIndex = contained.associateBy { it.index }
        val blocking = overlaps.any { o -> !isZeroRow(byIndex.getValue(o.firstIndex)) && !isZeroRow(byIndex.getValue(o.secondIndex)) }
        val gaps = mutableListOf<CoverageGap>()
        var runningEnd = start
        for (s in contained) {
            if (s.start > runningEnd) gaps += CoverageGap(runningEnd, s.start, days(runningEnd, s.start))
            if (s.endExclusive > runningEnd) runningEnd = s.endExclusive
        }
        if (end > runningEnd) gaps += CoverageGap(runningEnd, end, days(runningEnd, end))
        val coveredDays = unionDays(contained)
        val nullCells = contained.sumOf { s -> s.cells.count { it == null } }
        val periodSums = (0 until 6).map { p ->
            val present = contained.mapNotNull { it.cells[p] }
            if (present.isEmpty()) null else present.sum()
        }
        val sum = periodSums.filterNotNull().sum()
        val status = when {
            contained.isEmpty() -> ActiveWindowStatus.UNAVAILABLE
            blocking -> ActiveWindowStatus.OVERLAP_UNRESOLVED
            gaps.isEmpty() && crossing.isEmpty() && coveredDays == windowDays -> ActiveWindowStatus.COMPLETE
            else -> ActiveWindowStatus.INCOMPLETE
        }
        val firm = status == ActiveWindowStatus.COMPLETE
        val shares = if (firm && sum > 0) values(periodSums.map { v -> v?.let { it / sum * 100 } }) else null
        val reason = when (status) {
            ActiveWindowStatus.UNAVAILABLE -> NO_ACTIVE_ROWS
            ActiveWindowStatus.OVERLAP_UNRESOLVED -> OVERLAP_UNRESOLVED
            ActiveWindowStatus.COMPLETE -> if (sum > 0) null else ZERO_TOTAL
            ActiveWindowStatus.INCOMPLETE -> null
        }
        val showTotals = status == ActiveWindowStatus.COMPLETE || status == ActiveWindowStatus.INCOMPLETE
        return ActiveWindowSummary(
            kind = kind,
            start = start,
            endExclusive = end,
            status = status,
            reason = reason,
            windowDays = windowDays,
            coveredDays = coveredDays,
            contained = contained.map { it.index },
            crossing = crossing.map { it.index },
            gaps = gaps,
            overlapDisclosures = overlaps,
            periodTotals = if (showTotals) values(periodSums) else values(List(6) { null }),
            total = if (firm) sum else null,
            partialTotal = if (status == ActiveWindowStatus.INCOMPLETE) sum else null,
            shares = shares,
            nullCells = nullCells
        )
    }

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

    private fun change(latest: ActiveWindowSummary, previous: ActiveWindowSummary): Pair<Double?, String?> {
        val l = latest.total
        val p = previous.total
        return when {
            l == null -> null to LATEST_INCOMPLETE
            p == null -> null to PREVIOUS_INCOMPLETE
            p <= 0.0 -> null to PREVIOUS_ZERO
            else -> (l - p) / p * 100 to null
        }
    }

    private fun highIntervals(windows: List<ActiveWindowSummary>, spans: List<Span>): List<HighConsumptionInterval> {
        val target = windows.firstOrNull {
            it.status == ActiveWindowStatus.COMPLETE || it.status == ActiveWindowStatus.INCOMPLETE
        } ?: return emptyList()
        val byIndex = spans.associateBy { it.index }
        val excluded = target.overlapDisclosures
            .filter { !isZeroRow(byIndex.getValue(it.firstIndex)) && !isZeroRow(byIndex.getValue(it.secondIndex)) }
            .flatMap { listOf(it.firstIndex, it.secondIndex) }.toSet()
        return target.contained.asSequence()
            .filter { it !in excluded }
            .map { byIndex.getValue(it) }
            .filter { days(it.start, it.endExclusive) > 0 }
            .map { s ->
                val n = days(s.start, s.endExclusive)
                val total = s.cells.filterNotNull().sum()
                val max = s.cells.filterNotNull().maxOrNull()
                val dominant = if (max == null || max <= 0.0) null else s.cells.indexOfFirst { it == max } + 1
                HighConsumptionInterval(s.index, s.start, s.endExclusive, n, total, total / n, dominant)
            }
            .sortedWith(compareByDescending<HighConsumptionInterval> { it.kwhPerDay }.thenBy { it.start }.thenBy { it.index })
            .take(ActiveSummary.HIGH_INTERVAL_COUNT)
            .toList()
    }

    private data class SourceValues(val source: AnnualSource, val total: Double?, val periods: List<Double?>?)

    private fun sources(snapshot: StudySnapshot?, legacy: CleanedConsumptionData?): List<SourceValues> {
        val fields = snapshot?.takeIf { it.status == StudySnapshotStatus.AVAILABLE }?.fields
        val sips = SourceValues(AnnualSource.SIPS_SNAPSHOT, fields?.annualConsumptionKwh, fields?.annualConsumptionPeriodsKwh?.let { cells(it) })
        val leg = SourceValues(
            AnnualSource.LEGACY_ANNUAL,
            legacy?.annualConsumption,
            legacy?.let {
                listOf(it.annualConsumptionP1, it.annualConsumptionP2, it.annualConsumptionP3,
                    it.annualConsumptionP4, it.annualConsumptionP5, it.annualConsumptionP6)
            }
        )
        return listOf(sips, leg)
    }

    private fun compare(sources: List<SourceValues>, latest: ActiveWindowSummary?): List<AnnualSourceComparison> =
        sources.map { src ->
            val available = src.total != null || src.periods?.any { it != null } == true
            val comparable = available && latest?.status == ActiveWindowStatus.COMPLETE
            val status = when {
                !available -> ComparisonStatus.SOURCE_UNAVAILABLE
                comparable -> ComparisonStatus.COMPARED
                else -> ComparisonStatus.NOT_COMPARABLE
            }
            val historyPeriods = latest?.let { cells(it.periodTotals) }
            AnnualSourceComparison(
                source = src.source,
                status = status,
                total = value(null, src.total, if (comparable) latest?.total else null),
                periods = (0 until 6).map { p ->
                    value(p + 1, src.periods?.get(p), if (comparable) historyPeriods?.get(p) else null)
                }
            )
        }

    private fun value(period: Int?, source: Double?, history: Double?): ValueComparison {
        val diff = if (source != null && history != null) history - source else null
        val pct = if (diff != null && source != null && source != 0.0) diff / source * 100 else null
        return ValueComparison(period, source, history, diff, pct, diff?.let { abs(it) < MATCH_TOLERANCE_KWH })
    }
}
