package com.bm.backend.repositories.ports

/**
 * Port (Clean Architecture) for the admin-users (BmWeb access allowlist)
 * store.
 *
 * Implementations must guarantee that emails are stored and matched
 * normalized (trimmed, lowercase), with one row per email. The list is
 * managed directly via SQL, so only lookups are exposed here.
 */
interface AdminUsersRepositoryPort {

    fun existsByEmail(email: String): Boolean
}
