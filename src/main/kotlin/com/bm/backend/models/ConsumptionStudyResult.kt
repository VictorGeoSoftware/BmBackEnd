package com.bm.backend.models

/** Internal pipeline output: the client-visible annual report plus the retained (not exposed) history and snapshot. */
data class ConsumptionStudyResult(
    val report: ConsumptionReportResponse,
    val history: StudyHistory,
    val snapshot: StudySnapshot,
    /** The n8n execution that fetched the data; null when the workflow does not report it. */
    val n8nRun: N8nRunReference? = null
)
