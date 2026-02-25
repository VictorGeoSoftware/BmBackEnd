package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class UserDataRequest(
    val uid: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val photoURL: String? = null,
    val providerIds: List<String> = emptyList()
)
