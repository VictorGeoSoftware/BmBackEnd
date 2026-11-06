package com.bm.backend.models

/**
 * Typed dated history retained on the owner's in-memory job.
 * When [status] is UNAVAILABLE, [intervals] is empty and [reason] holds a single code
 * (never values or the CUPS). When AVAILABLE, [reason] is null.
 */
data class StudyHistory(
    val cups: String,
    val status: StudyHistoryStatus,
    val reason: String?,
    val intervals: List<StudyHistoryInterval>
) {
    companion object {
        fun unavailable(cups: String, reason: String): StudyHistory =
            StudyHistory(cups = cups, status = StudyHistoryStatus.UNAVAILABLE, reason = reason, intervals = emptyList())
    }
}
