package com.bm.backend.models

/** Internal pipeline output: the client-visible annual report plus the retained (not exposed) history. */
data class ConsumptionStudyResult(
    val report: ConsumptionReportResponse,
    val history: StudyHistory
)
