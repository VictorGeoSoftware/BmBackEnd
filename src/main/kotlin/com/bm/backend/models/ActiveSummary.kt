package com.bm.backend.models

import java.time.LocalDate

/**
 * Active-energy summary computed on demand from the retained history (not stored, not exposed to clients).
 * No projection: when [partial] is true the latest 12 months are not fully covered.
 */
data class ActiveSummary(
    val status: StudyHistoryStatus,
    val reason: String?,
    val anchorDate: LocalDate?,
    val partial: Boolean,
    val historyStart: LocalDate?,
    val historyEnd: LocalDate?,
    val windows: List<ActiveWindowSummary>,
    val changePct: Double?,
    val changeReason: String?,
    val highIntervals: List<HighConsumptionInterval>,
    val highIntervalCount: Int,
    val comparisons: List<AnnualSourceComparison>
) {
    companion object {
        const val HIGH_INTERVAL_COUNT = 3

        fun unavailable(reason: String): ActiveSummary = ActiveSummary(
            StudyHistoryStatus.UNAVAILABLE, reason, null, true, null, null, emptyList(),
            null, null, emptyList(), HIGH_INTERVAL_COUNT, emptyList()
        )
    }
}
