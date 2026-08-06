package com.bm.backend.testing

import com.bm.backend.models.GrantedUser
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

    private val rows = ConcurrentHashMap<String, Instant>()

    override fun existsByEmail(email: String): Boolean = rows.containsKey(email)

    override fun insert(email: String): Boolean {
        if (rows.containsKey(email)) return false
        rows[email] = Instant.now()
        return true
    }

    override fun deleteByEmail(email: String): Int = if (rows.remove(email) != null) 1 else 0

    override fun findAll(): List<GrantedUser> = rows.entries
        .map { (email, createdAt) -> GrantedUser(email = email, createdAt = createdAt) }
        .sortedByDescending { it.createdAt }
}
