package com.bm.backend.services

import com.bm.backend.firebase.FirebaseAdminFactory
import com.bm.backend.models.PriceUpdatesNotification
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class FirebasePriceUpdatesNotifier(
    private val topic: String = DEFAULT_PRICE_UPDATES_TOPIC,
    private val json: Json = Json { encodeDefaults = true }
) : PriceUpdatesNotifier {

    private val logger = LoggerFactory.getLogger(FirebasePriceUpdatesNotifier::class.java)

    override suspend fun notify(notification: PriceUpdatesNotification) {
        withContext(Dispatchers.IO) {
            runCatching {
                FirebaseAdminFactory.init()

                val payload = json.encodeToString(PriceUpdatesNotification.serializer(), notification)
                val message = Message.builder()
                    .setTopic(topic)
                    .setAndroidConfig(
                        AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .build()
                    )
                    .putData("type", "price_updates")
                    .putData("payload", payload)
                    .build()

                FirebaseMessaging.getInstance().send(message)
            }.onSuccess { messageId ->
                logger.info(
                    "FCM price-updates notification sent. eventType={}, topic={}, messageId={}",
                    notification.eventType,
                    topic,
                    messageId
                )
            }.onFailure { error ->
                logger.error(
                    "Failed to send FCM price-updates notification. eventType={}, topic={}, error={}",
                    notification.eventType,
                    topic,
                    error.message,
                    error
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_PRICE_UPDATES_TOPIC = "price_updates"
    }
}
