package com.bm.backend.models

/** Internal pipeline output: the client-visible annual report plus the retained (not exposed) history and snapshot. */
data class ConsumptionStudyResult(
    val report: ConsumptionReportResponse,
    val history: StudyHistory,
    val snapshot: StudySnapshot
)
