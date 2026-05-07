package com.bm.backend.services

import com.bm.backend.repositories.ports.UserDataRepositoryPort
import java.time.Instant

class UserDataService(
    private val userDataRepository: UserDataRepositoryPort
) {
    fun upsertUserData(
        uid: String,
        email: String?,
        displayName: String?,
        photoURL: String?,
        providerIds: List<String>,
        tokenIssuedAt: Instant,
        tokenExpiresAt: Instant
    ) {
        val cleanedProviders = providerIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        userDataRepository.upsertUserData(
            uid = uid,
            email = email,
            displayName = displayName,
            photoURL = photoURL,
            providerIds = cleanedProviders,
            tokenIssuedAt = tokenIssuedAt,
            tokenExpiresAt = tokenExpiresAt
        )
    }
}
