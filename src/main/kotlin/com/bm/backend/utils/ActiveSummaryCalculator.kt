package com.bm.backend.utils

import com.bm.backend.models.ActiveSummary
import com.bm.backend.models.ActiveWindowKind
import com.bm.backend.models.ActiveWindowStatus
import com.bm.backend.models.ActiveWindowSummary
import com.bm.backend.models.AnnualSource
import com.bm.backend.models.AnnualSourceComparison
import com.bm.backend.models.CleanedConsumptionData
import com.bm.backend.models.ComparisonStatus
import com.bm.backend.models.HighConsumptionInterval
import com.bm.backend.models.HistoryCoverage
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.StudySnapshot
import com.bm.backend.models.StudySnapshotStatus
import com.bm.backend.models.TariffPeriodProfile
import com.bm.backend.models.ValueComparison
import java.time.LocalDate
import com.bm.backend.utils.FamilyWindowAggregator.cells
import com.bm.backend.utils.FamilyWindowAggregator.days
import com.bm.backend.utils.FamilyWindowAggregator.isZeroRow
import com.bm.backend.utils.FamilyWindowAggregator.values
import kotlin.math.abs

/**
 * Pure, never-throwing active-energy summary over comparable date windows (spec report-active-summary, A1–A8).
 * D1: a row covers `[startDate, endDate)`; only fully contained rows are summed, crossing rows are never prorated.
 * Null cells and comparison rows are limited to the tariff's [periods] (default P1–P6).
 * Null cells are never treated as 0; nothing is projected; source values are never replaced.
 */
object ActiveSummaryCalculator {
    const val CALCULATION_ERROR = "calculation_error"
    const val NO_ANCHOR = "no_anchor"
    const val NO_ACTIVE_ROWS = "no_active_rows"
    const val OVERLAP_UNRESOLVED = "overlap_unresolved"
    const val ZERO_TOTAL = "zero_total"
    const val LATEST_UNAVAILABLE = "latest_unavailable"
    const val PREVIOUS_UNAVAILABLE = "previous_unavailable"
    const val PREVIOUS_ZERO = "previous_zero"
    const val MATCH_TOLERANCE_KWH = 0.5

    fun calculate(
        history: StudyHistory,
        coverage: HistoryCoverage,
        snapshot: StudySnapshot?,
        legacy: CleanedConsumptionData?,
        periods: List<Int> = TariffPeriodProfile.ALL_PERIODS
    ): ActiveSummary = try {
        compute(history, coverage, snapshot, legacy, periods)
    } catch (e: Exception) {
        ActiveSummary.unavailable(CALCULATION_ERROR)
    }

