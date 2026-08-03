package com.bm.backend.models

import kotlinx.serialization.Serializable

/**
 * Response for the administrative "reset device binding" endpoint.
 * [reset] is the number of accounts whose device binding was cleared
 * (0 when no account matched the supplied email).
 */
@Serializable
data class AdminResetDeviceResponse(
    val email: String,
    val reset: Int
)
