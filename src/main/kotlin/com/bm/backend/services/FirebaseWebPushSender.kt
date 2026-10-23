package com.bm.backend.services

import com.bm.backend.firebase.FirebaseAdminFactory
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.WebpushConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data-only multicast: no `notification` block, the PWA service worker (`/sw.js`)
 * builds the notification itself.
 */
class FirebaseWebPushSender : WebPushSender {

    override suspend fun sendMulticast(
        tokens: List<String>,
        data: Map<String, String>,
        ttlSeconds: Long,
    ): List<WebPushSender.SendOutcome> = withContext(Dispatchers.IO) {
        FirebaseAdminFactory.init()
        val message = MulticastMessage.builder()
            .addAllTokens(tokens)
            .putAllData(data)
            .setWebpushConfig(
                WebpushConfig.builder()
                    .putHeader("Urgency", "high")
                    .putHeader("TTL", ttlSeconds.toString())
                    .build()
            )
            .build()
        val response = FirebaseMessaging.getInstance().sendEachForMulticast(message)
        response.responses.mapIndexed { index, result ->
            WebPushSender.SendOutcome(
                token = tokens[index],
                errorCode = if (result.isSuccessful) null
                else result.exception?.messagingErrorCode?.name ?: "UNKNOWN"
            )
        }
    }
}
