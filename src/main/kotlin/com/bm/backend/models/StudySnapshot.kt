package com.bm.backend.models

/**
 * Typed supply snapshot retained on the owner's in-memory job (never exposed to clients).
 * [cups] is the study's reference CUPS. When UNAVAILABLE, [fields] and [contractedPowerCheck]
 * are null and [reason] holds a single code (never values or the CUPS).
 */
data class StudySnapshot(
    val cups: String,
    val status: StudySnapshotStatus,
    val reason: String?,
    val fields: StudySnapshotFields?,
    val contractedPowerCheck: ContractedPowerCheck?
) {
    companion object {
        fun unavailable(cups: String, reason: String): StudySnapshot =
            StudySnapshot(cups, StudySnapshotStatus.UNAVAILABLE, reason, fields = null, contractedPowerCheck = null)
    }
}
