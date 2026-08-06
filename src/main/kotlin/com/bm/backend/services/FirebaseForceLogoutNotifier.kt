package com.bm.backend.services

import com.bm.backend.firebase.FirebaseAdminFactory
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Sends the force-logout signal as a data-only FCM topic message carrying the
 * revoked account's email. Clients compare it against their signed-in account
 * and sign out when it matches.
 */
class FirebaseForceLogoutNotifier(
    private val topic: String = DEFAULT_FORCE_LOGOUT_TOPIC
) : ForceLogoutNotifier {

    private val logger = LoggerFactory.getLogger(FirebaseForceLogoutNotifier::class.java)

    override suspend fun notifyForceLogout(email: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                FirebaseAdminFactory.init()

                val message = Message.builder()
                    .setTopic(topic)
                    .setAndroidConfig(
                        AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .build()
                    )
                    .putData("type", "force_logout")
                    .putData("email", email)
                    .build()

                FirebaseMessaging.getInstance().send(message)
            }.onSuccess { messageId ->
                logger.info(
                    "FCM force-logout notification sent. email={}, topic={}, messageId={}",
                    email,
                    topic,
                    messageId
                )
            }.onFailure { error ->
                logger.error(
                    "Failed to send FCM force-logout notification. email={}, error={}",
                    email,
                    error.message,
                    error
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_FORCE_LOGOUT_TOPIC = "force_logout"
    }
}
