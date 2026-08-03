package com.bm.backend.services

import org.slf4j.LoggerFactory

/**
 * Authorization policy that restricts which accounts may access the app.
 *
 * The allowlist is a set of emails provided via the [ENV_ALLOWLIST] environment
 * variable (comma-separated). In production it is injected from a GitHub secret;
 * locally it comes from `local.properties` via the `launchAll` Gradle task.
 *
 * The policy is DISABLED (every authenticated account allowed) when the variable
 * is unset or empty, preserving the pre-allowlist behavior. As soon as at least
 * one email is configured the policy is ENABLED and fails closed: only listed
 * accounts pass.
 */
class AccessControlService(
    allowedEmails: Set<String>
) {
    private val logger = LoggerFactory.getLogger(AccessControlService::class.java)

    private val allowlist: Set<String> = allowedEmails
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .toSet()

    val enabled: Boolean = allowlist.isNotEmpty()

    init {
        if (enabled) {
            logger.info("Email allowlist ENABLED with {} account(s)", allowlist.size)
        } else {
            logger.warn(
                "Email allowlist DISABLED ({} unset/empty). All authenticated accounts are allowed.",
                ENV_ALLOWLIST
            )
        }
    }

    /**
     * Returns true when the given account is permitted to access the app.
     * When the allowlist is disabled every account is allowed.
     */
    fun isEmailAllowed(email: String?): Boolean {
        if (!enabled) return true
        val normalized = email?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        return normalized in allowlist
    }

    companion object {
        const val ENV_ALLOWLIST = "BM_AUTH_EMAIL_ALLOWLIST"

        fun fromEnv(
            rawAllowlist: String? = System.getenv(ENV_ALLOWLIST)
        ): AccessControlService {
            val emails = rawAllowlist
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
            return AccessControlService(allowedEmails = emails)
        }
    }
}
