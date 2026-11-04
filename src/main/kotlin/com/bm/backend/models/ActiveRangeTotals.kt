package com.bm.backend.models

/**
 * Active energy of the readings contained in the last 24 months ([com.bm.backend.utils.ReportHistoryRange]):
 * per-period totals, overall total and shares. [partial] when the readings do not cover the whole range. All null
 * with a [reason] when no firm figure exists (unresolved overlap, no readings).
 */
data class ActiveRangeTotals(
    val periodTotals: PeriodValues?,
    val total: Double?,
    val shares: PeriodValues?,
    val partial: Boolean,
    val reason: String?
)
