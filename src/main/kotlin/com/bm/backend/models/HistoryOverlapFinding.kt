package com.bm.backend.models

import java.time.LocalDate

/**
 * One overlapping pair of history rows within a measurement family, with the effect the current
 * report rules (TECH_DEBT #18: A1, R2, M2/M4) give it. Rows are 1-based source positions so they
 * match the row order of the portal Excel.
 */
data class HistoryOverlapFinding(
    val family: MeasurementFamily,
    val firstRow: Int,
    val firstStart: LocalDate,
    val firstEnd: LocalDate,
    val secondRow: Int,
    val secondStart: LocalDate,
    val secondEnd: LocalDate,
    val overlapDays: Long,
    val zeroRow: Int?,
    val effect: HistoryOverlapEffect
)
