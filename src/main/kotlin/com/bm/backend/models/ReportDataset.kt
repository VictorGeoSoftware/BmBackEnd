package com.bm.backend.models

/**
 * Internal, validated input for the comprehensive report. [comparison] is the client-built request instance
 * (no recalculation); [cups] is the study's cleaned CUPS. No current-supplier name exists (explicit absence).
 */
data class ReportDataset(
    val jobId: String,
    val cups: String,
    val report: ConsumptionReportResponse,
    val history: StudyHistory,
    val snapshot: StudySnapshot,
    val comparison: ComparatorReportPdfRequest
)
