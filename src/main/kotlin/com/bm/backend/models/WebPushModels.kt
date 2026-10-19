package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class WebPushTokenRegisterRequest(
    val token: String? = null,
    val platform: String? = null
)

@Serializable
data class WebPushTokenDeleteRequest(
    val token: String? = null
)

@Serializable
data class WebPushTokenMutationResponse(
    val success: Boolean
)
