package com.bm.backend.models

import kotlinx.serialization.Serializable

/**
 * Response for the readiness probe (`GET /health/ready`, and its backwards
 * compatible alias `GET /health`).
 *
 * The field names and values (`healthy`/`degraded`, `connected`/`unreachable`)
 * are preserved from the original single `/health` endpoint because deploy
 * workflows and Docker healthchecks already parse them.
 */
@Serializable
data class ReadinessResponse(
    val status: String,
    val database: String,
    val timestamp: String
)
