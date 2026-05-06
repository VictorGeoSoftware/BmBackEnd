package com.bm.backend.repositories.ports

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
        tokenIssuedAt: Long,
        tokenExpiresAt: Long
    )
}
