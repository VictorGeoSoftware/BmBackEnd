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
        tokenExpiresAt: Instant,
        phoneUuid: String? = null
    )

    /**
     * Returns the device identifier currently bound to [uid], or `null` when
     * no row exists yet or no device has been bound.
     */
    fun findPhoneUuid(uid: String): String?

    /**
     * Administrative operation: unbinds the device from every account matching
     * [email] (compared case-insensitively against the decrypted value), so a
     * replacement device can bind on the next login. Returns the number of
     * rows updated.
     */
    fun clearPhoneUuidByEmail(email: String): Int

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
