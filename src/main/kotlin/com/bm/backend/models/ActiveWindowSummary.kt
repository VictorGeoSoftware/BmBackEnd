package com.bm.backend.models

import java.time.LocalDate

/**
 * Active energy over one `[start, endExclusive)` window. Only fully contained rows are summed;
 * crossing rows are listed, never prorated. [total] is set only when COMPLETE, [partialTotal] only when INCOMPLETE.
 */
data class ActiveWindowSummary(
    val kind: ActiveWindowKind,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val status: ActiveWindowStatus,
    val reason: String?,
    val windowDays: Long,
    val coveredDays: Long,
    val contained: List<Int>,
    val crossing: List<Int>,
    val gaps: List<CoverageGap>,
    val overlapDisclosures: List<CoverageOverlap>,
    val periodTotals: PeriodValues,
    val total: Double?,
    val partialTotal: Double?,
    val shares: PeriodValues?,
    val nullCells: Int
)
