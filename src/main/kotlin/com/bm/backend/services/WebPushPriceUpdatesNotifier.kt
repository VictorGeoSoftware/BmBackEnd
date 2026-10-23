package com.bm.backend.services

import com.bm.backend.models.PriceUpdatesNotification
import com.bm.backend.repositories.ports.WebPushTokensRepositoryPort
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Sends `price_updates` to every stored PowerAppWeb token (multicast, not topics), so a
 * deleted row (logout, grant wipe) stops delivery at once. Dead tokens are pruned.
 * Never throws. See docs/WEB_PUSH.md.
 */
class WebPushPriceUpdatesNotifier(
    private val repository: WebPushTokensRepositoryPort,
    private val sender: WebPushSender,
    private val json: Json = Json { encodeDefaults = true },
) : PriceUpdatesNotifier {

    private val logger = LoggerFactory.getLogger(WebPushPriceUpdatesNotifier::class.java)

    override suspend fun notify(notification: PriceUpdatesNotification) {
        try {
            send(notification)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to send web push price-updates. error={}", e.message, e)
        }
    }

    private suspend fun send(notification: PriceUpdatesNotification) {
        val tokens = repository.allTokens()
        if (tokens.isEmpty()) return
        val data = mapOf(
            "type" to "price_updates",
            "payload" to json.encodeToString(PriceUpdatesNotification.serializer(), notification),
        )
        var sent = 0
        var failed = 0
        val dead = mutableListOf<String>()
        for (chunk in tokens.chunked(MAX_TOKENS_PER_MULTICAST)) {
            val outcomes = try {
                sender.sendMulticast(chunk, data, TTL_SECONDS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += chunk.size
                logger.error("Web push multicast failed. tokens={}, error={}", chunk.size, e.message, e)
                continue
            }
            outcomes.forEach { outcome ->
                when (outcome.errorCode) {
                    null -> sent++
                    in PRUNE_ERROR_CODES -> { failed++; dead += outcome.token }
                    else -> failed++
                }
            }
        }
        val pruned = repository.deleteByTokens(dead)
        logger.info(
            "Web push price-updates sent. eventType={}, tokens={}, sent={}, failed={}, pruned={}, prunedTokens={}",
            notification.eventType, tokens.size, sent, failed, pruned, dead.map(WebPushTokensService::mask),
        )
    }

    companion object {
        const val MAX_TOKENS_PER_MULTICAST = 500
        const val TTL_SECONDS = 86_400L
        val PRUNE_ERROR_CODES = setOf("UNREGISTERED", "SENDER_ID_MISMATCH", "INVALID_ARGUMENT")
    }
}
