package com.bm.backend.services

import com.bm.backend.models.UserActivityFirstConnectionResponse
import com.bm.backend.models.UserActivityUserResponse
import com.bm.backend.repositories.ports.UserActivityRepositoryPort

class UserActivityService(
    private val userActivityRepository: UserActivityRepositoryPort
) {

    fun setUserOnline(name: String, email: String) {
        val normalizedEmail = normalizeEmail(email)
        val normalizedName = normalizeName(name, normalizedEmail)
        userActivityRepository.setOnline(
            name = normalizedName,
            email = normalizedEmail
        )
    }

    fun setUserOffline(name: String, email: String) {
        val normalizedEmail = normalizeEmail(email)
        val normalizedName = normalizeName(name, normalizedEmail)
        userActivityRepository.setOffline(
            name = normalizedName,
            email = normalizedEmail
        )
    }

    fun incrementMonthlyUsageCounter(name: String, email: String) {
        val normalizedEmail = normalizeEmail(email)
        val normalizedName = normalizeName(name, normalizedEmail)
        userActivityRepository.incrementMonthlyUsageCounter(
            name = normalizedName,
            email = normalizedEmail
        )
    }

    fun getUsersActivity(): List<UserActivityUserResponse> {
        return userActivityRepository.getUsersActivity()
    }

    fun getUsersFirstConnection(): List<UserActivityFirstConnectionResponse> {
        return userActivityRepository.getUsersFirstConnection()
    }

    private fun normalizeEmail(email: String): String {
        val normalizedEmail = email.trim().lowercase()
        require(normalizedEmail.isNotBlank()) { "email is required" }
        require('@' in normalizedEmail) { "email format is invalid" }
        return normalizedEmail
    }

    private fun normalizeName(name: String, normalizedEmail: String): String {
        val normalizedName = name.trim()
        if (normalizedName.isNotBlank()) {
            return normalizedName
        }

        return normalizedEmail.substringBefore('@').ifBlank { normalizedEmail }
    }
}
