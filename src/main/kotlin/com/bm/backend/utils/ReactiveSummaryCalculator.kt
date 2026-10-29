package com.bm.backend.utils

import com.bm.backend.models.FamilyWindowAggregate
import com.bm.backend.models.HistoryCoverage
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.ReactiveRatio
import com.bm.backend.models.ReactiveSeriesPoint
import com.bm.backend.models.ReactiveSummary
import com.bm.backend.models.ReactiveWindowStatus
import com.bm.backend.models.ReactiveWindowSummary
import com.bm.backend.models.RatioActiveBasis
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.utils.FamilyWindowAggregator.cells
import com.bm.backend.utils.FamilyWindowAggregator.values

/**
 * Pure, never-throwing descriptive summary of recorded reactive energy (spec report-reactive-summary, R1–R8).
 * Latest 12-month window only, contained rows only, nulls never 0, nothing projected or prorated.
 * The ratio is a plain quotient kVArh / kWh over the same window's history ACTIVE rows.
 */
object ReactiveSummaryCalculator {
    const val CALCULATION_ERROR = "calculation_error"
    const val NO_ANCHOR = "no_anchor"
    const val NO_REACTIVE_ROWS = "no_reactive_rows"
    const val OVERLAP_UNRESOLVED = "overlap_unresolved"
    const val ZERO_TOTAL = "zero_total"
    const val REACTIVE_INCOMPLETE = "reactive_incomplete"
    const val REACTIVE_OVERLAP = "reactive_overlap"
    const val ACTIVE_INCOMPLETE = "active_incomplete"
    const val ACTIVE_OVERLAP = "active_overlap"
    const val ACTIVE_UNAVAILABLE = "active_unavailable"
    const val ACTIVE_ZERO = "active_zero"
    const val PERIOD_MISSING = "period_missing"
    const val PERIOD_NULL_CELLS = "period_null_cells"
    const val WINDOW_MONTHS = 12L

    fun calculate(history: StudyHistory, coverage: HistoryCoverage): ReactiveSummary = try {
        compute(history, coverage)
    } catch (e: Exception) {
        ReactiveSummary.unavailable(CALCULATION_ERROR)
    }

    private fun status(a: FamilyWindowAggregate): ReactiveWindowStatus = when {
        a.contained.isEmpty() -> ReactiveWindowStatus.UNAVAILABLE
        a.hasBlockingOverlap -> ReactiveWindowStatus.OVERLAP_UNRESOLVED
        a.gaps.isEmpty() && a.crossing.isEmpty() && a.coveredDays == a.windowDays -> ReactiveWindowStatus.COMPLETE
        else -> ReactiveWindowStatus.INCOMPLETE
    }

