package com.bm.backend.repositories.ports

import com.bm.backend.models.GrantedUser
import com.bm.backend.models.UserTier

/**
 * Port (Clean Architecture) for the granted-users (access allowlist) store.
 *
 * Implementations must guarantee:
 * - Emails are stored and matched normalized (trimmed, lowercase).
 * - One row per email (uniqueness enforced by the store).
 */
interface GrantedUsersRepositoryPort {

    fun findByEmail(email: String): GrantedUser?

    fun existsByEmail(email: String): Boolean = findByEmail(email) != null

    /**
     * Inserts a grant for [email]. Returns false when a grant for that email
     * already exists.
     */
    fun insert(email: String, tier: UserTier = UserTier.BASIC): Boolean

    /** Updates only the product tier. Returns 0 when no grant exists. */
    fun updateTier(email: String, tier: UserTier): Int

    /**
     * Removes the grant for [email]. Returns the number of rows deleted (0 or 1).
     */
    fun deleteByEmail(email: String): Int

    /**
     * Returns all grants, most recently created first.
     */
    fun findAll(): List<GrantedUser>
}
