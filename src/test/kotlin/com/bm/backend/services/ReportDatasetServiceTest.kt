package com.bm.backend.services

import com.bm.backend.models.ConsumptionStudyResult
import com.bm.backend.models.JobStatus
import com.bm.backend.models.ReportDatasetRejection
import com.bm.backend.models.ReportDatasetRequest
import com.bm.backend.models.ReportDatasetResult
import com.bm.backend.testing.N8nFixtures
import com.bm.backend.testing.ReportComparisonFixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReportDatasetServiceTest {
    private val cups = "ES0000000000000000AA0F"
    private val core = "ES0000000000000000AA"
    private val jobService = JobService()
    private val service = ReportDatasetService(jobService)

    private fun study(): ConsumptionStudyResult = ReportComparisonFixtures.study(cups)

    private fun completedJob(owner: String = "uid-a"): Pair<String, ConsumptionStudyResult> {
        val study = study()
        val jobId = jobService.createJob(owner)
        jobService.completeJob(jobId, study.report, study.history, study.snapshot)
        return jobId to study
    }

    private fun rejection(result: ReportDatasetResult) = (result as ReportDatasetResult.Rejected).reason

    @Test
    fun `A assembles the owned study with the client comparison reused verbatim`() {
        val (jobId, study) = completedJob()
        assertTrue(study.report.proposals.size >= 2)
        val comparison = ReportComparisonFixtures.comparison(study.report, core)
        val request = ReportDatasetRequest(jobId, comparison)
        val dataset = (service.assemble(request, "uid-a") as ReportDatasetResult.Assembled).dataset
        val job = jobService.getJob(jobId)!!
        assertSame(comparison, dataset.comparison)
        assertEquals(study.report.proposals.reversed().map { it.proposalTitle }, dataset.comparison.proposals.map { it.title })
        assertEquals("12.00", dataset.comparison.proposals.first().extraServices)
        assertSame(job.history, dataset.history)
        assertSame(job.snapshot, dataset.snapshot)
        assertSame(job.result, dataset.report)
        assertEquals(cups, dataset.cups)
        assertEquals(jobId, dataset.jobId)
        assertEquals(ReportComparisonFixtures.comparison(study.report, core), dataset.comparison)
    }

    @Test
    fun `A F1 app-format fixed amounts assemble and stay verbatim`() {
        val (jobId, study) = completedJob()
        val base = ReportComparisonFixtures.comparison(study.report, core)
        listOf("12", "12,5", "12,50", "12.").forEach { amount ->
            val comparison = base.copy(
                proposals = listOf(base.proposals[0].copy(extraServices = amount)) + base.proposals.drop(1),
                customerConditions = base.customerConditions.copy(extraServices = amount)
            )
            val dataset = (service.assemble(ReportDatasetRequest(jobId, comparison), "uid-a") as ReportDatasetResult.Assembled).dataset
            assertSame(comparison, dataset.comparison)
            assertEquals(amount, dataset.comparison.proposals[0].extraServices)
            assertEquals(amount, dataset.comparison.customerConditions.extraServices)
        }
    }

    @Test
    fun `B other owner and unknown id are not found, unfinished studies are not ready`() {
        val (jobId, study) = completedJob()
        val comparison = ReportComparisonFixtures.comparison(study.report, core)
        assertEquals(ReportDatasetRejection.STUDY_NOT_FOUND, rejection(service.assemble(ReportDatasetRequest(jobId, comparison), "uid-b")))
        assertEquals(ReportDatasetRejection.STUDY_NOT_FOUND, rejection(service.assemble(ReportDatasetRequest("missing", comparison), "uid-a")))

        val pending = jobService.createJob("uid-a")
        assertEquals(ReportDatasetRejection.STUDY_NOT_READY, rejection(service.assemble(ReportDatasetRequest(pending, comparison), "uid-a")))
        jobService.failJob(pending, "boom")
        assertEquals(JobStatus.FAILED, jobService.getJob(pending)!!.status)
        assertEquals(ReportDatasetRejection.STUDY_NOT_READY, rejection(service.assemble(ReportDatasetRequest(pending, comparison), "uid-a")))

        val legacy = jobService.createJob("uid-a")
        jobService.completeJob(legacy, study.report)
        assertEquals(ReportDatasetRejection.STUDY_NOT_READY, rejection(service.assemble(ReportDatasetRequest(legacy, comparison), "uid-a")))
    }

    @Test
    fun `validator rejection is surfaced for an owned completed study`() {
        val (jobId, study) = completedJob()
        val comparison = ReportComparisonFixtures.comparison(study.report, core).copy(supplyHolder = "--")
        assertEquals(ReportDatasetRejection.HOLDER_MISSING, rejection(service.assemble(ReportDatasetRequest(jobId, comparison), "uid-a")))
    }

    @Test
    fun `request decodes from the synthetic fixture`() {
        val text = requireNotNull(javaClass.getResource("/report/comparison_request.json")).readText()
        val request = N8nFixtures.json.decodeFromString(ReportDatasetRequest.serializer(), text)
        assertEquals("synthetic-job", request.jobId)
        assertEquals("Cliente Ficticio SL", request.comparison.supplyHolder)
        assertEquals(ReportDatasetRejection.STUDY_NOT_FOUND, rejection(service.assemble(request, "uid-a")))
    }
}
