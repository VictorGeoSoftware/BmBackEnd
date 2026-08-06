package com.bm.backend.services

import com.bm.backend.repositories.ports.GrantedUsersRepositoryPort
import org.slf4j.LoggerFactory

/**
 * Authorization policy that restricts which accounts may access the app.
 *
 * The allowlist lives in the `granted_users` database table and is managed
 * from the BmWeb "Usuarios" dashboard (see GrantedUsersRoutes). The policy
 * always fails closed: an account is allowed only when a grant row exists for
 * its normalized email. Lookups hit the database on every call (no caching),
 * so revoking a grant takes effect immediately.
 */
class AccessControlService(
    private val grantedUsersRepository: GrantedUsersRepositoryPort
) {
    private val logger = LoggerFactory.getLogger(AccessControlService::class.java)

    /**
     * Returns true when the given account is permitted to access the app.
     */
    fun isEmailAllowed(email: String?): Boolean {
        val normalized = email?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        val allowed = grantedUsersRepository.existsByEmail(normalized)
        if (!allowed) {
            logger.warn("AUDIT: Access denied for non-granted account email={}", normalized)
        }
        return allowed
    }
}
