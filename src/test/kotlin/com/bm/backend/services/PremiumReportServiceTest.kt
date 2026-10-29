package com.bm.backend.services

import com.bm.backend.models.GrantedFirebaseUser
import com.bm.backend.models.PremiumReportResult
import com.bm.backend.models.ReportDatasetRejection
import com.bm.backend.models.ReportDatasetRequest
import com.bm.backend.models.UserTier
import com.bm.backend.testing.ReportComparisonFixtures
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class PremiumReportServiceTest {
    private val cups = "ES0000000000000000AA0F"
    private val jobs = JobService()
    private var calls = 0
    private var failure: Exception? = null
    private val renderer = PremiumReportRenderer { calls++; failure?.let { throw it }; byteArrayOf(1, 2) }
    private val service = PremiumReportService(ReportDatasetService(jobs), renderer)

    private fun user(tier: UserTier, uid: String = "uid-a") =
        GrantedFirebaseUser(uid, "a@example.com", null, Instant.EPOCH, Instant.EPOCH, tier)

    private fun request(owner: String = "uid-a"): ReportDatasetRequest {
        val study = ReportComparisonFixtures.study(cups)
        val id = jobs.createJob(owner)
        jobs.completeJob(id, study.report, study.history, study.snapshot)
        return ReportDatasetRequest(id, ReportComparisonFixtures.comparison(study.report, "ES0000000000000000AA"))
    }

    @Test
    fun `basic is forbidden without touching the dataset or renderer`() {
        assertSame(PremiumReportResult.Forbidden, service.generate(request(), user(UserTier.BASIC)))
        assertEquals(false, service.isAllowed(user(UserTier.BASIC)))
        assertEquals(0, calls)
    }

    @Test
    fun `premium owner renders exactly once`() {
        val result = service.generate(request(), user(UserTier.PREMIUM))
        assertContentEquals(byteArrayOf(1, 2), assertIs<PremiumReportResult.Generated>(result).bytes)
        assertEquals(1, calls)
    }

    @Test
    fun `dataset rejection never renders`() {
        val result = service.generate(request(owner = "uid-b"), user(UserTier.PREMIUM))
        assertEquals(PremiumReportResult.Rejected(ReportDatasetRejection.STUDY_NOT_FOUND), result)
        assertEquals(0, calls)
    }

    @Test
    fun `unavailable and failing renderers map to stable outcomes without messages`() {
        failure = PremiumReportUnavailableException()
        assertSame(PremiumReportResult.Unavailable, service.generate(request(), user(UserTier.PREMIUM)))
        failure = IllegalStateException("$cups Cliente")
        assertEquals(PremiumReportResult.Failed("IllegalStateException"), service.generate(request(), user(UserTier.PREMIUM)))
        assertEquals(2, calls)
    }
}
