package com.bm.backend.services

import com.bm.backend.repositories.ports.AdminUsersRepositoryPort
import org.slf4j.LoggerFactory

/**
 * Authorization policy that restricts which accounts may access the BmWeb
 * dashboard and the admin endpoints.
 *
 * The allowlist lives in the `admin_users` database table, kept separate from
 * `granted_users` (which gates BmApp access) so regular app users never gain
 * administrative privileges. The policy always fails closed: an account is an
 * admin only when a row exists for its normalized email. Lookups hit the
 * database on every call (no caching), so revoking an admin takes effect
 * immediately.
 */
class AdminAccessControlService(
    private val adminUsersRepository: AdminUsersRepositoryPort
) {
    private val logger = LoggerFactory.getLogger(AdminAccessControlService::class.java)

    /**
     * Returns true when the given account is permitted to act as an admin.
     */
    fun isAdmin(email: String?): Boolean {
        val normalized = email?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        val allowed = adminUsersRepository.existsByEmail(normalized)
        if (!allowed) {
            logger.warn("AUDIT: Admin access denied for non-admin account email={}", normalized)
        }
        return allowed
    }
}
