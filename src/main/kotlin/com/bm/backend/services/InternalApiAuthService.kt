package com.bm.backend.services

import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Guards the machine-to-machine endpoints that n8n calls over the internal
 * Docker network (`consumption-report`, `batch-process-price-tables`). They
 * carry no Firebase token, so without this anyone who reaches the backend
 * could write consumption data or price tables.
 *
 * Nginx denies them publicly and the backend ports are bound to 127.0.0.1,
 * but those are network controls that one config mistake can undo. This
 * shared secret protects the endpoints however they are reached.
 *
 * The caller sends the secret in the [HEADER] header. When
 * [ENV_INTERNAL_API_TOKEN] is unset every request is denied, so a
 * misconfigured deployment never accepts anonymous writes.
 */
class InternalApiAuthService(
    private val token: String?
) {
    private val logger = LoggerFactory.getLogger(InternalApiAuthService::class.java)

    val enabled: Boolean = !token.isNullOrBlank()

    init {
        if (enabled) {
            logger.info("Internal API endpoints ENABLED (shared token configured)")
        } else {
            logger.warn(
                "{} unset: internal endpoints (n8n callbacks) will reject all requests.",
                ENV_INTERNAL_API_TOKEN
            )
        }
    }

    /** Constant-time check of [headerValue] against the configured token. */
    fun isAuthorized(headerValue: String?): Boolean {
        val expected = token?.takeIf { enabled } ?: return false
        val provided = headerValue?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    companion object {
        const val ENV_INTERNAL_API_TOKEN = "INTERNAL_API_TOKEN"
        const val HEADER = "X-Internal-Token"

        fun fromEnv(rawToken: String? = System.getenv(ENV_INTERNAL_API_TOKEN)): InternalApiAuthService =
            InternalApiAuthService(token = rawToken?.trim()?.takeIf { it.isNotBlank() })
    }
}
