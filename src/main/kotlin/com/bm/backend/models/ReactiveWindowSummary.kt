package com.bm.backend.models

import java.time.LocalDate

/**
 * Recorded reactive energy (kVArh) of the latest `[start, endExclusive)` window. [total] only when COMPLETE,
 * [partialTotal] only when INCOMPLETE. [recordedZero] distinguishes recorded 0.0 from missing data.
 */
data class ReactiveWindowSummary(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val status: ReactiveWindowStatus,
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
    val nullCells: Int,
    val recordedZero: Boolean
)
