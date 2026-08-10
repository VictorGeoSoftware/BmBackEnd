package com.bm.backend.models

import java.time.Instant

/**
 * Domain model for one row of `admin_users`: an account permitted to access
 * the BmWeb dashboard and the admin endpoints. The email is always stored
 * normalized (trimmed, lowercase).
 */
data class AdminUser(
    val email: String,
    val createdAt: Instant
)
