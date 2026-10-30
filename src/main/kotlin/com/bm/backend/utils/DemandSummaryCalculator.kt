package com.bm.backend.utils

import com.bm.backend.models.DemandContractedSource
import com.bm.backend.models.DemandPeriodStatistic
import com.bm.backend.models.DemandSeriesPoint
import com.bm.backend.models.DemandSummary
import com.bm.backend.models.DemandWindowStatus
import com.bm.backend.models.DemandWindowSummary
import com.bm.backend.models.FamilyWindowAggregate
import com.bm.backend.models.HistoryCoverage
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.StudySnapshot
import com.bm.backend.models.StudySnapshotStatus
import com.bm.backend.models.TariffPeriodProfile
import com.bm.backend.utils.FamilyWindowAggregator.cells

/**
 * Pure, never-throwing descriptive summary of recorded maximeter demand in kW (spec report-demand-summary, M1–M7).
 * Latest 12-month window, contained rows only, demand rows kept regardless of their energy values,
 * recorded 0.0 distinct from null, values never converted. Overlaps are disclosed and do not block the peak (M2);
 * the mean of per-reading maxima is given only on a complete, overlap-free window (M4).
 * The contracted comparison is a side-by-side difference and percentage only (M5).
 * Period statistics and null cells cover only the tariff's power [periods] (default P1–P6).
 * Reuses [FamilyWindowAggregator] for window classification only; its sums and blocking rows are ignored (M7).
 */
object DemandSummaryCalculator {
    const val CALCULATION_ERROR = "calculation_error"
    const val NO_ANCHOR = "no_anchor"
    const val NO_DEMAND_ROWS = "no_demand_rows"
    const val NO_CONTAINED_ROWS = "no_contained_rows"
    const val NO_READINGS = "no_readings"
    const val WINDOW_INCOMPLETE = "window_incomplete"
    const val OVERLAP = "overlap"
    const val SNAPSHOT_UNAVAILABLE = "snapshot_unavailable"
    const val CONTRACTED_MISSING = "contracted_missing"
    const val CONTRACTED_ZERO = "contracted_zero"
    const val WINDOW_MONTHS = 12L

    fun calculate(
        history: StudyHistory,
        coverage: HistoryCoverage,
        snapshot: StudySnapshot?,
        periods: List<Int> = TariffPeriodProfile.ALL_PERIODS
    ): DemandSummary = try {
        compute(history, coverage, snapshot, periods)
    } catch (e: Exception) {
        DemandSummary.unavailable(CALCULATION_ERROR)
    }

    private fun status(a: FamilyWindowAggregate): DemandWindowStatus = when {
        a.contained.isEmpty() -> DemandWindowStatus.UNAVAILABLE
        a.gaps.isEmpty() && a.crossing.isEmpty() && a.coveredDays == a.windowDays -> DemandWindowStatus.COMPLETE
        else -> DemandWindowStatus.INCOMPLETE
    }