    private fun compute(history: StudyHistory, coverage: HistoryCoverage): ReactiveSummary {
        val unavailable = when {
            history.status == StudyHistoryStatus.UNAVAILABLE -> history.reason ?: CALCULATION_ERROR
            coverage.status == StudyHistoryStatus.UNAVAILABLE -> coverage.reason ?: CALCULATION_ERROR
            coverage.anchorDate == null -> NO_ANCHOR
            else -> null
        }
        if (unavailable != null) return ReactiveSummary.unavailable(unavailable)
        val anchor = coverage.anchorDate!!
        val inverted = coverage.invertedIntervals.toSet()
        if (!FamilyWindowAggregator.hasRows(history, MeasurementFamily.REACTIVE, inverted)) {
            return ReactiveSummary.unavailable(NO_REACTIVE_ROWS).copy(anchorDate = anchor)
        }
        val start = anchor.minusMonths(WINDOW_MONTHS)
        val reactive = FamilyWindowAggregator.aggregate(history, MeasurementFamily.REACTIVE, inverted, start, anchor)
        val active = FamilyWindowAggregator.aggregate(history, MeasurementFamily.ACTIVE, inverted, start, anchor)
        val rStatus = status(reactive)
        val aStatus = status(active)
        val window = window(reactive, rStatus)

        val ratioReason = when {
            rStatus == ReactiveWindowStatus.OVERLAP_UNRESOLVED -> REACTIVE_OVERLAP
            rStatus != ReactiveWindowStatus.COMPLETE -> REACTIVE_INCOMPLETE
            aStatus == ReactiveWindowStatus.UNAVAILABLE -> ACTIVE_UNAVAILABLE
            aStatus == ReactiveWindowStatus.OVERLAP_UNRESOLVED -> ACTIVE_OVERLAP
            aStatus != ReactiveWindowStatus.COMPLETE -> ACTIVE_INCOMPLETE
            else -> null
        }
        val activeComplete = aStatus == ReactiveWindowStatus.COMPLETE
        val activeSums = cells(active.periodSums)
        val activeTotal = if (activeComplete) activeSums.filterNotNull().sum() else null
        val reactiveSums = cells(reactive.periodSums)

        val ratios: List<ReactiveRatio>
        val overall: ReactiveRatio
        if (ratioReason != null) {
            ratios = (1..6).map { ReactiveRatio(it, null, null, null, ratioReason) }
            overall = ReactiveRatio(null, null, null, null, ratioReason)
        } else {
            ratios = (1..6).map { p ->
                val r = reactiveSums[p - 1]
                val a = activeSums[p - 1]
                when {
                    r == null || a == null -> ReactiveRatio(p, r, a, null, PERIOD_MISSING)
                    p in reactive.nullCellPeriods || p in active.nullCellPeriods -> ReactiveRatio(p, r, a, null, PERIOD_NULL_CELLS)
                    a == 0.0 -> ReactiveRatio(p, r, a, null, ACTIVE_ZERO)
                    else -> ReactiveRatio(p, r, a, r / a, null)
                }
            }
            val rt = window.total
            overall = when {
                reactive.nullCellPeriods.isNotEmpty() || active.nullCellPeriods.isNotEmpty() ->
                    ReactiveRatio(null, rt, activeTotal, null, PERIOD_NULL_CELLS)
                rt == null || activeTotal == null -> ReactiveRatio(null, rt, activeTotal, null, PERIOD_MISSING)
                activeTotal == 0.0 -> ReactiveRatio(null, rt, activeTotal, null, ACTIVE_ZERO)
                else -> ReactiveRatio(null, rt, activeTotal, rt / activeTotal, null)
            }
        }

        val series = reactive.contained
            .map { i -> i to history.intervals[i] }
            .map { (i, row) ->
                val c = cells(row.reactive)
                ReactiveSeriesPoint(
                    index = i,
                    start = row.startDate,
                    endExclusive = row.endDate,
                    days = FamilyWindowAggregator.days(row.startDate, row.endDate),
                    values = row.reactive,
                    total = if (c.all { it == null }) null else c.filterNotNull().sum(),
                    inUnresolvedOverlap = i in reactive.blockingIndices
                )
            }

        return ReactiveSummary(
            status = StudyHistoryStatus.AVAILABLE,
            reason = null,
            anchorDate = anchor,
            window = window,
            activeBasis = RatioActiveBasis.HISTORY_ACTIVE_LATEST_12M,
            activeWindowStatus = aStatus,
            activeWindowStart = active.start,
            activeWindowEndExclusive = active.endExclusive,
            activeTotals = if (activeComplete) active.periodSums else null,
            activeTotal = activeTotal,
            ratioReason = ratioReason,
            ratios = ratios,
            overallRatio = overall,
            series = series
        )
    }

    private fun window(a: FamilyWindowAggregate, status: ReactiveWindowStatus): ReactiveWindowSummary {
        val sums = cells(a.periodSums)
        val sum = sums.filterNotNull().sum()
        val showTotals = status == ReactiveWindowStatus.COMPLETE || status == ReactiveWindowStatus.INCOMPLETE
        val firm = status == ReactiveWindowStatus.COMPLETE
        val recordedZero = showTotals && sums.any { it != null } && sums.filterNotNull().all { it == 0.0 }
        val reason = when (status) {
            ReactiveWindowStatus.UNAVAILABLE -> NO_REACTIVE_ROWS
            ReactiveWindowStatus.OVERLAP_UNRESOLVED -> OVERLAP_UNRESOLVED
            else -> if (recordedZero) ZERO_TOTAL else null
        }
        return ReactiveWindowSummary(
            start = a.start,
            endExclusive = a.endExclusive,
            status = status,
            reason = reason,
            windowDays = a.windowDays,
            coveredDays = a.coveredDays,
            contained = a.contained,
            crossing = a.crossing,
            gaps = a.gaps,
            overlapDisclosures = a.overlaps,
            periodTotals = if (showTotals) a.periodSums else values(List(6) { null }),
            total = if (firm) sum else null,
            partialTotal = if (status == ReactiveWindowStatus.INCOMPLETE) sum else null,
            shares = if (firm && sum > 0) values(sums.map { v -> v?.let { it / sum * 100 } }) else null,
            nullCells = a.nullCells,
            recordedZero = recordedZero
        )
    }
}
