package com.bm.backend.models

import java.time.LocalDate

/**
 * Classification of one family's usable intervals against a `[start, endExclusive)` analysis window
 * of [months] calendar months anchored on the latest reading date. Crossing intervals are listed, never prorated.
 */
data class WindowCoverage(
    val months: Int,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val contained: List<Int>,
    val crossing: List<Int>,
    val containedCoveredDays: Long,
    val complete: Boolean
)
