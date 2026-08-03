package com.bm.backend.models

import kotlinx.serialization.Serializable

/**
 * Request body for the administrative "reset device binding" endpoint.
 * The [email] identifies the account whose one-phone binding should be
 * cleared so a replacement device can bind on the next login.
 */
@Serializable
data class AdminResetDeviceRequest(
    val email: String? = null
)
