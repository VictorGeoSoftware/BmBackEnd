package com.bm.backend.services

import com.bm.backend.repositories.ports.WebPushTokensRepositoryPort
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory

/**
 * Stores the FCM web push tokens of the PowerAppWeb PWA. See docs/WEB_PUSH.md.
 *
 * Tokens are never logged in full (first 12 characters only, like BmApp).
 */
class WebPushTokensService(
    private val webPushTokensRepository: WebPushTokensRepositoryPort
) {
    private val logger = LoggerFactory.getLogger(WebPushTokensService::class.java)

    fun register(rawToken: String?, rawPlatform: String?, uid: String, email: String) {
        val token = validToken(rawToken)
        val platform = rawPlatform?.trim()?.lowercase()
        require(platform in PLATFORMS) { "platform must be one of ${PLATFORMS.joinToString()}" }
        webPushTokensRepository.upsert(token, uid, email.trim().lowercase(), platform!!)
        logger.info(
            "Web push token registered {} {} {}",
            kv("userEmail", email), kv("platform", platform), kv("token", mask(token))
        )
    }

    fun unregister(rawToken: String?, uid: String): Int {
        val token = validToken(rawToken)
        val deleted = webPushTokensRepository.deleteByTokenForUid(token, uid)
        logger.info(
            "Web push token unregistered {} {} {}",
            kv("userId", uid), kv("token", mask(token)), kv("deleted", deleted)
        )
        return deleted
    }

    private fun validToken(rawToken: String?): String {
        val token = rawToken?.trim().orEmpty()
        require(token.isNotEmpty()) { "token is required" }
        require(token.length <= MAX_TOKEN_LENGTH) { "token is too long" }
        return token
    }

    companion object {
        const val MAX_TOKEN_LENGTH = 4096
        val PLATFORMS = setOf("ios", "android", "desktop")

        fun mask(token: String): String = token.take(12) + "…"
    }
}
