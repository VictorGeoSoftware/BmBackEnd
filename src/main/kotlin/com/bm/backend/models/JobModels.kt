package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class JobResponse(
    val jobId: String,
    val status: String,
    val message: String
)

@Serializable
data class JobStatusResponse(
    val jobId: String,
    val status: JobStatus,
    val message: String? = null,
    val progress: Int? = null, // 0-100
    val result: ConsumptionReportResponse? = null,
    val error: String? = null
)

@Serializable
enum class JobStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}

data class Job(
    val jobId: String,
    val ownerUid: String,
    var status: JobStatus,
    var progress: Int = 0,
    var result: ConsumptionReportResponse? = null,
    var error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
