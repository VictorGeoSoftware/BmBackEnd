package com.bm.backend.repositories.ports

import com.bm.backend.models.UserActivityFirstConnectionResponse
import com.bm.backend.models.UserActivityUserResponse

/**
 * Port (Clean Architecture) for user-activity persistence.
 *
 * Implementations must guarantee:
 * - Online/offline transitions are idempotent per email (one row per email).
 * - `monthlyUsageCount` resets when the stored month key differs from the
 *   current one.
 * - `usageStartedAt` is set on first insert and never overwritten afterwards.
 */
interface UserActivityRepositoryPort {

    fun setOnline(name: String, email: String)

    fun setOffline(name: String, email: String)

    fun incrementMonthlyUsageCounter(name: String, email: String)

    fun getUsersActivity(): List<UserActivityUserResponse>

    fun getUsersFirstConnection(): List<UserActivityFirstConnectionResponse>
}
