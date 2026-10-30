package com.bm.backend.services

import com.bm.backend.models.JobStatus
import com.bm.backend.models.ReportDataset
import com.bm.backend.models.ReportDatasetRejection
import com.bm.backend.models.ReportDatasetRequest
import com.bm.backend.models.ReportDatasetResult
import com.bm.backend.utils.ReportDatasetValidator

/**
 * Assembles the internal Premium report dataset from the requester's own completed study and the client-built
 * Basic comparison, reused by identity. No amount is recalculated; another owner's job is indistinguishable
 * from a missing or expired (older than 1 h) one. Exposed through `POST /reports/premium-pdf`.
 */
class ReportDatasetService(
    private val jobService: JobService,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun assemble(request: ReportDatasetRequest, requesterUid: String): ReportDatasetResult {
        val job = jobService.getActiveJob(request.jobId, clock())
            ?.takeIf { it.ownerUid == requesterUid }
            ?: return ReportDatasetResult.Rejected(ReportDatasetRejection.STUDY_NOT_FOUND)
        val report = job.result
        val history = job.history
        val snapshot = job.snapshot
        if (job.status != JobStatus.COMPLETED || report == null || history == null || snapshot == null) {
            return ReportDatasetResult.Rejected(ReportDatasetRejection.STUDY_NOT_READY)
        }
        ReportDatasetValidator.validate(request.comparison, report)?.let { return ReportDatasetResult.Rejected(it) }
        return ReportDatasetResult.Assembled(
            ReportDataset(
                jobId = job.jobId,
                cups = report.consumptionData.cups,
                report = report,
                history = history,
                snapshot = snapshot,
                comparison = request.comparison
            )
        )
    }
}
