package com.bm.backend.models

import java.time.LocalDate

/**
 * One contained dated maximeter row (kW, as given; null preserved). Not a month and not a curve.
 * [activeAllZero] marks rows whose active energy cells are all 0.0 (demand is still kept).
 */
data class DemandSeriesPoint(
    val index: Int,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val days: Long,
    val values: PeriodValues,
    val inOverlap: Boolean,
    val activeAllZero: Boolean
)
