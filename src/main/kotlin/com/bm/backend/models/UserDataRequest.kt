package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class UserDataRequest(
    val uid: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val photoURL: String? = null,
    val providerIds: List<String> = emptyList(),
    /** Ignored. Still accepted so clients that send a device id keep working (device binding was removed). */
    val phoneUuid: String? = null
)
