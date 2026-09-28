package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
enum class UserTier {
    BASIC,
    PREMIUM;

    val capabilities: Set<UserCapability>
        get() = when (this) {
            BASIC -> emptySet()
            PREMIUM -> setOf(UserCapability.COMPREHENSIVE_COMPARATOR_PDF)
        }
}
