package com.bm.backend.models

import kotlinx.serialization.Serializable

/**
 * Response for the liveness probe (`GET /health/live`). Reports only that the
 * process is running, so it carries no dependency status.
 */
@Serializable
data class LivenessResponse(
    val status: String,
    val timestamp: String
)
