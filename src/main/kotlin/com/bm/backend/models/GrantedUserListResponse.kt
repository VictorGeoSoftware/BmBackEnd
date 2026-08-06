package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class GrantedUserListResponse(
    val success: Boolean,
    val users: List<GrantedUserResponse>
)
