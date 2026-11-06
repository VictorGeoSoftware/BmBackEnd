package com.bm.backend.models

import java.time.LocalDate

/**
 * Descriptive summary of recorded maximeter demand, computed on demand (not stored, not exposed to clients).
 * When [status] is UNAVAILABLE, [reason] holds a code and the lists are empty.
 */
data class DemandSummary(
    val status: StudyHistoryStatus,
    val reason: String?,
    val anchorDate: LocalDate?,
    val window: DemandWindowSummary?,
    val contractedSource: DemandContractedSource,
    val snapshotReason: String?,
    val periods: List<DemandPeriodStatistic>,
    val series: List<DemandSeriesPoint>,
    /** Maximeter rows of the last 24 months up to the anchor ([com.bm.backend.utils.ReportHistoryRange]; same construction as [series]), date order. */
    val historySeries: List<DemandSeriesPoint> = emptyList()
) {
    companion object {
        fun unavailable(reason: String): DemandSummary = DemandSummary(
            status = StudyHistoryStatus.UNAVAILABLE,
            reason = reason,
            anchorDate = null,
            window = null,
            contractedSource = DemandContractedSource.SNAPSHOT_CONTRACTED_POWER_KW,
            snapshotReason = null,
            periods = emptyList(),
            series = emptyList()
        )
    }
}
