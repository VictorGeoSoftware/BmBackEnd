package com.bm.backend.models

import java.time.Instant

/**
 * Domain model for one row of `granted_users`: an account permitted to access
 * the app. The email is always stored normalized (trimmed, lowercase).
 */
data class GrantedUser(
    val email: String,
    val createdAt: Instant
)
