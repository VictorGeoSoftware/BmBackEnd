package com.bm.backend.models

import java.time.LocalDate

/** One contained REACTIVE history row of the latest window, as given (kVArh, nulls preserved; not a month). */
data class ReactiveSeriesPoint(
    val index: Int,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val days: Long,
    val values: PeriodValues,
    val total: Double?,
    val inUnresolvedOverlap: Boolean
)
