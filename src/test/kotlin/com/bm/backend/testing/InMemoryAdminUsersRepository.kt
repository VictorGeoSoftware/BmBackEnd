package com.bm.backend.testing

import com.bm.backend.repositories.ports.AdminUsersRepositoryPort
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory test double for [AdminUsersRepositoryPort].
 *
 * Mirrors the production contract (normalized emails, uniqueness) without
 * touching the database.
 */
class InMemoryAdminUsersRepository : AdminUsersRepositoryPort {

    private val rows = ConcurrentHashMap.newKeySet<String>()

    /** Test setup helper; the production list is managed directly via SQL. */
    fun add(email: String) {
        rows.add(email.trim().lowercase())
    }

    fun remove(email: String) {
        rows.remove(email.trim().lowercase())
    }

    override fun existsByEmail(email: String): Boolean = rows.contains(email)
}
