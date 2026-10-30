package com.bm.backend.models

import java.time.LocalDate

/**
 * Internal, family-agnostic aggregate of one measurement family over `[start, endExclusive)` (D1).
 * Only contained rows are summed; [blockingIndices] are rows in an overlap that is not value-neutral.
 */
data class FamilyWindowAggregate(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val windowDays: Long,
    val coveredDays: Long,
    val contained: List<Int>,
    val crossing: List<Int>,
    val gaps: List<CoverageGap>,
    val overlaps: List<CoverageOverlap>,
    val blockingIndices: Set<Int>,
    val periodSums: PeriodValues,
    val nullCells: Int,
    val nullCellPeriods: Set<Int>
) {
    val hasBlockingOverlap: Boolean get() = blockingIndices.isNotEmpty()
}
