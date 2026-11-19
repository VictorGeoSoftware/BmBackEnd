package com.bm.backend.services

class TeachMeAccessPolicy(environment: String, allowedEmails: String?) {
    val enabled = environment == "qa"
    private val emails = allowedEmails.orEmpty().split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun allows(email: String?): Boolean = enabled && email?.trim()?.lowercase() in emails
}
