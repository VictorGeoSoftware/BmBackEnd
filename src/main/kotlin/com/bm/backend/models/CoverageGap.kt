package com.bm.backend.models

import java.time.LocalDate

/** Uncovered day range `[start, endExclusive)` between usable intervals of one family. */
data class CoverageGap(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val days: Long
)
