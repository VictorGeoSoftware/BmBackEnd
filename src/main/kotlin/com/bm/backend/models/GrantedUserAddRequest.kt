package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class GrantedUserAddRequest(
    val email: String? = null
)
