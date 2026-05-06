package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class UserActivityFirstConnectionResponse(
    val email: String,
    val firstConnectedAt: Long?
)

@Serializable
data class UserActivityFirstConnectionListResponse(
    val success: Boolean,
    val users: List<UserActivityFirstConnectionResponse>
)
