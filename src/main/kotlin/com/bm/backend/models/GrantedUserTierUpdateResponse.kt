package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class GrantedUserTierUpdateResponse(
    val success: Boolean,
    val email: String,
    val tier: UserTier,
    val message: String
)
