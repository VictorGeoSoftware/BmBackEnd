package com.bm.backend.models

import java.time.Instant

data class GrantedFirebaseUser(
    val uid: String,
    val email: String,
    val name: String?,
    val tokenIssuedAt: Instant,
    val tokenExpiresAt: Instant,
    val tier: UserTier
) {
    val capabilities: Set<UserCapability>
        get() = tier.capabilities
}
