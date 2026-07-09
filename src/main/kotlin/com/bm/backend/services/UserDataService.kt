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
        tokenExpiresAt: Instant,
        phoneUuid: String? = null
    ) {
        val cleanedProviders = providerIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val normalizedPhoneUuid = phoneUuid?.trim()?.takeIf { it.isNotBlank() }

        // One-phone-per-account rule: once an account is bound to a device, a
        // login carrying a different device identifier is rejected until an
        // administrator resets the binding. A missing incoming identifier
        // (older clients) is treated as "no assertion" and left unenforced.
        if (normalizedPhoneUuid != null) {
            val boundPhoneUuid = userDataRepository.findPhoneUuid(uid)
            if (boundPhoneUuid != null && boundPhoneUuid != normalizedPhoneUuid) {
                throw DeviceMismatchException(uid)
            }
        }

        userDataRepository.upsertUserData(
            uid = uid,
            email = email,
            displayName = displayName,
            photoURL = photoURL,
            providerIds = cleanedProviders,
            tokenIssuedAt = tokenIssuedAt,
            tokenExpiresAt = tokenExpiresAt,
            phoneUuid = normalizedPhoneUuid
        )
    }

    /**
     * Administrative operation that unbinds the device from the account(s)
     * matching [email], letting a replacement phone bind on the next login.
     * Returns the number of accounts updated.
     */
    fun resetDeviceBinding(email: String): Int {
        val normalized = email.trim()
        require(normalized.isNotBlank()) { "email is required" }
        return userDataRepository.clearPhoneUuidByEmail(normalized)
    }
}
