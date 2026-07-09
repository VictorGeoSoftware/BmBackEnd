package com.bm.backend.testing

import com.bm.backend.repositories.ports.UserDataRepositoryPort
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory test double for [UserDataRepositoryPort].
 *
 * Mirrors the production contract (including first-device-wins retention of
 * `phoneUuid`) without touching the database, so [com.bm.backend.services]
 * unit tests can run fast.
 */
class InMemoryUserDataRepository : UserDataRepositoryPort {

    private data class Row(
        var email: String?,
        var phoneUuid: String?
    )

    private val rows = ConcurrentHashMap<String, Row>()

    override fun upsertUserData(
        uid: String,
        email: String?,
        displayName: String?,
        photoURL: String?,
        providerIds: List<String>,
        tokenIssuedAt: Instant,
        tokenExpiresAt: Instant,
        phoneUuid: String?
    ) {
        rows.compute(uid) { _, existing ->
            existing?.apply {
                this.email = email
                // First-device-wins: keep the already-bound device.
                this.phoneUuid = this.phoneUuid ?: phoneUuid
            } ?: Row(email = email, phoneUuid = phoneUuid)
        }
    }

    override fun findPhoneUuid(uid: String): String? = rows[uid]?.phoneUuid

    override fun clearPhoneUuidByEmail(email: String): Int {
        val target = email.trim().lowercase()
        var count = 0
        rows.values.forEach { row ->
            if (row.email?.trim()?.lowercase() == target) {
                row.phoneUuid = null
                count++
            }
        }
        return count
    }
}
