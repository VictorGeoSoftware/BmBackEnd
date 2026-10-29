package com.bm.backend.models

import java.time.LocalDate

/**
 * Shared day range `[start, endExclusive)` of two usable intervals of one family.
 * Indices are source positions in `StudyHistory.intervals`; overlap meaning is unresolved.
 */
data class CoverageOverlap(
    val firstIndex: Int,
    val secondIndex: Int,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val days: Long
)
