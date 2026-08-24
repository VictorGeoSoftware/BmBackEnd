package com.bm.backend.services

import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Guards the Prometheus scrape endpoint with a shared secret provided via the
 * [ENV_METRICS_TOKEN] environment variable and sent by the scraper as a
 * standard `Authorization: Bearer <token>` header.
 *
 * Metrics are not harmless: they enumerate every route name and expose traffic
 * volumes, error rates and JVM internals — a free reconnaissance map of the
 * service. Nginx already denies `/metrics` publicly, but the backend host
 * ports (8081 PROD, 9081 QA) are reachable directly, so the Nginx rule is not
 * the only door.
 *
 * When the variable is unset the endpoint is disabled and every request is
 * denied, so a misconfigured deployment never serves metrics anonymously.
 * Prometheus is not deployed yet (Phase 2 is deferred), so nothing scrapes
 * this today and failing closed breaks nothing.
 */
class MetricsAuthService(
    private val token: String?
) {
    private val logger = LoggerFactory.getLogger(MetricsAuthService::class.java)

    val enabled: Boolean = !token.isNullOrBlank()

    init {
        if (enabled) {
            logger.info("Metrics endpoint ENABLED (bearer token configured)")
        } else {
            logger.info(
                "Metrics endpoint not enabled ({} unset) — /metrics will reject all requests.",
                ENV_METRICS_TOKEN
            )
        }
    }

    /**
     * Returns true when [authorizationHeader] carries the configured bearer
     * token. The comparison is constant-time to avoid leaking the secret via
     * timing.
     */
    fun isAuthorized(authorizationHeader: String?): Boolean {
        val expected = token?.takeIf { enabled } ?: return false
        val provided = extractBearerToken(authorizationHeader) ?: return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private fun extractBearerToken(header: String?): String? {
        val raw = header?.trim().orEmpty()
        if (!raw.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) {
            return null
        }
        return raw.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        const val ENV_METRICS_TOKEN = "METRICS_TOKEN"
        private const val BEARER_PREFIX = "Bearer "

        fun fromEnv(rawToken: String? = System.getenv(ENV_METRICS_TOKEN)): MetricsAuthService =
            MetricsAuthService(token = rawToken?.trim()?.takeIf { it.isNotBlank() })
    }
}
