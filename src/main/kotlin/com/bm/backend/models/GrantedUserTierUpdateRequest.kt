package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class GrantedUserTierUpdateRequest(
    val tier: UserTier? = null
)
