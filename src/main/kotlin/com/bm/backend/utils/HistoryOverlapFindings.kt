package com.bm.backend.utils

import com.bm.backend.models.HistoryCoverage
import com.bm.backend.models.HistoryOverlapEffect
import com.bm.backend.models.HistoryOverlapFinding
import com.bm.backend.models.MeasurementFamily
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval

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
                        allZero(values(first, family)) -> overlap.firstIndex + 1
                        allZero(values(second, family)) -> overlap.secondIndex + 1
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

    private fun values(interval: StudyHistoryInterval, family: MeasurementFamily): PeriodValues = when (family) {
        MeasurementFamily.ACTIVE -> interval.active
        MeasurementFamily.REACTIVE -> interval.reactive
        MeasurementFamily.MAX_DEMAND -> interval.maxDemand
    }

    /** Same rule as A1/R2: all six cells exactly 0.0; blanks never qualify. */
    private fun allZero(v: PeriodValues): Boolean =
        listOf(v.p1, v.p2, v.p3, v.p4, v.p5, v.p6).all { it == 0.0 }

    /** One line per finding, e.g. `ACTIVE rows 3 [2025-01-01..2025-03-01) x 4 [..): 31d, zero row none -> blocks_total`. */
    fun describe(f: HistoryOverlapFinding): String =
        "${f.family} rows ${f.firstRow} [${f.firstStart}..${f.firstEnd}) x ${f.secondRow} " +
            "[${f.secondStart}..${f.secondEnd}): ${f.overlapDays}d, zero row ${f.zeroRow ?: "none"} -> ${f.effect.code}"
}
