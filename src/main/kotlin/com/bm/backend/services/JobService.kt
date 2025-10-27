package com.bm.backend.services

import com.bm.backend.models.Job
import com.bm.backend.models.JobStatus
import com.bm.backend.models.ConsumptionReportResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JobService {
    private val jobs = ConcurrentHashMap<String, Job>()
    
    // Auto-cleanup jobs older than 1 hour
    private val JOB_EXPIRY_MS = 60 * 60 * 1000L
    
    fun createJob(): String {
        val jobId = UUID.randomUUID().toString()
        jobs[jobId] = Job(
            jobId = jobId,
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
    
    fun getAllJobs(): Map<String, Job> = jobs.toMap()
}
