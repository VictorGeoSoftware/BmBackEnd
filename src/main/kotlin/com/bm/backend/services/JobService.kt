package com.bm.backend.services

import com.bm.backend.models.ConsumptionReportResponse
import com.bm.backend.models.Job
import com.bm.backend.models.JobStatus
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudySnapshot
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class JobService {
    private val jobs = ConcurrentHashMap<String, Job>()
    
    // Auto-cleanup jobs older than 1 hour
    private val JOB_EXPIRY_MS = 60 * 60 * 1000L
    
    fun createJob(ownerUid: String): String {
        val jobId = UUID.randomUUID().toString()
        jobs[jobId] = Job(
            jobId = jobId,
            ownerUid = ownerUid,
            status = JobStatus.PENDING
        )
        cleanupOldJobs()
        return jobId
    }
    
    fun getJob(jobId: String): Job? {
        return jobs[jobId]
    }
    
    fun updateJobStatus(jobId: String, status: JobStatus, progress: Int? = null) {
        jobs[jobId]?.let { job ->
            job.status = status
            progress?.let { job.progress = it }
        }
    }
    
    fun completeJob(jobId: String, result: ConsumptionReportResponse) {
        jobs[jobId]?.let { job ->
            job.status = JobStatus.COMPLETED
            job.progress = 100
            job.result = result
        }
    }
    
    /** Completes a fetched study, retaining its dated history and supply snapshot alongside the annual result. */
    fun completeJob(jobId: String, result: ConsumptionReportResponse, history: StudyHistory, snapshot: StudySnapshot) {
        jobs[jobId]?.let { job ->
            job.history = history
            job.snapshot = snapshot
            job.status = JobStatus.COMPLETED
            job.progress = 100
            job.result = result
        }
    }

    /** Owner-scoped history read: null when the job is missing or owned by another user. */
    fun getHistory(jobId: String, ownerUid: String): StudyHistory? {
        val job = jobs[jobId] ?: return null
        return if (job.ownerUid == ownerUid) job.history else null
    }

    /** Owner-scoped snapshot read: null when the job is missing or owned by another user. */
    fun getSnapshot(jobId: String, ownerUid: String): StudySnapshot? {
        val job = jobs[jobId] ?: return null
        return if (job.ownerUid == ownerUid) job.snapshot else null
    }

    fun failJob(jobId: String, error: String) {
        jobs[jobId]?.let { job ->
            job.status = JobStatus.FAILED
            job.error = error
        }
    }
    
    private fun cleanupOldJobs() {
        val now = System.currentTimeMillis()
        jobs.entries.removeIf { (_, job) ->
            now - job.createdAt > JOB_EXPIRY_MS
        }
    }
}
