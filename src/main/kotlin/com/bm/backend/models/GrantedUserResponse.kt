package com.bm.backend.models

import kotlinx.serialization.Serializable

/**
 * One row of the "Usuarios" management list: the grant itself joined with the
 * user's activity. Activity fields are null when the granted account has never
 * logged in (no `user_activity` row yet).
 */
@Serializable
data class GrantedUserResponse(
    val email: String,
    val grantedAt: Long,
    val name: String?,
    val isOnline: Boolean?,
    val monthlyUsageCount: Int?,
    val usageStartedAt: Long?,
    val lastConnectedAt: Long?,
    val lastDisconnectedAt: Long?,
    val activityUpdatedAt: Long?
)
