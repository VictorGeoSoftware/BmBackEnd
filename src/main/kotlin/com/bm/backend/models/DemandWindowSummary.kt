package com.bm.backend.models

import java.time.LocalDate

/** Maximeter demand window over `[start, endExclusive)`; [partial] is true when not COMPLETE. */
data class DemandWindowSummary(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val status: DemandWindowStatus,
    val reason: String?,
    val windowDays: Long,
    val coveredDays: Long,
    val contained: List<Int>,
    val crossing: List<Int>,
    val gaps: List<CoverageGap>,
    val overlapDisclosures: List<CoverageOverlap>,
    val nullCells: Int,
    val partial: Boolean
)