    private fun compute(
        history: StudyHistory,
        coverage: HistoryCoverage,
        snapshot: StudySnapshot?,
        legacy: CleanedConsumptionData?,
        periods: List<Int>
    ): ActiveSummary {
        val sources = sources(snapshot, legacy)
        val unavailableReason = when {
            history.status == StudyHistoryStatus.UNAVAILABLE -> history.reason ?: CALCULATION_ERROR
            coverage.status == StudyHistoryStatus.UNAVAILABLE -> coverage.reason ?: CALCULATION_ERROR
            coverage.anchorDate == null -> NO_ANCHOR
            else -> null
        }
        if (unavailableReason != null) {
            return ActiveSummary.unavailable(unavailableReason).copy(comparisons = compare(sources, null, periods))
        }
        val anchor = coverage.anchorDate!!
        val inverted = coverage.invertedIntervals.toSet()

        val latest = window(ActiveWindowKind.LATEST_12M, anchor.minusMonths(12), anchor, history, inverted, periods)
        val previous = window(ActiveWindowKind.PREVIOUS_12M, anchor.minusMonths(24), anchor.minusMonths(12), history, inverted, periods)
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
            highIntervals = highIntervals(listOf(latest, previous), history),
            highIntervalCount = ActiveSummary.HIGH_INTERVAL_COUNT,
            comparisons = compare(sources, latest, periods)
        )
    }

    private fun window(
        kind: ActiveWindowKind,
        start: LocalDate,
        end: LocalDate,
        history: StudyHistory,
        inverted: Set<Int>,
        periods: List<Int>
    ): ActiveWindowSummary {
        val agg = FamilyWindowAggregator.aggregate(history, MeasurementFamily.ACTIVE, inverted, start, end, periods)
        val contained = agg.contained
        val crossing = agg.crossing
        val windowDays = agg.windowDays
        val overlaps = agg.overlaps
        val blocking = agg.hasBlockingOverlap
        val gaps = agg.gaps
        val coveredDays = agg.coveredDays
        val nullCells = agg.nullCells
        val periodSums = cells(agg.periodSums)
        val sum = periodSums.filterNotNull().sum()
        val status = when {
            contained.isEmpty() -> ActiveWindowStatus.UNAVAILABLE
            blocking -> ActiveWindowStatus.OVERLAP_UNRESOLVED
            gaps.isEmpty() && crossing.isEmpty() && coveredDays == windowDays -> ActiveWindowStatus.COMPLETE
            else -> ActiveWindowStatus.INCOMPLETE
        }
        val firm = status == ActiveWindowStatus.COMPLETE
        val showTotals = firm || status == ActiveWindowStatus.INCOMPLETE
        val shares = if (showTotals && sum > 0) values(periodSums.map { v -> v?.let { it / sum * 100 } }) else null
        val reason = when (status) {
            ActiveWindowStatus.UNAVAILABLE -> NO_ACTIVE_ROWS
            ActiveWindowStatus.OVERLAP_UNRESOLVED -> OVERLAP_UNRESOLVED
            ActiveWindowStatus.COMPLETE, ActiveWindowStatus.INCOMPLETE -> if (sum > 0) null else ZERO_TOTAL
        }
        return ActiveWindowSummary(
            kind = kind,
            start = start,
            endExclusive = end,
            status = status,
            reason = reason,
            windowDays = windowDays,
            coveredDays = coveredDays,
            contained = contained,
            crossing = crossing,
            gaps = gaps,
            overlapDisclosures = overlaps,
            periodTotals = if (showTotals) values(periodSums) else values(List(6) { null }),
            total = if (firm) sum else null,
            partialTotal = if (status == ActiveWindowStatus.INCOMPLETE) sum else null,
            shares = shares,
            nullCells = nullCells
        )
    }

    private fun change(latest: ActiveWindowSummary, previous: ActiveWindowSummary): Pair<Double?, String?> {
        val l = latest.recordedTotal
        val p = previous.recordedTotal
        return when {
            latest.status == ActiveWindowStatus.OVERLAP_UNRESOLVED || previous.status == ActiveWindowStatus.OVERLAP_UNRESOLVED -> null to OVERLAP_UNRESOLVED
            l == null -> null to LATEST_UNAVAILABLE
            p == null -> null to PREVIOUS_UNAVAILABLE
            p <= 0.0 -> null to PREVIOUS_ZERO
            else -> (l - p) / p * 100 to null
        }
    }

    private fun highIntervals(windows: List<ActiveWindowSummary>, history: StudyHistory): List<HighConsumptionInterval> {
        val target = windows.firstOrNull {
            it.status == ActiveWindowStatus.COMPLETE || it.status == ActiveWindowStatus.INCOMPLETE
        } ?: return emptyList()
        val zero = { i: Int -> isZeroRow(history.intervals[i].active) }
        val excluded = target.overlapDisclosures
            .filter { !zero(it.firstIndex) && !zero(it.secondIndex) }
            .flatMap { listOf(it.firstIndex, it.secondIndex) }.toSet()
        return target.contained.asSequence()
            .filter { it !in excluded }
            .filter { i -> history.intervals[i].let { days(it.startDate, it.endDate) } > 0 }
            .map { i ->
                val row = history.intervals[i]
                val c = cells(row.active)
                val n = days(row.startDate, row.endDate)
                val total = c.filterNotNull().sum()
                val max = c.filterNotNull().maxOrNull()
                val dominant = if (max == null || max <= 0.0) null else c.indexOfFirst { it == max } + 1
                HighConsumptionInterval(i, row.startDate, row.endDate, n, total, total / n, dominant)
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

    private fun compare(sources: List<SourceValues>, latest: ActiveWindowSummary?, periods: List<Int>): List<AnnualSourceComparison> =
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
                periods = periods.map { p ->
                    value(p, src.periods?.get(p - 1), if (comparable) historyPeriods?.get(p - 1) else null)
                }
            )
        }

    private fun value(period: Int?, source: Double?, history: Double?): ValueComparison {
        val diff = if (source != null && history != null) history - source else null
        val pct = if (diff != null && source != null && source != 0.0) diff / source * 100 else null
        return ValueComparison(period, source, history, diff, pct, diff?.let { abs(it) < MATCH_TOLERANCE_KWH })
    }
}
