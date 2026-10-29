package com.bm.backend.models

import java.time.LocalDate

/**
 * Descriptive summary of recorded reactive energy, computed on demand (not stored, not exposed to clients).
 * Ratios always state their active basis. When [status] is UNAVAILABLE, [reason] holds a code.
 */
data class ReactiveSummary(
    val status: StudyHistoryStatus,
    val reason: String?,
    val anchorDate: LocalDate?,
    val window: ReactiveWindowSummary?,
    val activeBasis: RatioActiveBasis,
    val activeWindowStatus: ReactiveWindowStatus?,
    val activeWindowStart: LocalDate?,
    val activeWindowEndExclusive: LocalDate?,
    val activeTotals: PeriodValues?,
    val activeTotal: Double?,
    val ratioReason: String?,
    val ratios: List<ReactiveRatio>,
    val overallRatio: ReactiveRatio,
    val series: List<ReactiveSeriesPoint>
) {
    companion object {
        fun unavailable(reason: String, ratioReason: String? = null): ReactiveSummary = ReactiveSummary(
            status = StudyHistoryStatus.UNAVAILABLE,
            reason = reason,
            anchorDate = null,
            window = null,
            activeBasis = RatioActiveBasis.HISTORY_ACTIVE_LATEST_12M,
            activeWindowStatus = null,
            activeWindowStart = null,
            activeWindowEndExclusive = null,
            activeTotals = null,
            activeTotal = null,
            ratioReason = ratioReason,
            ratios = emptyList(),
            overallRatio = ReactiveRatio(null, null, null, null, ratioReason),
            series = emptyList()
        )
    }
}
