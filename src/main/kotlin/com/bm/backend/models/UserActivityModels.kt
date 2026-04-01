package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class UserActivityEventRequest(
    val name: String,
    val email: String
)

@Serializable
data class UserActivityMutationResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class UserActivityUserResponse(
    val name: String,
    val email: String,
    val isOnline: Boolean,
    val monthlyUsageCount: Int,
    val lastConnectedAt: Long?,
    val lastDisconnectedAt: Long?,
    val updatedAt: Long
)

@Serializable
data class UserActivityListResponse(
    val success: Boolean,
    val users: List<UserActivityUserResponse>
)
