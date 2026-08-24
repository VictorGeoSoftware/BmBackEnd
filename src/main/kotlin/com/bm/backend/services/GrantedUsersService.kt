package com.bm.backend.services

import com.bm.backend.models.GrantedUserResponse
import com.bm.backend.repositories.ports.GrantedUsersRepositoryPort
import com.bm.backend.repositories.ports.TransactionRunnerPort
import com.bm.backend.repositories.ports.UserActivityRepositoryPort
import com.bm.backend.repositories.ports.UserConsumptionRepositoryPort
import com.bm.backend.repositories.ports.UserDataRepositoryPort
import org.slf4j.LoggerFactory

/**
 * Business logic for managing granted accounts from the BmWeb "Usuarios"
 * dashboard: listing (joined with activity data), adding, and deleting.
 *
 * Deleting a grant performs a FULL WIPE of the account: the grant row plus
 * every `user_data`, `user_activity` and `user_consumption` row, and revokes
 * the account's Firebase refresh tokens so active sessions die immediately.
 */
class GrantedUsersService(
    private val grantedUsersRepository: GrantedUsersRepositoryPort,
    private val userDataRepository: UserDataRepositoryPort,
    private val userActivityRepository: UserActivityRepositoryPort,
    private val userConsumptionRepository: UserConsumptionRepositoryPort,
    private val userAccountRevoker: UserAccountRevoker,
    private val forceLogoutNotifier: ForceLogoutNotifier,
    private val transactionRunner: TransactionRunnerPort
) {
    private val logger = LoggerFactory.getLogger(GrantedUsersService::class.java)

    sealed interface AddGrantResult {
        data class Added(val email: String) : AddGrantResult
        data class AlreadyExists(val email: String) : AddGrantResult
        data object InvalidEmail : AddGrantResult
    }

    sealed interface DeleteGrantResult {
        data class Deleted(val email: String) : DeleteGrantResult
        data class NotFound(val email: String) : DeleteGrantResult
        data object InvalidEmail : DeleteGrantResult
    }

    fun addGrant(rawEmail: String?): AddGrantResult {
        val email = normalize(rawEmail) ?: return AddGrantResult.InvalidEmail
        val inserted = grantedUsersRepository.insert(email)
        if (!inserted) {
            logger.info("AUDIT: Grant add skipped, already exists email={}", email)
            return AddGrantResult.AlreadyExists(email)
        }
        logger.info("AUDIT: Grant added email={}", email)
        return AddGrantResult.Added(email)
    }

    fun listGrants(): List<GrantedUserResponse> {
        val activityByEmail = userActivityRepository.getUsersActivity()
            .associateBy { it.email.trim().lowercase() }
        val firstConnectionByEmail = userActivityRepository.getUsersFirstConnection()
            .associateBy({ it.email.trim().lowercase() }, { it.firstConnectedAt })

        return grantedUsersRepository.findAll().map { grant ->
            val activity = activityByEmail[grant.email]
            GrantedUserResponse(
                email = grant.email,
                grantedAt = grant.createdAt.toEpochMilli(),
                name = activity?.name,
                isOnline = activity?.isOnline,
                monthlyUsageCount = activity?.monthlyUsageCount,
                usageStartedAt = firstConnectionByEmail[grant.email],
                lastConnectedAt = activity?.lastConnectedAt,
                lastDisconnectedAt = activity?.lastDisconnectedAt,
                activityUpdatedAt = activity?.updatedAt
            )
        }
    }

    suspend fun deleteGrant(rawEmail: String?): DeleteGrantResult {
        val email = normalize(rawEmail) ?: return DeleteGrantResult.InvalidEmail
        if (!grantedUsersRepository.existsByEmail(email)) {
            return DeleteGrantResult.NotFound(email)
        }

        // Resolve the uid before wiping so Firebase sessions can be revoked
        // and the consumption payload (keyed by uid) removed.
        val uid = userDataRepository.findUidByEmail(email)

        // Revoke first: a still-valid refresh token would otherwise let the
        // account mint new ID tokens until the wipe lands.
        if (uid != null) {
            userAccountRevoker.revokeRefreshTokens(uid)
        }

        // Unit of work spanning the four user tables; the repository methods
        // join this transaction (Exposed reuses the thread-local transaction).
        transactionRunner.inTransaction {
            if (uid != null) {
                userConsumptionRepository.deleteByUid(uid)
            }
            userActivityRepository.deleteByEmail(email)
            userDataRepository.deleteByEmail(email)
            grantedUsersRepository.deleteByEmail(email)
        }
        logger.info("AUDIT: Grant deleted with full data wipe email={} uid={}", email, uid)

        forceLogoutNotifier.notifyForceLogout(email)
        return DeleteGrantResult.Deleted(email)
    }

    private fun normalize(email: String?): String? {
        val normalized = email?.trim()?.lowercase().orEmpty()
        if (!EMAIL_PATTERN.matches(normalized)) return null
        return normalized
    }

    companion object {
        private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
