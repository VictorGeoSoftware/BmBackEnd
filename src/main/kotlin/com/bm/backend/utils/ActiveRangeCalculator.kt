package com.bm.backend.utils

import com.bm.backend.models.ActiveRangeTotals
import com.bm.backend.models.HistoryCoverage
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.StudyHistory

/** 24-month active totals and shares (spec report-original-fidelity), same containment as the readings table. */
object ActiveRangeCalculator {
    const val NO_READINGS = "no_readings"
    const val OVERLAP = "overlap"

    fun calculate(history: StudyHistory, coverage: HistoryCoverage, periods: List<Int>): ActiveRangeTotals {
        val anchor = coverage.anchorDate ?: return ActiveRangeTotals(null, null, null, false, NO_READINGS)
        val inverted = coverage.invertedIntervals.toSet()
        val agg = FamilyWindowAggregator.aggregate(history, MeasurementFamily.ACTIVE, inverted, ReportHistoryRange.start(anchor), anchor, periods)
        if (agg.contained.isEmpty()) return ActiveRangeTotals(null, null, null, false, NO_READINGS)
        if (agg.hasBlockingOverlap) return ActiveRangeTotals(null, null, null, false, OVERLAP)
        val sums = FamilyWindowAggregator.cells(agg.periodSums)
        val inTariff = sums.mapIndexed { i, v -> if (i + 1 in periods) v else null }
        val total = inTariff.filterNotNull().sum()
        val shares = if (total > 0) inTariff.map { v -> v?.let { it / total * 100 } } else null
        return ActiveRangeTotals(
            periodTotals = FamilyWindowAggregator.values(inTariff),
            total = total,
            shares = shares?.let { FamilyWindowAggregator.values(it) },
            partial = agg.coveredDays < agg.windowDays,
            reason = null
        )
    }
}
