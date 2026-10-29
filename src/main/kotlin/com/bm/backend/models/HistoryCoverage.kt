package com.bm.backend.models

import java.time.LocalDate

/**
 * Coverage of a [StudyHistory], computed on demand (not stored, not exposed to clients).
 * When [status] is UNAVAILABLE, [reason] holds a code and [families] is empty.
 */
data class HistoryCoverage(
    val status: StudyHistoryStatus,
    val reason: String?,
    val anchorDate: LocalDate?,
    val invertedIntervals: List<Int>,
    val families: Map<MeasurementFamily, FamilyCoverage>
) {
    companion object {
        fun unavailable(reason: String): HistoryCoverage =
            HistoryCoverage(StudyHistoryStatus.UNAVAILABLE, reason, null, emptyList(), emptyMap())
    }
}
