package com.bm.backend.services

import com.bm.backend.models.ConsumptionReportResponse
import com.bm.backend.models.JobStatus
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryStatus
import com.bm.backend.models.StudySnapshot
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class JobServiceTest {
    private fun history(cups: String) =
        StudyHistory(cups = cups, status = StudyHistoryStatus.AVAILABLE, reason = null, intervals = emptyList())

    private fun snapshot(cups: String) = StudySnapshot.unavailable(cups, "not_provided")

    @Test
    fun `completing with history retains it for the owner only`() {
        val jobs = JobService()
        val jobId = jobs.createJob("uid-a")
        val report = mockk<ConsumptionReportResponse>()
        val history = history("CUPS-A")
        jobs.completeJob(jobId, report, history, snapshot("CUPS-A"))

        val job = jobs.getJob(jobId)!!
        assertEquals(JobStatus.COMPLETED, job.status)
        assertEquals(100, job.progress)
        assertSame(report, job.result)
        assertSame(history, jobs.getHistory(jobId, "uid-a"))
        assertNull(jobs.getHistory(jobId, "uid-b"))
        assertNull(jobs.getHistory("missing", "uid-a"))
    }

    @Test
    fun `refresh completion keeps the retained history instance`() {
        val jobs = JobService()
        val jobId = jobs.createJob("uid-a")
        val history = history("CUPS-A")
        jobs.completeJob(jobId, mockk(), history, snapshot("CUPS-A"))
        val refreshed = mockk<ConsumptionReportResponse>()

        jobs.completeJob(jobId, refreshed)

        assertSame(refreshed, jobs.getJob(jobId)!!.result)
        assertSame(history, jobs.getJob(jobId)!!.history)
    }

    @Test
    fun `concurrent completions keep each owner's history separate`() {
        val jobs = JobService()
        val ids = (0 until 50).map { i -> jobs.createJob("uid-$i") to history("CUPS-$i") }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        ids.forEach { (id, h) -> pool.submit { start.await(); jobs.completeJob(id, mockk(), h, snapshot(h.cups)) } }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        ids.forEachIndexed { i, (id, h) ->
            assertSame(h, jobs.getHistory(id, "uid-$i"))
            assertEquals("CUPS-$i", jobs.getHistory(id, "uid-$i")!!.cups)
        }
    }

    @Test
    fun `snapshot is owner-scoped and survives refresh completion`() {
        val jobs = JobService()
        val jobId = jobs.createJob("uid-a")
        val history = history("CUPS-A")
        val snapshot = snapshot("CUPS-A")
        jobs.completeJob(jobId, mockk(), history, snapshot)
        assertSame(snapshot, jobs.getSnapshot(jobId, "uid-a"))
        assertNull(jobs.getSnapshot(jobId, "uid-b"))
        assertNull(jobs.getSnapshot("missing", "uid-a"))

        jobs.completeJob(jobId, mockk<ConsumptionReportResponse>())
        assertSame(snapshot, jobs.getSnapshot(jobId, "uid-a"))
        assertSame(history, jobs.getHistory(jobId, "uid-a"))
    }
}
