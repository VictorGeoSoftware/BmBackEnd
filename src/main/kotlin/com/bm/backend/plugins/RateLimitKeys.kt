package com.bm.backend.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import java.security.MessageDigest

/**
 * Rate-limit key for the caller of an API request.
 *
 * - Bearer token present: `user:<sha256(token)>`. One Firebase session = one bucket. The token is
 *   not verified here (that would be a second Firebase check per request); an invented token only
 *   buys its own bucket and is rejected with 401 by the route anyway. A global backstop bounds the
 *   total, see `API_GLOBAL_RATE_LIMIT`.
 * - Otherwise: `ip:<address>`, using `X-Real-IP` (set by nginx to the real client address) and
 *   falling back to the socket peer for internal callers on bm-network (n8n), which bypass nginx.
 *   The backend ports are bound to 127.0.0.1, so only nginx or internal containers can set that
 *   header.
 *
 * The raw token is never used as the key: the limiter keeps keys in memory.
 */
object RateLimitKeys {
    private const val BEARER_PREFIX = "Bearer "

    fun callerKey(call: ApplicationCall): String =
        callerKey(
            authorization = call.request.header(HttpHeaders.Authorization),
            realIp = call.request.header("X-Real-IP"),
            peer = call.request.origin.remoteHost
        )

    fun callerKey(authorization: String?, realIp: String?, peer: String): String {
        val raw = authorization?.trim().orEmpty()
        if (raw.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) {
            val token = raw.substring(BEARER_PREFIX.length).trim()
            if (token.isNotEmpty()) return "user:${sha256(token)}"
        }
        val ip = realIp?.trim()?.takeIf { it.isNotEmpty() } ?: peer
        return "ip:$ip"
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
