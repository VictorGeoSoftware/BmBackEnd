package com.bm.backend.testing

import com.bm.backend.repositories.ports.UserDataRepositoryPort
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory test double for [UserDataRepositoryPort].
 *
 * Mirrors the production contract without touching the database, so
 * [com.bm.backend.services] unit tests can run fast.
 */
class InMemoryUserDataRepository : UserDataRepositoryPort {

    private data class Row(var email: String?)

    private val rows = ConcurrentHashMap<String, Row>()

    override fun upsertUserData(
        uid: String,
        email: String?,
        displayName: String?,
        photoURL: String?,
        providerIds: List<String>,
        tokenIssuedAt: Instant,
        tokenExpiresAt: Instant
    ) {
        rows.compute(uid) { _, existing ->
            existing?.apply { this.email = email } ?: Row(email = email)
        }
    }

    fun findEmail(uid: String): String? = rows[uid]?.email

    override fun findUidByEmail(email: String): String? {
        val target = email.trim().lowercase()
        return rows.entries
            .firstOrNull { (_, row) -> row.email?.trim()?.lowercase() == target }
            ?.key
    }

    override fun deleteByEmail(email: String): Int {
        val target = email.trim().lowercase()
        val matchingUids = rows.entries
            .filter { (_, row) -> row.email?.trim()?.lowercase() == target }
            .map { it.key }
        matchingUids.forEach { rows.remove(it) }
        return matchingUids.size
    }
}