    private fun compute(history: StudyHistory, coverage: HistoryCoverage, snapshot: StudySnapshot?, periods: List<Int>): DemandSummary {
        val unavailable = when {
            history.status == StudyHistoryStatus.UNAVAILABLE -> history.reason ?: CALCULATION_ERROR
            coverage.status == StudyHistoryStatus.UNAVAILABLE -> coverage.reason ?: CALCULATION_ERROR
            coverage.anchorDate == null -> NO_ANCHOR
            else -> null
        }
        if (unavailable != null) return DemandSummary.unavailable(unavailable)
        val anchor = coverage.anchorDate!!
        val inverted = coverage.invertedIntervals.toSet()
        if (!FamilyWindowAggregator.hasRows(history, MeasurementFamily.MAX_DEMAND, inverted)) {
            return DemandSummary.unavailable(NO_DEMAND_ROWS).copy(anchorDate = anchor)
        }
        val start = anchor.minusMonths(WINDOW_MONTHS)
        val agg = FamilyWindowAggregator.aggregate(history, MeasurementFamily.MAX_DEMAND, inverted, start, anchor, periods)
        val wStatus = status(agg)
        val overlapIndices = agg.overlaps.flatMap { listOf(it.firstIndex, it.secondIndex) }.toSet()
        val window = DemandWindowSummary(
            start = agg.start,
            endExclusive = agg.endExclusive,
            status = wStatus,
            reason = if (wStatus == DemandWindowStatus.UNAVAILABLE) NO_CONTAINED_ROWS else null,
            windowDays = agg.windowDays,
            coveredDays = agg.coveredDays,
            contained = agg.contained,
            crossing = agg.crossing,
            gaps = agg.gaps,
            overlapDisclosures = agg.overlaps,
            nullCells = agg.nullCells,
            partial = wStatus != DemandWindowStatus.COMPLETE
        )

        val contracted: PeriodValues? = snapshot
            ?.takeIf { it.status == StudySnapshotStatus.AVAILABLE }
            ?.fields?.contractedPowerKw
        val snapshotReason = if (contracted == null) SNAPSHOT_UNAVAILABLE else null
        val contractedCells = contracted?.let { cells(it) }

        // Contained rows in date order (aggregator order: start, endExclusive, index).
        val rows = agg.contained.map { it to history.intervals[it] }
        val meanReason = when {
            wStatus != DemandWindowStatus.COMPLETE -> WINDOW_INCOMPLETE
            agg.overlaps.isNotEmpty() -> OVERLAP
            else -> null
        }

        val statistics = periods.map { it - 1 }.map { p ->
            val readings = rows.mapNotNull { (i, r) -> cells(r.maxDemand)[p]?.let { Triple(i, r, it) } }
            val peak = readings.sortedWith(
                compareByDescending<Triple<Int, StudyHistoryInterval, Double>> { it.third }
                    .thenBy { it.second.startDate }
                    .thenBy { it.first }
            ).firstOrNull()
            val peakKw = peak?.third
            val mean = if (meanReason == null && readings.isNotEmpty()) readings.sumOf { it.third } / readings.size else null
            val c = contractedCells?.get(p)
            val comparisonReason = when {
                contractedCells == null -> SNAPSHOT_UNAVAILABLE
                c == null -> CONTRACTED_MISSING
                c == 0.0 -> CONTRACTED_ZERO
                peakKw == null -> NO_READINGS
                else -> null
            }
            val diff = if (comparisonReason == null) finite(peakKw!! - c!!) else null
            val pct = if (comparisonReason == null) finite(peakKw!! / c!! * 100) else null
            val peakRow = peak?.second
            DemandPeriodStatistic(
                period = p + 1,
                readings = readings.size,
                peakKw = peakKw?.takeIf { it.isFinite() },
                peakIndex = peak?.first,
                peakStart = peakRow?.startDate,
                peakEndExclusive = peakRow?.endDate,
                meanKw = mean?.let { finite(it) },
                meanReason = if (readings.isEmpty()) NO_READINGS else meanReason,
                contractedKw = c,
                peakMinusContractedKw = diff,
                peakPctOfContracted = pct,
                comparisonReason = comparisonReason,
                reason = if (readings.isEmpty()) NO_READINGS else null
            )
        }

        val series = rows.map { (i, r) ->
            DemandSeriesPoint(
                index = i,
                start = r.startDate,
                endExclusive = r.endDate,
                days = FamilyWindowAggregator.days(r.startDate, r.endDate),
                values = r.maxDemand,
                inOverlap = i in overlapIndices,
                activeAllZero = FamilyWindowAggregator.isZeroRow(r.active)
            )
        }

        return DemandSummary(
            status = StudyHistoryStatus.AVAILABLE,
            reason = null,
            anchorDate = anchor,
            window = window,
            contractedSource = DemandContractedSource.SNAPSHOT_CONTRACTED_POWER_KW,
            snapshotReason = snapshotReason,
            periods = statistics,
            series = series
        )
    }

    private fun finite(v: Double): Double? = v.takeIf { it.isFinite() }
}
