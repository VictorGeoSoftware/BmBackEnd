package com.bm.backend.models

import kotlinx.serialization.Serializable

/**
 * Response for the admin check-access probe: confirms the caller's account is
 * on the admin allowlist.
 */
@Serializable
data class AdminAccessResponse(
    val success: Boolean,
    val email: String? = null
)
