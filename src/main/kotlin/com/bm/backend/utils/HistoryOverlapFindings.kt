package com.bm.backend.utils

import com.bm.backend.models.HistoryCoverage
import com.bm.backend.models.HistoryOverlapEffect
import com.bm.backend.models.HistoryOverlapFinding
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.StudyHistory

/**
 * Pure: turns the overlaps disclosed by [HistoryCoverageCalculator] into findings with the effect the
 * report rules give them. Never throws; an unavailable history yields no findings.
 */
object HistoryOverlapFindings {

    fun from(history: StudyHistory, coverage: HistoryCoverage): List<HistoryOverlapFinding> = try {
        coverage.families.entries
            .sortedBy { it.key.ordinal }
            .flatMap { (family, familyCoverage) ->
                familyCoverage.overlaps.map { overlap ->
                    val first = history.intervals[overlap.firstIndex]
                    val second = history.intervals[overlap.secondIndex]
                    val zeroRow = when {
                        FamilyWindowAggregator.isZeroRow(FamilyWindowAggregator.familyValues(first, family)) -> overlap.firstIndex + 1
                        FamilyWindowAggregator.isZeroRow(FamilyWindowAggregator.familyValues(second, family)) -> overlap.secondIndex + 1
                        else -> null
                    }
                    HistoryOverlapFinding(
                        family = family,
                        firstRow = overlap.firstIndex + 1,
                        firstStart = first.startDate,
                        firstEnd = first.endDate,
                        secondRow = overlap.secondIndex + 1,
                        secondStart = second.startDate,
                        secondEnd = second.endDate,
                        overlapDays = overlap.days,
                        zeroRow = zeroRow,
                        effect = effect(family, zeroRow)
                    )
                }
            }
    } catch (e: Exception) {
        emptyList()
    }

    private fun effect(family: MeasurementFamily, zeroRow: Int?): HistoryOverlapEffect = when {
        family == MeasurementFamily.MAX_DEMAND -> HistoryOverlapEffect.HIDES_DEMAND_MEAN
        zeroRow != null -> HistoryOverlapEffect.NEUTRAL_ZERO_ROW
        else -> HistoryOverlapEffect.BLOCKS_TOTAL
    }

    /** One line per finding, e.g. `ACTIVE rows 3 [2025-01-01..2025-03-01) x 4 [..): 31d, zero row none -> blocks_total`. */
    fun describe(f: HistoryOverlapFinding): String =
        "${f.family} rows ${f.firstRow} [${f.firstStart}..${f.firstEnd}) x ${f.secondRow} " +
            "[${f.secondStart}..${f.secondEnd}): ${f.overlapDays}d, zero row ${f.zeroRow ?: "none"} -> ${f.effect.code}"
}
