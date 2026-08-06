package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class GrantedUserMutationResponse(
    val success: Boolean,
    val email: String,
    val message: String
)
