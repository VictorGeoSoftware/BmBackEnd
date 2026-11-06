package com.bm.backend.repositories.ports

import java.time.Instant

/**
 * Port (Clean Architecture) for the authenticated user data persistence
 * boundary. Implementations are responsible for at-rest encryption of PII
 * fields (`email`, `displayName`, `photoURL`).
 */
interface UserDataRepositoryPort {

    fun upsertUserData(
        uid: String,
        email: String?,
        displayName: String?,
        photoURL: String?,
        providerIds: List<String>,
        tokenIssuedAt: Instant,
        tokenExpiresAt: Instant
    )

    /**
     * Returns the Firebase uid of the account matching [email] (compared
     * case-insensitively against the decrypted value), or `null` when the
     * account has never synced its data.
     */
    fun findUidByEmail(email: String): String?

    /**
     * Administrative operation: permanently deletes every account row matching
     * [email] (compared case-insensitively against the decrypted value).
     * Returns the number of rows deleted.
     */
    fun deleteByEmail(email: String): Int
}
