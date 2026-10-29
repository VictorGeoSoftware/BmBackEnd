package com.bm.backend.models

/** Outcome of a Premium report request; only [Generated] carries bytes, no variant carries customer values. */
sealed interface PremiumReportResult {
    class Generated(val bytes: ByteArray) : PremiumReportResult
    data object Forbidden : PremiumReportResult
    data class Rejected(val reason: ReportDatasetRejection) : PremiumReportResult
    data object Unavailable : PremiumReportResult
    data class Failed(val errorType: String) : PremiumReportResult
}
