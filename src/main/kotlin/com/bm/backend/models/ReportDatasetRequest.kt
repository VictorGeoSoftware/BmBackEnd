package com.bm.backend.models

import kotlinx.serialization.Serializable

/** A Premium report request: the owned study plus the client-built Basic comparison, reused verbatim. */
@Serializable
data class ReportDatasetRequest(
    val jobId: String,
    val comparison: ComparatorReportPdfRequest
)
