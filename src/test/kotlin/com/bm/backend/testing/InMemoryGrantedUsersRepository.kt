package com.bm.backend.testing

import com.bm.backend.models.GrantedUser
import com.bm.backend.models.UserTier
import com.bm.backend.repositories.ports.GrantedUsersRepositoryPort
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory test double for [GrantedUsersRepositoryPort].
 *
 * Mirrors the production contract (normalized emails, uniqueness, newest
 * first) without touching the database.
 */
class InMemoryGrantedUsersRepository : GrantedUsersRepositoryPort {

    private data class Row(val tier: UserTier, val createdAt: Instant)

    private val rows = ConcurrentHashMap<String, Row>()

    override fun findByEmail(email: String): GrantedUser? = rows[email]?.let { row ->
        GrantedUser(email = email, tier = row.tier, createdAt = row.createdAt)
    }

    override fun insert(email: String, tier: UserTier): Boolean {
        if (rows.containsKey(email)) return false
        rows[email] = Row(tier = tier, createdAt = Instant.now())
        return true
    }

    override fun updateTier(email: String, tier: UserTier): Int {
        val existing = rows[email] ?: return 0
        rows[email] = existing.copy(tier = tier)
        return 1
    }

    override fun deleteByEmail(email: String): Int = if (rows.remove(email) != null) 1 else 0

    override fun findAll(): List<GrantedUser> = rows.entries
        .map { (email, row) -> GrantedUser(email = email, tier = row.tier, createdAt = row.createdAt) }
        .sortedByDescending { it.createdAt }
}
