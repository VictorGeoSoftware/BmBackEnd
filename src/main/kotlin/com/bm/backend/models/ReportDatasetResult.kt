package com.bm.backend.models

sealed interface ReportDatasetResult {
    data class Assembled(val dataset: ReportDataset) : ReportDatasetResult
    data class Rejected(val reason: ReportDatasetRejection) : ReportDatasetResult
}
