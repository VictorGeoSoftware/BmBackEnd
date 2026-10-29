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
 * from a missing one. Not exposed by any route yet (see `report-api-access`).
 */
class ReportDatasetService(private val jobService: JobService) {

    fun assemble(request: ReportDatasetRequest, requesterUid: String): ReportDatasetResult {
        val job = jobService.getJob(request.jobId)
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
