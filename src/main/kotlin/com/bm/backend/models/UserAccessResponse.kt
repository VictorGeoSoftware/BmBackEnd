package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class UserAccessResponse(
    val tier: UserTier,
    val capabilities: List<UserCapability>
)
