package com.bm.backend.services

import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Guards administrative endpoints with a shared secret provided via the
 * [ENV_ADMIN_TOKEN] environment variable and sent by callers in the
 * [HEADER] request header.
 *
 * When the variable is unset the admin API is disabled and every request is
 * denied, so a misconfigured deployment never exposes unauthenticated
 * administrative actions. Set the env var to activate, e.g.:
 *
 *     BM_ADMIN_TOKEN=some-long-random-secret
 */
class AdminAuthService(
    private val token: String?
) {
    private val logger = LoggerFactory.getLogger(AdminAuthService::class.java)

    val enabled: Boolean = !token.isNullOrBlank()

    init {
        if (enabled) {
            logger.info("Admin API ENABLED (shared-secret token configured)")
        } else {
            logger.warn(
                "Admin API DISABLED ({} not set). Admin endpoints will reject all requests.",
                ENV_ADMIN_TOKEN
            )
        }
    }

    /**
     * Returns true when [providedToken] matches the configured secret. The
     * comparison is constant-time to avoid leaking the secret via timing.
     */
    fun isAuthorized(providedToken: String?): Boolean {
        val expected = token?.takeIf { enabled } ?: return false
        val provided = providedToken?.trim().orEmpty()
        if (provided.isEmpty()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    companion object {
        const val ENV_ADMIN_TOKEN = "BM_ADMIN_TOKEN"
        const val HEADER = "X-Admin-Token"

        fun fromEnv(rawToken: String? = System.getenv(ENV_ADMIN_TOKEN)): AdminAuthService =
            AdminAuthService(token = rawToken?.trim()?.takeIf { it.isNotBlank() })
    }
}
